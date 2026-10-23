package com.faustinoloeza.parquex.map

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
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Andadores (`park.json → walkways`, vías peatonales de OSM) como líneas punteadas delgadas y
 * neutras, igual que `src/layers/walkways.js` de la web: contorno café translúcido y guiones
 * crema encima. Sirven para ver por dónde caminar, sobre todo en Xel-Há, que no tiene rutas oficiales.
 *
 * - Se omiten los tramos que van a ≤ [ROUTE_CLEARANCE] m de una ruta oficial (ya se ve la ruta).
 * - Canal [CHANNEL]: encima del suelo y de los árboles, debajo de las rutas y de los edificios.
 *   Sin prueba de profundidad; ambas pasadas translúcidas, ordenadas por prioridad.
 * - Los tramos que cruzan un hueco del suelo (`park.json → groundHoles`, el foso de los felinos) van
 *   sobre la cubierta del puente, en el canal de los modelos ([DECK_CHANNEL]) y con prueba de
 *   profundidad: la cubierta ya no los tapa, las barandas y lo que quede delante sí.
 * - Guiones con una textura repetida a lo largo de la línea, con periodo en metros redondeado a una
 *   potencia de dos según el zoom: los guiones no "caminan" al acercar, solo cambian de escala al
 *   cruzar cada potencia.
 */
