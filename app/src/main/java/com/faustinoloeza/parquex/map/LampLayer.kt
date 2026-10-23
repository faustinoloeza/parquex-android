package com.faustinoloeza.parquex.map

import com.faustinoloeza.parquex.data.Lamps
import com.faustinoloeza.parquex.data.ParkNight
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
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Faroles de noche (`lamps.bin`), como `layers/lamps.js` de la web:
 *
 * - **Postes**: una sola malla (poste de 5 lados + linterna) para todos, unlit mate `#2c3036`; ocultos con la
 *   cámara a más de [ParkNight.LampStyle.postDistance] m (serían sub-píxel).
 * - **Cabezas**: un disco de cara a la cámara por farol (también los de parques vecinos), radio 1.3 m pero nunca
 *   menos de 3.2 px, con brillo radial cálido. La web suma (aditiva); aquí van con alfa premultiplicado = cobertura
 *   del brillo (con alfa 0 el unlit translúcido del ubershader no dibujaba nada) y sobre el fondo de noche se ve
 *   igual. Se recolocan en CPU cuando la cámara cambia, como las rutas.
 *
 * Solo se ven de noche ([setLevel] > 0.001). Sin luces reales: los charcos de luz van horneados en el GLB.
 */
internal class LampLayer(
    private val engine: Engine,
    private val scene: Scene,
    private val materials: UnlitMaterials,
    private val density: Float,
) {
    companion object {
        const val CHANNEL_POSTS = ParkRenderer.CHANNEL_MODELS
        /** Después del agua y de todo lo demás. */
        const val CHANNEL_HEADS = 6
        private const val GLOW_SIZE = 64
        /** El brillo del centro pasa de 1 (core + halo ≤ 1.85): la textura guarda la mitad. */
        private const val GLOW_SCALE = 2f
    }

    var dirty = false
        private set

    private var style: ParkNight.LampStyle? = null
    private var level = 0f
    private var shown = false
    private var postsShown = false

    // Cabezas: centro (x, y, z) por farol.
    private var heads = FloatArray(0)
    private var headCount = 0
    private var headVerts = FloatArray(0)
    private var headVb: VertexBuffer? = null
    private var headIb: IndexBuffer? = null
    private var headRing: PositionRing? = null
    private var headEntity = 0
    private var headMi: MaterialInstance? = null

    private var postVb: VertexBuffer? = null
    private var postIb: IndexBuffer? = null
    private var postEntity = 0
    private var postMi: MaterialInstance? = null

    private var glow: Texture? = null

    /** Reemplaza los faroles: [park] con poste y cabeza, [neighbors] (parques vecinos) solo cabeza. */
    fun setLamps(park: Lamps, neighbors: Lamps, style: ParkNight.LampStyle?) {
        clear()
        this.style = style ?: return
        if (park.count + neighbors.count == 0) return
        if (glow == null) glow = makeGlow(style)
        buildPosts(park, style)
        buildHeads(park, neighbors, style)
        shown = false; postsShown = false
        applyVisibility(postsVisible = true)
        dirty = true
    }

    /** Nivel de noche k (0…1): visibilidad y brillo. */
    fun setLevel(k: Float) {
        if (k == level) return
        level = k
        headMi?.let { mi ->
            // Premultiplicado: color × 2 (la textura guarda la mitad) y alfa, ambos × k.
            val v = GLOW_SCALE * k
            mi.setParameter("baseColorFactor", v, v, v, k)
        }
        applyVisibility(postsVisible = postsShown || !shown)
        dirty = true
    }

    /** Recoloca las cabezas para la vista [snap] y oculta los postes de lejos. Hilo principal. */
    fun update(snap: CameraSnapshot) {
        dirty = false
        val s = style ?: return
        if (!shown) return
        applyVisibility(postsVisible = snap.distance <= s.postDistance)
        val vb = headVb ?: return
        val f = snap.focalPx
        val right = snap.right; val up = snap.up
        var o = 0
        for (i in 0 until headCount) {
            val cx = heads[i * 3]; val cy = heads[i * 3 + 1]; val cz = heads[i * 3 + 2]
            val depth = snap.depth(cx, cy, cz)
            // Radio en el mundo: 1.3 m, pero nunca menos de minPx (dp) en pantalla.
            val r = if (depth > 0f) max(s.headRadius, s.headMinPx * density * depth / f) else 0f
            for ((sx, sy) in CORNERS) {
                headVerts[o++] = cx + (right[0] * sx + up[0] * sy) * r
                headVerts[o++] = cy + (right[1] * sx + up[1] * sy) * r
                headVerts[o++] = cz + (right[2] * sx + up[2] * sy) * r
            }
        }
        headRing?.upload(engine, vb, headVerts)
    }

    private val CORNERS = arrayOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)

    private fun applyVisibility(postsVisible: Boolean) {
        val on = level > 0.001f && style != null
        if (on != shown) {
            shown = on
            if (headVb != null) { if (on) scene.addEntity(headEntity) else scene.remove(headEntity) }
            if (!on && postsShown) { scene.remove(postEntity); postsShown = false }
        }
        val posts = on && postsVisible && postVb != null
        if (posts != postsShown) {
            postsShown = posts
            if (posts) scene.addEntity(postEntity) else scene.remove(postEntity)
        }
    }

    // ---------------------------------------------------------------------------- geometría

    private fun buildHeads(park: Lamps, neighbors: Lamps, s: ParkNight.LampStyle) {
        headCount = park.count + neighbors.count
        heads = FloatArray(headCount * 3)
        var o = 0
        for (l in listOf(park, neighbors)) for (i in 0 until l.count) {
            heads[o++] = l.x[i]; heads[o++] = l.y[i] + s.headAboveGround; heads[o++] = l.z[i]
        }
        val n = headCount * 4
        headVerts = FloatArray(n * 3)
        val vb = UnlitMaterials.vertexBuffer(engine, n)
        val uvs = FloatArray(n * 2)
        for (i in 0 until headCount) {
            val b = i * 8
            uvs[b] = 0f; uvs[b + 1] = 0f; uvs[b + 2] = 1f; uvs[b + 3] = 0f
            uvs[b + 4] = 0f; uvs[b + 5] = 1f; uvs[b + 6] = 1f; uvs[b + 7] = 1f
        }
        vb.setBufferAt(engine, 1, UnlitMaterials.staticAttributes(n, uvs))
        val indices = ArrayList<Int>(headCount * 6)
        for (i in 0 until headCount) {
            val a = i * 4
            indices += a; indices += a + 2; indices += a + 1; indices += a + 1; indices += a + 2; indices += a + 3
        }
        val ib = ScreenLines.indexBuffer(engine, indices, n)
        val mi = materials.texturedBlend(0xFFFFFF, "lamp-heads")
        mi.setParameter("baseColorMap", glow!!, TextureSampler(
            TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE,
        ))
        mi.setDepthCulling(true) // los edificios tapan las cabezas que quedan detrás
        headVb = vb; headIb = ib; headMi = mi; headRing = PositionRing(n)
        headEntity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(Box(0f, 0f, 0f, 1f, 1f, 1f))
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib, 0, indices.size)
            .material(0, mi)
            .channel(CHANNEL_HEADS)
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            .build(engine, headEntity)
    }

    private fun buildPosts(park: Lamps, s: ParkNight.LampStyle) {
        if (park.count == 0) return
        val sides = s.sides
        // Por farol: poste (2 anillos), linterna (2 anillos) y tapa (centro).
        val perLamp = sides * 4 + 1
        val n = park.count * perLamp
        val pos = UnlitMaterials.directBuffer(n * 12)
        val indices = ArrayList<Int>(park.count * sides * 15)
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE; var minY = Float.MAX_VALUE
        for (i in 0 until park.count) {
            val bx = park.x[i]; val by = park.y[i] - s.postSink; val bz = park.z[i]
            val base = i * perLamp
            val lanternMid = by + s.postHeight + s.lanternAbovePost
            val rings = arrayOf(
                s.poleRadiusBottom to by,
                s.poleRadiusTop to by + s.postHeight,
                s.lanternRadiusBottom to lanternMid - s.lanternHeight / 2f,
                s.lanternRadiusTop to lanternMid + s.lanternHeight / 2f,
            )
            for ((r, y) in rings) for (k in 0 until sides) {
                val a = 2 * PI * k / sides
                val x = bx + (r * cos(a)).toFloat(); val z = bz + (r * sin(a)).toFloat()
                pos.putFloat(x).putFloat(y).putFloat(z)
                minX = minOf(minX, x); maxX = maxOf(maxX, x); minZ = minOf(minZ, z); maxZ = maxOf(maxZ, z)
                minY = minOf(minY, y); maxY = maxOf(maxY, y)
            }
            pos.putFloat(bx).putFloat(rings[3].second).putFloat(bz) // centro de la tapa
            for (ring in intArrayOf(0, 2)) for (k in 0 until sides) {
                val a = base + ring * sides + k; val b = base + ring * sides + (k + 1) % sides
                val c = a + sides; val d = b + sides
                indices += a; indices += c; indices += b; indices += b; indices += c; indices += d
            }
            val center = base + sides * 4
            for (k in 0 until sides) {
                indices += center; indices += base + 3 * sides + k; indices += base + 3 * sides + (k + 1) % sides
            }
        }
        pos.flip()
        val vb = UnlitMaterials.vertexBuffer(engine, n)
        vb.setBufferAt(engine, 0, pos)
        vb.setBufferAt(engine, 1, UnlitMaterials.staticAttributes(n, null))
        val ib = ScreenLines.indexBuffer(engine, indices, n)
        val mi = materials.color(s.postColor, "lamp-posts")
        mi.setDepthCulling(true); mi.setDepthWrite(true) // poste sólido, con profundidad
        postVb = vb; postIb = ib; postMi = mi
        postEntity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(Box((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2,
                (maxX - minX) / 2 + 1f, (maxY - minY) / 2 + 1f, (maxZ - minZ) / 2 + 1f))
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib, 0, indices.size)
            .material(0, mi)
            .channel(CHANNEL_POSTS)
            .castShadows(false)
            .receiveShadows(false)
            .build(engine, postEntity)
    }

    /**
     * Textura del brillo (lineal, alfa 0): d = distancia al centro; core = smoothstep(0.42, 0.12, d),
     * halo = (1 − d)^1.6 · 0.85, color = mix(farol, núcleo, core) · (core + halo); 0 fuera del disco.
     */
    private fun makeGlow(s: ParkNight.LampStyle): Texture {
        val n = GLOW_SIZE
        val lamp = ParkNight.linear(s.headColor); val core = ParkNight.linear(s.headCore)
        val px = UnlitMaterials.directBuffer(n * n * 4)
        for (j in 0 until n) for (i in 0 until n) {
            val dx = (i + 0.5f) / n * 2f - 1f; val dy = (j + 0.5f) / n * 2f - 1f
            val d = sqrt(dx * dx + dy * dy)
            if (d > 1f) { repeat(4) { px.put(0.toByte()) }; continue }
            val c = smoothstep(0.42f, 0.12f, d)
            val h = (1f - d).pow(1.6f) * 0.85f
            for (ch in 0..2) {
                val v = (lamp[ch] + (core[ch] - lamp[ch]) * c) * (c + h) / GLOW_SCALE
                px.put((v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
            }
            // Alfa = cobertura (el color ya va multiplicado por ella: premultiplicado).
            px.put(((c + h).coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
        }
        px.flip()
        val t = Texture.Builder().width(n).height(n).levels(7)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.RGBA8) // lineal: ya es luz, no se decodifica de sRGB
            .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE)
            .build(engine)
        t.setImage(engine, 0, Texture.PixelBufferDescriptor(px, Texture.Format.RGBA, Texture.Type.UBYTE))
        t.generateMipmaps(engine)
        return t
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    // ---------------------------------------------------------------------------- limpieza

    private fun clear() {
        for (e in listOf(headEntity, postEntity)) if (e != 0) {
            scene.remove(e); engine.destroyEntity(e); EntityManager.get().destroy(e)
        }
        headEntity = 0; postEntity = 0
        headMi?.let(engine::destroyMaterialInstance); postMi?.let(engine::destroyMaterialInstance)
        headMi = null; postMi = null
        headVb?.let(engine::destroyVertexBuffer); postVb?.let(engine::destroyVertexBuffer)
        headIb?.let(engine::destroyIndexBuffer); postIb?.let(engine::destroyIndexBuffer)
        headVb = null; postVb = null; headIb = null; postIb = null; headRing = null
        headCount = 0; shown = false; postsShown = false
    }

    fun destroy() {
        clear()
        glow?.let(engine::destroyTexture); glow = null
    }
}
