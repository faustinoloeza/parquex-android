package com.faustinoloeza.parquex.map

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.faustinoloeza.parquex.data.ParkData
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sinh

/**
 * Mapa real bajo los modelos 3D: teselas raster de OpenStreetMap tendidas en el plano y = 0, como
 * `src/layers/basemap.js` de la web.
 *
 * Las teselas se eligen por tesela y no por pantalla: las cercanas con zoom fino y las lejanas
 * (hacia el horizonte) gruesas, para que cada una ocupe en pantalla más o menos su tamaño en
 * píxeles. Mientras una no llega se dibuja su ancestro cargado más cercano recortado por UV (cada
 * cuadro de pantalla es una hoja: nunca se enciman). Debajo va un plano liso del color de tierra.
 *
 * Con área diseñada ([setDesignArea], cuando el GLB trae el suelo ilustrado del parque) el mapa se
 * mezcla como en la web: no se dibuja ni se descarga dentro del parque, y en la franja del contorno
 * las teselas van translúcidas, en una malla con alfa por vértice (`ParkData.DesignArea.fade`).
 *
 * Solo se recalcula cuando la cámara cambia o llega una tesela ([dirty]); con el mapa quieto no
 * hace nada. Texturas en una caché LRU acotada ([CAPACITY]); en disco, la caché HTTP de [TileLoader].
 */