internal class WalkwayLayer(
    private val engine: Engine,
    private val scene: Scene,
    private val materials: UnlitMaterials,
    private val density: Float,
) {
    companion object {
        const val CHANNEL = 3
        /** Tramos sobre un puente: después de lo opaco de los modelos (son translúcidos). */
        private const val DECK_CHANNEL = ParkRenderer.CHANNEL_MODELS

        private const val STEP = 4f
        private const val ROUTE_CLEARANCE = 4f
        private const val LIFT = 0.05f
        /** Sobre la cubierta, como la web (altura + 0.5 m): el puente cuelga y su `y` es la altura media. */
        private const val DECK_LIFT = 0.5f
        /** Ancho del guion en el mundo (m), acotado en pantalla a [MIN_DP]…[MAX_DP]. */
        private const val DASH_WORLD = 0.9f
        private const val MIN_DP = 1.25f
        private const val MAX_DP = 3.5f
        /** El contorno es el guion más esto (dp, en total). */
        private const val CASING_EXTRA_DP = 1f
        /** Periodo guion + hueco en pantalla, aproximado (dp). */
        private const val PERIOD_DP = 8f

        private const val CASING_COLOR = 0x4A3B2C
        private const val CASING_ALPHA = 0.5f
        private const val DASH_COLOR = 0xFFFAF0
        private const val PRIORITY_CASING = 0
        private const val PRIORITY_DASH = 1

        /** Texeles del patrón y cuántos son guion (10/16 ≈ 60 % guion, 40 % hueco). */
        private const val PATTERN = 16
        private const val PATTERN_ON = 10
    }

    var dirty = false
        private set
    var visible = true
        private set

    /** Andadores sobre el suelo y, si hay, los que van sobre un puente. */
    private val sets = ArrayList<Strokes>()
    private val halfCasing = FloatArray(1)
    private val halfDash = FloatArray(1)

    /** Patrón de guiones: blanco opaco / transparente, alfa premultiplicado. */
    private val pattern: Texture = Texture.Builder().width(PATTERN).height(1).levels(5)
        .sampler(Texture.Sampler.SAMPLER_2D)
        .format(Texture.InternalFormat.RGBA8)
        .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE)
        .build(engine).also { t ->
            val px = UnlitMaterials.directBuffer(PATTERN * 4)
            for (i in 0 until PATTERN) repeat(4) { px.put(if (i < PATTERN_ON) (-1).toByte() else 0.toByte()) }
            px.flip()
            t.setImage(engine, 0, Texture.PixelBufferDescriptor(px, Texture.Format.RGBA, Texture.Type.UBYTE))
            t.generateMipmaps(engine)
        }
    private val sampler = TextureSampler(
        TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.REPEAT, TextureSampler.WrapMode.CLAMP_TO_EDGE, TextureSampler.WrapMode.CLAMP_TO_EDGE,
    )

    /** Reemplaza los andadores (al cambiar de parque). */
    fun setWalkways(park: ParkData?) {
        clear()
        if (park == null) return
        val ground = ArrayList<FloatArray>()
        val decks = ArrayList<FloatArray>()
        val deckY = ArrayList<Float>()
        for (line in awayFromRoutes(park.walkways, park.routes)) splitOnDecks(line, park, ground, decks, deckY)
        val onGround = ScreenLines(ground, IntArray(ground.size), STEP, LIFT)
        if (onGround.count > 0) sets += Strokes(onGround, CHANNEL, depthTest = false)
        val onDecks = ScreenLines(decks, IntArray(decks.size), STEP, DECK_LIFT, { l, _, _ -> deckY[l] }, flat = true)
        if (onDecks.count > 0) sets += Strokes(onDecks, DECK_CHANNEL, depthTest = true)
        dirty = true
    }

    fun setVisible(on: Boolean) {
        if (on == visible) return
        visible = on
        for (set in sets) for (e in set.entities) if (on) scene.addEntity(e) else scene.remove(e)
        dirty = true
    }

    /** Recalcula las cintas y, si cambió la escala, las UV de los guiones. Hilo principal. */
    fun update(snap: CameraSnapshot) {
        dirty = false
        if (!visible || sets.isEmpty()) return

        val pxPerMeter = snap.focalPx / snap.distance
        val dashPx = (DASH_WORLD * pxPerMeter).coerceIn(MIN_DP * density, MAX_DP * density)
        halfDash[0] = dashPx / 2f
        halfCasing[0] = (dashPx + CASING_EXTRA_DP * density) / 2f
        val period = 2f.pow((ln(PERIOD_DP * density / pxPerMeter) / ln(2f)).roundToInt())
        for (set in sets) set.update(snap, period)
    }

    /**
     * Contorno y guiones de unas polilíneas en un canal (dos renderables sobre un búfer de vértices
     * que se recalcula al mover la cámara).
     */
    private inner class Strokes(private val lines: ScreenLines, channel: Int, depthTest: Boolean) {
        private val verts = FloatArray(lines.count * 4 * 3)
        // Vértices: [contorno: 2 por punto][guiones: 2 por punto].
        private val vb = UnlitMaterials.vertexBuffer(engine, lines.count * 4)
        private val ib: IndexBuffer
        private val ring = PositionRing(lines.count * 4)
        private val casingMi = materials.blendedColor(CASING_COLOR, CASING_ALPHA, "walkway-casing")
        private val dashMi = materials.texturedBlend(DASH_COLOR, "walkway-dash")
        val entities: IntArray
        /** Periodo actual del patrón (m) escrito en las UV; 0 = aún no. */
        private var period = 0f

        init {
            val indices = ArrayList<Int>()
            lines.appendIndices(indices, base = 0)
            val casingCount = indices.size
            lines.appendIndices(indices, base = lines.count * 2)
            ib = ScreenLines.indexBuffer(engine, indices, lines.count * 4)
            dashMi.setParameter("baseColorMap", pattern, sampler)
            casingMi.setDepthCulling(depthTest)
            dashMi.setDepthCulling(depthTest)
            entities = intArrayOf(
                renderable(0, casingCount, casingMi, PRIORITY_CASING, channel),
                renderable(casingCount, indices.size - casingCount, dashMi, PRIORITY_DASH, channel),
            )
        }

        fun update(snap: CameraSnapshot, p: Float) {
            if (p != period) {
                period = p
                val n = lines.count
                val uvs = FloatArray(n * 4 * 2)
                for (pass in 0..1) for (i in 0 until n) {
                    val u = lines.along[i] / p
                    val k = (pass * n * 2 + i * 2) * 2
                    uvs[k] = u; uvs[k + 1] = 0.5f; uvs[k + 2] = u; uvs[k + 3] = 0.5f
                }
                vb.setBufferAt(engine, 1, UnlitMaterials.staticAttributes(n * 4, uvs))
            }
            lines.project(snap)
            val o = lines.writeRibbon(verts, 0, halfCasing)
            lines.writeRibbon(verts, o, halfDash)
            ring.upload(engine, vb, verts)
        }

        private fun renderable(offset: Int, count: Int, mi: MaterialInstance, priority: Int, channel: Int): Int {
            val entity = EntityManager.get().create()
            RenderableManager.Builder(1)
                .boundingBox(Box(0f, 0f, 0f, 1f, 1f, 1f))
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib, offset, count)
                .material(0, mi)
                .channel(channel)
                .priority(priority)
                .culling(false) // las posiciones cambian con la cámara
                .castShadows(false)
                .receiveShadows(false)
                .build(engine, entity)
            if (visible) scene.addEntity(entity)
            return entity
        }

        fun destroy() {
            for (e in entities) {
                scene.remove(e)
                engine.destroyEntity(e)
                EntityManager.get().destroy(e)
            }
            engine.destroyMaterialInstance(casingMi)
            engine.destroyMaterialInstance(dashMi)
            engine.destroyVertexBuffer(vb)
            engine.destroyIndexBuffer(ib)
        }
    }

    /**
     * Parte [line] (ya remuestreada) en tramos sobre el suelo y tramos sobre un puente: va al puente
     * todo tramo con algún extremo dentro de un hueco, entero a la altura de esa cubierta (llega a las
     * plataformas de los extremos en vez de subir en rampa por debajo de ella).
     */
    private fun splitOnDecks(
        line: FloatArray, park: ParkData,
        ground: MutableList<FloatArray>, decks: MutableList<FloatArray>, deckY: MutableList<Float>,
    ) {
        if (park.groundHoles.isEmpty()) { ground += line; return }
        val n = line.size / 2
        val y = FloatArray(n) { park.deckAt(line[it * 2], line[it * 2 + 1]) ?: Float.NaN }
        fun onDeck(k: Int) = !y[k].isNaN() || !y[k + 1].isNaN()
        var s = 0
        for (k in 0 until n - 1) {
            if (k < n - 2 && onDeck(k + 1) == onDeck(k)) continue
            val run = line.copyOfRange(s * 2, (k + 2) * 2)
            if (onDeck(k)) { decks += run; deckY += (s..k + 1).map { y[it] }.first { !it.isNaN() } } else ground += run
            s = k + 1
        }
    }

    /**
     * Tramos de ≤ [STEP] m cuyo punto medio queda a más de [ROUTE_CLEARANCE] m de toda ruta oficial,
     * unidos en polilíneas.
     */
    private fun awayFromRoutes(walkways: List<FloatArray>, routes: List<ParkData.Route>): List<FloatArray> {
        val segs = ArrayList<Float>() // ax, az, bx, bz
        for (route in routes) for (line in route.lines) for (i in 2 until line.size step 2) {
            segs += line[i - 2]; segs += line[i - 1]; segs += line[i]; segs += line[i + 1]
        }
        val rs = segs.toFloatArray()
        fun nearRoute(x: Float, z: Float): Boolean {
            for (k in rs.indices step 4) {
                if (distanceToSegment(x, z, rs[k], rs[k + 1], rs[k + 2], rs[k + 3]) <= ROUTE_CLEARANCE) return true
            }
            return false
        }

        val out = ArrayList<FloatArray>()
        for (line in walkways) {
            val pts = ArrayList<Float>()
            ScreenLines.resample(line, STEP) { x, z -> pts += x; pts += z }
            var run = ArrayList<Float>()
            for (i in 2 until pts.size step 2) {
                val ax = pts[i - 2]; val az = pts[i - 1]; val bx = pts[i]; val bz = pts[i + 1]
                if (rs.isEmpty() || !nearRoute((ax + bx) / 2f, (az + bz) / 2f)) {
                    if (run.isEmpty()) { run += ax; run += az }
                    run += bx; run += bz
                } else if (run.isNotEmpty()) {
                    out += run.toFloatArray(); run = ArrayList()
                }
            }
            if (run.size >= 4) out += run.toFloatArray()
        }
        return out
    }

    private fun distanceToSegment(px: Float, pz: Float, ax: Float, az: Float, bx: Float, bz: Float): Float {
        val dx = bx - ax; val dz = bz - az
        val len2 = dx * dx + dz * dz
        val t = if (len2 > 0f) (((px - ax) * dx + (pz - az) * dz) / len2).coerceIn(0f, 1f) else 0f
        return hypot(px - (ax + dx * t), pz - (az + dz * t))
    }

    // ---------------------------------------------------------------------------- limpieza

    private fun clear() {
        for (set in sets) set.destroy()
        sets.clear()
    }

    fun destroy() {
        clear()
        engine.destroyTexture(pattern)
    }
}
