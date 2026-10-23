package com.faustinoloeza.parquex.map

import com.faustinoloeza.parquex.data.ParkData
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.VertexBuffer

/**
 * Rutas oficiales (`park.json → routes`) como líneas de **ancho constante en pantalla** con
 * contorno, dibujadas por la app (no las del GLB), igual que `src/layers/routes.js` de la web.
 * La geometría la calcula [ScreenLines] en CPU solo cuando la cámara cambia (~1000 puntos en Xcaret).
 *
 * Sin prueba de profundidad y en su propio canal ([CHANNEL]): van encima del suelo, de los árboles
 * y de los andadores, y debajo de los edificios (que se dibujan después). Un renderable para el
 * contorno y otro para el trazo de cada ruta: todos los contornos van antes que todos los trazos y
 * las rutas activas encima de las demás (más gruesas).
 */
internal class RouteLayer(
    private val engine: Engine,
    private val scene: Scene,
    private val materials: UnlitMaterials,
    private val density: Float,
) {
    companion object {
        const val CHANNEL = 4

        /** Remuestreo (m), ver [ScreenLines]. */
        private const val STEP = 4f
        /** Altura sobre el mapa (y = 0). */
        private const val LIFT = 0.05f
        /** Ancho "pintado en el andador" (m), acotado en pantalla a [MIN_DP]…[MAX_DP]. */
        private const val WORLD_WIDTH = 1.5f
        private const val MIN_DP = 3.5f
        private const val MAX_DP = 9f

        private const val PRIORITY_CASING = 1
        private const val PRIORITY_STROKE = 2
        private const val PRIORITY_ACTIVE_CASING = 3
        private const val PRIORITY_ACTIVE_STROKE = 4
    }

    private class Item(
        val slug: String,
        val casing: Int,
        val stroke: Int,
        val casingMi: MaterialInstance,
        val strokeMi: MaterialInstance,
    )

    var dirty = false
        private set

    private val items = ArrayList<Item>()
    private var active: Set<String> = emptySet()
    /** Atenuadas mientras hay una ruta a pie (más delgadas). */
    private var dimmed = false

    fun setDimmed(on: Boolean) {
        if (on == dimmed) return
        dimmed = on
        dirty = true
    }

    private var lines: ScreenLines? = null
    private var verts = FloatArray(0)
    private var halfCasing = FloatArray(0)
    private var halfStroke = FloatArray(0)
    private var vb: VertexBuffer? = null
    private var ib: IndexBuffer? = null
    private var ring: PositionRing? = null

    /** Reemplaza las rutas (al cambiar de parque). */
    fun setRoutes(routes: List<ParkData.Route>) {
        clear()
        val polylines = ArrayList<FloatArray>(); val owners = ArrayList<Int>()
        routes.forEachIndexed { r, route -> route.lines.forEach { polylines += it; owners += r } }
        val sl = ScreenLines(polylines, owners.toIntArray(), STEP, LIFT)
        if (sl.count == 0) return
        lines = sl
        verts = FloatArray(sl.count * 4 * 3)
        halfCasing = FloatArray(routes.size); halfStroke = FloatArray(routes.size)

        // Vértices: [contornos: 2 por punto][trazos: 2 por punto]. Índices agrupados por ruta.
        val vertexCount = sl.count * 4
        val indices = ArrayList<Int>()
        val ranges = Array(routes.size) { IntArray(4) } // casingOffset, casingCount, strokeOffset, strokeCount
        for (r in routes.indices) {
            for (pass in 0..1) {
                val first = indices.size
                sl.appendIndices(indices, base = pass * sl.count * 2, owner = r)
                ranges[r][pass * 2] = first
                ranges[r][pass * 2 + 1] = indices.size - first
            }
        }

        val vertexBuffer = UnlitMaterials.vertexBuffer(engine, vertexCount)
        vertexBuffer.setBufferAt(engine, 1, UnlitMaterials.staticAttributes(vertexCount, null))
        val indexBuffer = ScreenLines.indexBuffer(engine, indices, vertexCount)
        vb = vertexBuffer; ib = indexBuffer
        ring = PositionRing(vertexCount)

        routes.forEachIndexed { r, route ->
            val casingMi = materials.color(route.casing and 0xFFFFFF, "route-casing:${route.slug}")
            val strokeMi = materials.color(route.color and 0xFFFFFF, "route:${route.slug}")
            val casing = renderable(vertexBuffer, indexBuffer, ranges[r][0], ranges[r][1], casingMi, PRIORITY_CASING)
            val stroke = renderable(vertexBuffer, indexBuffer, ranges[r][2], ranges[r][3], strokeMi, PRIORITY_STROKE)
            items += Item(route.slug, casing, stroke, casingMi, strokeMi)
        }
        applyPriorities()
        dirty = true
    }

    /** Rutas resaltadas (más gruesas y encima del resto). */
    fun setActive(slugs: Set<String>) {
        if (slugs == active) return
        active = slugs
        applyPriorities()
        dirty = true
    }

    private fun applyPriorities() {
        val rcm = engine.renderableManager
        for (item in items) {
            val on = item.slug in active
            rcm.setPriority(rcm.getInstance(item.casing), if (on) PRIORITY_ACTIVE_CASING else PRIORITY_CASING)
            rcm.setPriority(rcm.getInstance(item.stroke), if (on) PRIORITY_ACTIVE_STROKE else PRIORITY_STROKE)
        }
    }

    private fun renderable(
        vertexBuffer: VertexBuffer, indexBuffer: IndexBuffer, offset: Int, count: Int, mi: MaterialInstance, priority: Int,
    ): Int {
        val entity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(Box(0f, 0f, 0f, 1f, 1f, 1f))
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertexBuffer, indexBuffer, offset, count)
            .material(0, mi)
            .channel(CHANNEL)
            .priority(priority)
            .culling(false) // las posiciones cambian con la cámara
            .castShadows(false)
            .receiveShadows(false)
            .build(engine, entity)
        scene.addEntity(entity)
        return entity
    }

    // ---------------------------------------------------------------------------- por cuadro

    /** Recalcula las cintas para la vista [snap]. Hilo principal. */
    fun update(snap: CameraSnapshot) {
        dirty = false
        val sl = lines ?: return
        val vertexBuffer = vb ?: return

        // Anchos en píxeles (como la web: el trazo sigue al zoom dentro de un rango).
        val base = (WORLD_WIDTH * snap.focalPx / snap.distance).coerceIn(MIN_DP * density, MAX_DP * density) *
            if (dimmed) 0.55f else 1f
        val anyActive = active.isNotEmpty()
        items.forEachIndexed { r, item ->
            val stroke = when {
                item.slug in active -> base * 1.45f
                anyActive -> base * 0.75f
                else -> base
            }
            halfStroke[r] = stroke / 2f
            halfCasing[r] = (stroke + (stroke * 0.4f).coerceIn(2f * density, 4f * density)) / 2f
        }

        sl.project(snap)
        val o = sl.writeRibbon(verts, 0, halfCasing)
        sl.writeRibbon(verts, o, halfStroke)
        ring?.upload(engine, vertexBuffer, verts)
    }

    // ---------------------------------------------------------------------------- limpieza

    private fun clear() {
        for (item in items) {
            for (e in intArrayOf(item.casing, item.stroke)) {
                scene.remove(e)
                engine.destroyEntity(e)
                EntityManager.get().destroy(e)
            }
            engine.destroyMaterialInstance(item.casingMi)
            engine.destroyMaterialInstance(item.strokeMi)
        }
        items.clear()
        vb?.let { engine.destroyVertexBuffer(it) }
        ib?.let { engine.destroyIndexBuffer(it) }
        vb = null; ib = null; ring = null; lines = null
    }

    fun destroy() = clear()
}