internal class GroundTiles(
    context: Context,
    private val engine: Engine,
    private val scene: Scene,
    private val materials: UnlitMaterials,
    private val onChange: () -> Unit,
) {
    companion object {
        /**
         * Teselas {z}/{x}/{y}: OpenStreetMap estándar, igual que `BASEMAP` en `src/config.js` de la
         * web. Sirve para desarrollo y tráfico moderado (https://operations.osmfoundation.org/policies/tiles/);
         * en producción apuntar a un proveedor o servidor propio con el mismo esquema.
         */
        const val URL_TEMPLATE = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        const val ATTRIBUTION_URL = "https://www.openstreetmap.org/copyright"
        /** Color de tierra del estilo de OSM: se ve mientras cargan las teselas. */
        const val LAND = 0xF2EFE9

        /** Canal de dibujo: lo primero de todo (ver [ParkRenderer]). */
        const val CHANNEL = 0
        /** Prioridad de las teselas translúcidas del borde: encima del suelo del parque. */
        const val PRIORITY_FADE = 6

        /** Celdas por lado de la malla de una tesela del borde (alfa por vértice). */
        private const val FADE_GRID = 24

        private const val TILE_SIZE = 256
        private const val MIN_ZOOM = 3
        private const val MAX_ZOOM = 19
        private const val ROOT_ZOOM = 10
        /** Texturas en GPU (~350 KB cada una con mipmaps). */
        private const val CAPACITY = 80
        /** Tope de teselas dibujadas (draw calls); si se pasa, se elige con menos detalle. */
        private const val MAX_LEAVES = 48
        private const val MAX_IN_FLIGHT = 4
        private const val RETRY_MS = 15_000L
        private const val TAG = "GroundTiles"

        private fun key(z: Int, x: Int, y: Int) = (z.toLong() shl 48) or (x.toLong() shl 24) or y.toLong()

        private fun tileLng(x: Int, z: Int) = x.toDouble() / (1 shl z) * 360.0 - 180.0
        private fun tileLat(y: Int, z: Int) = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * y / (1 shl z)))))
        private fun tileX(lng: Double, z: Int) = (lng + 180.0) / 360.0 * (1 shl z)
        private fun tileY(lat: Double, z: Int): Double {
            val s = sin(Math.toRadians(lat))
            return (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * (1 shl z)
        }
    }

    private class Leaf(val x: Int, val y: Int, val z: Int, val x0: Float, val z0: Float, val x1: Float, val z1: Float, val dist: Float) {
        val key get() = key(z, x, y)
    }

    /** Cuadro del suelo: [grid] × [grid] celdas (1 = un solo cuadrado; más = alfa por vértice). */
    private class Quad(val entity: Int, val vb: VertexBuffer, val mi: MaterialInstance, val grid: Int) {
        var texture: Texture? = null
        var inScene = false
        /** x0, z0, x1, z1, u0, v0, u1, v1 ya escritos en el búfer. */
        val state = FloatArray(8) { Float.NaN }
        var areaVersion = -1
    }

    /** true cuando hay que volver a elegir teselas aunque la cámara no se mueva. */
    var dirty = true
        private set

    private var park: ParkData? = null
    private var area: ParkData.DesignArea? = null
    /** Región de contexto que rodea al parque (p. ej. "Zona Xcaret"); su suelo tapa el mapa. */
    private var region: ParkData.DesignArea? = null
    private var areaVersion = 0
    private val loader = TileLoader(context, MAX_IN_FLIGHT)
    private val main = Handler(Looper.getMainLooper())
    /**
     * Píxeles de pantalla por píxel de tesela antes de pasar a un zoom más fino. ≈ la densidad: una
     * tesela de 256 px ocupa a lo más ~230 dp, como en un mapa web (textos a su tamaño normal).
     */
    private val baseDetail = (context.resources.displayMetrics.density * 0.9f).coerceIn(1f, 3.5f)
    private var detail = baseDetail
    private var destroyed = false

    private val cache = LinkedHashMap<Long, Texture>(128, 0.75f, true) // orden de acceso = LRU
    private val inFlight = HashSet<Long>()
    private val failed = HashMap<Long, Long>()
    private val inUse = HashSet<Texture>()

    private val sampler = TextureSampler(
        TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE,
    ).apply { anisotropy = 4f }
    private val placeholder: Texture = Texture.Builder().width(1).height(1).levels(1)
        .format(Texture.InternalFormat.SRGB8_A8).build(engine).also { t ->
            val px = UnlitMaterials.directBuffer(4)
            px.put((LAND shr 16).toByte()).put((LAND shr 8).toByte()).put(LAND.toByte()).put((-1).toByte()).flip()
            t.setImage(engine, 0, Texture.PixelBufferDescriptor(px, Texture.Format.RGBA, Texture.Type.UBYTE))
        }

    private val indexBuffer = gridIndices(1)
    private val fadeIndexBuffer = gridIndices(FADE_GRID)

    private val background = newQuad(materials.color(LAND, "map-land"), priority = 0, grid = 1)
    private val quads = ArrayList<Quad>()
    private val fadeQuads = ArrayList<Quad>()
    private val leaves = ArrayList<Leaf>()
    private val missing = ArrayList<Leaf>()
    private var lastUsed = -1

    fun setPark(park: ParkData?) {
        this.park = park
        dirty = true
    }

    /**
     * Nivel de noche [k]: las teselas (sin iluminación) multiplican su color por mix(1, [tint], k)
     * (night.json → map.nightTint). La "elevación" (lift) de la web no se aplica: el unlit no tiene término
     * aditivo y la diferencia es mínima.
     */
    fun setNight(k: Float, tint: FloatArray) {
        val r = 1f + (tint[0] - 1f) * k; val g = 1f + (tint[1] - 1f) * k; val b = 1f + (tint[2] - 1f) * k
        nightFactor[0] = r; nightFactor[1] = g; nightFactor[2] = b
        for (q in quads + fadeQuads) q.mi.setParameter("baseColorFactor", r, g, b, 1f)
        val land = landLinear
        background.mi.setParameter("baseColorFactor", land[0] * r, land[1] * g, land[2] * b, 1f)
        dirty = true
    }

    private val nightFactor = floatArrayOf(1f, 1f, 1f)
    private val landLinear = com.faustinoloeza.parquex.data.ParkNight.linear(LAND)

    /** Multiplica el umbral de detalle (calidad gráfica): > 1 = teselas más gruesas, menos descargas. */
    fun setDetailScale(scale: Float) {
        val d = baseDetail * scale
        if (d == detail) return
        detail = d
        dirty = true
    }

    /** Área del parque donde no se dibuja el mapa (null = mapa en todas partes). */
    fun setDesignArea(area: ParkData.DesignArea?) {
        if (area === this.area) return
        this.area = area
        areaVersion++
        dirty = true
    }

    /**
     * Región de contexto alrededor del parque ([ParkData.DesignArea] en el marco del parque). Su suelo
     * (`ctx-ground`) se dibuja encima del mapa con alfa 1 por dentro: las teselas que quedan
     * enteras dentro no se dibujan, y en la franja donde el parque se funde con ella el alfa del mapa
     * se corrige para no asomarse (`context.json → basemapYield.regionCorrection`).
     */
    fun setRegion(region: ParkData.DesignArea?) {
        if (region === this.region) return
        this.region = region
        areaVersion++
        dirty = true
    }

    /**
     * Alfa del mapa en (x, z): f = fundido del parque; con región, f·(1 − r) / max(1 − r·f, 1e-4)
     * con r = 1 − fundido de la región (así, sumado al suelo de la región, el mapa no se asoma).
     */
    private fun mapAlpha(area: ParkData.DesignArea, x: Float, z: Float): Float {
        val f = area.fade(x, z)
        val reg = region ?: return f
        val r = 1f - reg.fade(x, z)
        return f * (1f - r) / max(1f - r * f, 1e-4f)
    }

    // ---------------------------------------------------------------------------- selección

    /** Elige y coloca las teselas para la vista [snap]; pide las que falten. Hilo principal. */
    fun update(snap: CameraSnapshot) {
        dirty = false
        val range = min(snap.far * 0.98f, 60_000f)
        val ex = snap.eye[0]; val ez = snap.eye[2]
        assign(background, ex - range, ez - range, ex + range, ez + range, 0f, 0f, 1f, 1f, null)

        val park = park
        if (park == null) { hideFrom(quads, 0); hideFrom(fadeQuads, 0); return }
        val area = area

        var d = detail
        select(park, snap, range, d)
        while (leaves.size > MAX_LEAVES && d < 8f) {
            d *= 1.5f
            select(park, snap, range, d)
        }

        // Cada hoja con su textura o, mientras llega, la de su ancestro cargado más cercano.
        inUse.clear()
        missing.clear()
        var used = 0
        var usedFade = 0
        for (leaf in leaves) {
            // Dentro del parque no hay mapa (ni descarga); en la franja del contorno, translúcido.
            val fading = when {
                region?.coversRect(leaf.x0, leaf.z0, leaf.x1, leaf.z1) == true -> continue // tapada por la región
                area == null -> false
                area.coversRect(leaf.x0, leaf.z0, leaf.x1, leaf.z1) -> continue
                // Solo la franja del parque es translúcida; en la de la región el alfa del mapa es 1.
                else -> !area.outsideRect(leaf.x0, leaf.z0, leaf.x1, leaf.z1)
            }
            var tex = cache[leaf.key]
            var u0 = 0f; var v0 = 0f; var u1 = 1f; var v1 = 1f
            if (tex == null) {
                missing += leaf
                var ax = leaf.x; var ay = leaf.y; var az = leaf.z
                while (tex == null && az > MIN_ZOOM) {
                    ax = ax shr 1; ay = ay shr 1; az--
                    tex = cache[key(az, ax, ay)]
                }
                if (tex == null) continue
                val n = (1 shl (leaf.z - az)).toFloat()
                val ox = leaf.x - ax * (1 shl (leaf.z - az))
                val oy = leaf.y - ay * (1 shl (leaf.z - az))
                u0 = ox / n; u1 = (ox + 1) / n; v0 = oy / n; v1 = (oy + 1) / n
            }
            inUse += tex
            val q = if (fading) {
                if (usedFade == fadeQuads.size) fadeQuads += newTile(fade = true)
                fadeQuads[usedFade++]
            } else {
                if (used == quads.size) quads += newTile(fade = false)
                quads[used++]
            }
            assign(q, leaf.x0, leaf.z0, leaf.x1, leaf.z1, u0, v0, u1, v1, tex)
        }
        hideFrom(quads, used)
        hideFrom(fadeQuads, usedFade)
        if (used + usedFade != lastUsed) {
            lastUsed = used + usedFade
            Log.d(TAG, "Teselas dibujadas: $lastUsed ($usedFade en el borde del parque; ${cache.size} en GPU, ${inFlight.size} descargando)")
        }
        requestMissing()
        evict()
    }

    private fun select(park: ParkData, snap: CameraSnapshot, range: Float, detail: Float) {
        leaves.clear()
        val ex = snap.eye[0]; val ez = snap.eye[2]
        val n = (1 shl ROOT_ZOOM) - 1
        val tx0 = floor(tileX(park.lngAt(ex - range), ROOT_ZOOM)).toInt().coerceIn(0, n)
        val tx1 = floor(tileX(park.lngAt(ex + range), ROOT_ZOOM)).toInt().coerceIn(0, n)
        val ty0 = floor(tileY(park.latAt(ez - range), ROOT_ZOOM)).toInt().coerceIn(0, n) // norte
        val ty1 = floor(tileY(park.latAt(ez + range), ROOT_ZOOM)).toInt().coerceIn(0, n)
        for (y in ty0..ty1) for (x in tx0..tx1) visit(park, snap, range, detail, x, y, ROOT_ZOOM)
    }

    private fun visit(park: ParkData, snap: CameraSnapshot, range: Float, detail: Float, x: Int, y: Int, z: Int) {
        if (leaves.size > MAX_LEAVES * 4) return // demasiado detalle: select() lo reintenta más grueso
        val x0 = park.localX(tileLng(x, z)); val x1 = park.localX(tileLng(x + 1, z))
        val z0 = park.localZ(tileLat(y, z)); val z1 = park.localZ(tileLat(y + 1, z))
        if (!snap.boxVisible(x0, x1, -2f, 2f, z0, z1)) return
        val dist = snap.distanceToRect(x0, x1, z0, z1)
        if (dist > range) return
        val px = (x1 - x0) * snap.focalPx / max(dist, 1f)
        if (px > TILE_SIZE * detail && z < MAX_ZOOM) {
            for (k in 0 until 4) visit(park, snap, range, detail, x * 2 + (k and 1), y * 2 + (k shr 1), z + 1)
        } else {
            leaves += Leaf(x, y, z, x0, z0, x1, z1, dist)
        }
    }

    // ---------------------------------------------------------------------------- carga

    private fun requestMissing() {
        if (inFlight.size >= MAX_IN_FLIGHT || missing.isEmpty()) return
        // Primero las gruesas (cubren rápido), luego las cercanas.
        missing.sortWith(compareBy<Leaf>({ it.z }, { it.dist }))
        val now = System.currentTimeMillis()
        for (leaf in missing) {
            if (inFlight.size >= MAX_IN_FLIGHT) break
            val k = leaf.key
            if (k in inFlight) continue
            val failedAt = failed[k]
            if (failedAt != null && now - failedAt < RETRY_MS) continue
            inFlight += k
            val url = URL_TEMPLATE.replace("{z}", leaf.z.toString())
                .replace("{x}", leaf.x.toString()).replace("{y}", leaf.y.toString())
            loader.load(url) { pixels -> onLoaded(k, pixels) }
        }
    }

    private fun onLoaded(key: Long, pixels: TileLoader.Pixels?) {
        inFlight -= key
        if (destroyed) return
        if (pixels == null) {
            failed[key] = System.currentTimeMillis()
            main.postDelayed({ if (!destroyed) { dirty = true; onChange() } }, RETRY_MS)
        } else {
            failed -= key
            val levels = 1 + floor(ln(max(pixels.width, pixels.height).toDouble()) / ln(2.0)).toInt()
            val tex = Texture.Builder()
                .width(pixels.width).height(pixels.height).levels(levels)
                .sampler(Texture.Sampler.SAMPLER_2D)
                .format(Texture.InternalFormat.SRGB8_A8)
                .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE)
                .build(engine)
            tex.setImage(engine, 0, Texture.PixelBufferDescriptor(pixels.rgba, Texture.Format.RGBA, Texture.Type.UBYTE))
            tex.generateMipmaps(engine)
            cache.put(key, tex)?.let { engine.destroyTexture(it) }
        }
        dirty = true
        onChange()
    }

    /** Suelta las texturas menos usadas que no se están dibujando. */
    private fun evict() {
        if (cache.size <= CAPACITY) return
        val it = cache.entries.iterator()
        while (cache.size > CAPACITY && it.hasNext()) {
            val e = it.next()
            if (e.value in inUse) continue
            engine.destroyTexture(e.value)
            it.remove()
        }
    }

    // ---------------------------------------------------------------------------- cuadros

    private fun newTile(fade: Boolean): Quad {
        val mi = if (fade) materials.texturedFade("map-tile-fade") else materials.textured("map-tile")
        mi.setParameter("baseColorMap", placeholder, sampler)
        mi.setParameter("baseColorFactor", nightFactor[0], nightFactor[1], nightFactor[2], 1f)
        return if (fade) newQuad(mi, PRIORITY_FADE, FADE_GRID) else newQuad(mi, priority = 1, grid = 1)
    }

    private fun newQuad(mi: MaterialInstance, priority: Int, grid: Int): Quad {
        val vb = UnlitMaterials.vertexBuffer(engine, (grid + 1) * (grid + 1))
        val entity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(Box(0f, 0f, 0f, 1f, 1f, 1f))
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb,
                if (grid == 1) indexBuffer else fadeIndexBuffer, 0, grid * grid * 6)
            .material(0, mi)
            .channel(CHANNEL)
            .priority(priority)
            .culling(false) // solo se colocan teselas visibles
            .castShadows(false)
            .receiveShadows(false)
            .build(engine, entity)
        return Quad(entity, vb, mi, grid)
    }

    /** Índices de una malla de [grid] × [grid] celdas, vértices fila por fila (norte → sur). */
    private fun gridIndices(grid: Int): IndexBuffer {
        val ib = IndexBuffer.Builder().indexCount(grid * grid * 6)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT).build(engine)
        val b = UnlitMaterials.directBuffer(grid * grid * 6 * 2)
        val row = grid + 1
        for (j in 0 until grid) for (i in 0 until grid) {
            val a = j * row + i; val c = a + row
            for (k in intArrayOf(a, c, a + 1, a + 1, c, c + 1)) b.putShort(k.toShort())
        }
        b.flip()
        ib.setBuffer(engine, b)
        return ib
    }

    private fun assign(
        q: Quad, x0: Float, z0: Float, x1: Float, z1: Float,
        u0: Float, v0: Float, u1: Float, v1: Float, texture: Texture?,
    ) {
        val s = q.state
        val fadeChanged = q.grid > 1 && q.areaVersion != areaVersion
        if (fadeChanged || s[0] != x0 || s[1] != z0 || s[2] != x1 || s[3] != z1 || s[4] != u0 || s[5] != v0 || s[6] != u1 || s[7] != v1) {
            s[0] = x0; s[1] = z0; s[2] = x1; s[3] = z1; s[4] = u0; s[5] = v0; s[6] = u1; s[7] = v1
            q.areaVersion = areaVersion
            val n = q.grid
            val count = (n + 1) * (n + 1)
            val pos = UnlitMaterials.directBuffer(count * 12)
            val uvs = FloatArray(count * 2)
            val area = area
            val alphas = if (n > 1 && area != null) FloatArray(count) else null
            var k = 0
            // v = 0 en el borde norte, como las filas de la imagen.
            for (j in 0..n) for (i in 0..n) {
                val fx = i.toFloat() / n; val fz = j.toFloat() / n
                val x = x0 + (x1 - x0) * fx; val z = z0 + (z1 - z0) * fz
                pos.putFloat(x).putFloat(0f).putFloat(z)
                uvs[k * 2] = u0 + (u1 - u0) * fx; uvs[k * 2 + 1] = v0 + (v1 - v0) * fz
                if (alphas != null) alphas[k] = mapAlpha(area!!, x, z)
                k++
            }
            pos.flip()
            q.vb.setBufferAt(engine, 0, pos)
            q.vb.setBufferAt(engine, 1, UnlitMaterials.staticAttributes(count, uvs, alphas))
        }
        if (texture != null && q.texture !== texture) {
            q.mi.setParameter("baseColorMap", texture, sampler)
            q.texture = texture
        }
        if (!q.inScene) { scene.addEntity(q.entity); q.inScene = true }
    }

    private fun hideFrom(pool: List<Quad>, index: Int) {
        for (i in index until pool.size) {
            val q = pool[i]
            if (q.inScene) { scene.remove(q.entity); q.inScene = false }
            // Suelta la referencia: la textura puede salir de la caché.
            if (q.texture != null) { q.mi.setParameter("baseColorMap", placeholder, sampler); q.texture = null }
        }
    }

    // ---------------------------------------------------------------------------- limpieza

    fun destroy() {
        destroyed = true
        loader.close()
        main.removeCallbacksAndMessages(null)
        for (q in quads + fadeQuads + background) {
            scene.remove(q.entity)
            engine.destroyEntity(q.entity)
            EntityManager.get().destroy(q.entity)
            engine.destroyVertexBuffer(q.vb)
            engine.destroyMaterialInstance(q.mi)
        }
        quads.clear()
        fadeQuads.clear()
        engine.destroyIndexBuffer(indexBuffer)
        engine.destroyIndexBuffer(fadeIndexBuffer)
        for (t in cache.values) engine.destroyTexture(t)
        cache.clear()
        engine.destroyTexture(placeholder)
    }
}
