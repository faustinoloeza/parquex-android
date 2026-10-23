package com.faustinoloeza.parquex.map

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.VertexBuffer

/**
 * Línea de la ruta a pie (navegación), como `layers/navigation.js` de la web: contorno blanco y
 * trazo azul (`#5b9cff` de noche) de 2.6 m entre 6 y 14 px, **encima de todo** y sin prueba de
 * profundidad; el tramo ya caminado en gris (`#8fa8c8`), con el color por vértice.
 */
internal class NavLineLayer(
    private val engine: Engine,
    private val scene: Scene,
    private val materials: UnlitMaterials,
    private val density: Float,
) {
    companion object {
        /** Después de modelos y faroles: siempre visible. */
        const val CHANNEL = 7
        private const val STEP = 4f
        private const val LIFT = 0.1f
        private const val WORLD_WIDTH = 2.6f
        private const val MIN_DP = 6f / 2f
        private const val MAX_DP = 14f / 2f
        const val DAY = 0x1A73E8
        const val NIGHT = 0x5B9CFF
        const val WALKED = 0x8FA8C8
    }

    var dirty = false
        private set

    private var lines: ScreenLines? = null
    /** Distancia a lo largo de la ruta de cada punto remuestreado (para colorear lo caminado). */
    private var along = FloatArray(0)
    private var verts = FloatArray(0)
    private val halfCasing = FloatArray(1)
    private val halfStroke = FloatArray(1)
    private var vb: VertexBuffer? = null
    private var ib: IndexBuffer? = null
    private var ring: PositionRing? = null
    private var casing = 0
    private var stroke = 0
    private var casingMi: MaterialInstance? = null
    private var strokeMi: MaterialInstance? = null
    private var walkedIndex = -1
    private var color = DAY

    /** Ruta nueva ([points] = x0, z0, x1, z1, …) o null para quitarla; [surfaceAt] = altura de lo que se pisa. */
    fun setRoute(points: FloatArray?, surfaceAt: (Float, Float) -> Float) {
        clear()
        if (points == null || points.size < 4) { dirty = true; return }
        val sl = ScreenLines(listOf(points), intArrayOf(0), STEP, LIFT, { _, x, z -> surfaceAt(x, z) })
        if (sl.count < 2) return
        lines = sl
        along = sl.along
        verts = FloatArray(sl.count * 4 * 3)
        val n = sl.count * 4
        val indices = ArrayList<Int>()
        sl.appendIndices(indices, base = 0)
        val casingCount = indices.size
        sl.appendIndices(indices, base = sl.count * 2)
        val vertexBuffer = UnlitMaterials.vertexBuffer(engine, n)
        val indexBuffer = ScreenLines.indexBuffer(engine, indices, n)
        vb = vertexBuffer; ib = indexBuffer; ring = PositionRing(n)
        val cMi = materials.color(0xFFFFFF, "nav-casing")
        val sMi = materials.vertexColored(0xFFFFFF, "nav-stroke")
        casingMi = cMi; strokeMi = sMi
        casing = renderable(vertexBuffer, indexBuffer, 0, casingCount, cMi, priority = 0)
        stroke = renderable(vertexBuffer, indexBuffer, casingCount, indices.size - casingCount, sMi, priority = 1)
        walkedIndex = -1
        setWalked(0f)
        dirty = true
    }

    /** Color del trazo (de día o de noche). */
    fun setNight(night: Boolean) {
        val c = if (night) NIGHT else DAY
        if (c == color) return
        color = c
        val w = walkedIndex; walkedIndex = -1
        setWalkedIndex(w.coerceAtLeast(0))
    }

    /** Metros ya caminados desde el inicio de la ruta: ese tramo se pinta en gris. */
    fun setWalked(meters: Float) {
        var i = 0
        while (i < along.size && along[i] <= meters) i++
        setWalkedIndex(i)
    }

    private fun setWalkedIndex(i: Int) {
        val sl = lines ?: return
        val v = vb ?: return
        if (i == walkedIndex) return
        walkedIndex = i
        val n = sl.count
        val colors = IntArray(n * 4)
        for (p in 0 until n) {
            val c = if (p < i) WALKED else color
            // [contorno: 2 por punto][trazo: 2 por punto]; el contorno usa el color uniforme.
            colors[p * 2] = 0xFFFFFF; colors[p * 2 + 1] = 0xFFFFFF
            colors[n * 2 + p * 2] = c; colors[n * 2 + p * 2 + 1] = c
        }
        v.setBufferAt(engine, 1, UnlitMaterials.colorAttributes(colors))
        dirty = true
    }

    fun update(snap: CameraSnapshot) {
        dirty = false
        val sl = lines ?: return
        val v = vb ?: return
        val stroke = (WORLD_WIDTH * snap.focalPx / snap.distance).coerceIn(MIN_DP * 2 * density, MAX_DP * 2 * density)
        halfStroke[0] = stroke / 2f
        halfCasing[0] = stroke / 2f + 1.5f * density
        sl.project(snap)
        val o = sl.writeRibbon(verts, 0, halfCasing)
        sl.writeRibbon(verts, o, halfStroke)
        ring?.upload(engine, v, verts)
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
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            .build(engine, entity)
        scene.addEntity(entity)
        return entity
    }

    private fun clear() {
        for (e in intArrayOf(casing, stroke)) if (e != 0) {
            scene.remove(e); engine.destroyEntity(e); EntityManager.get().destroy(e)
        }
        casing = 0; stroke = 0
        casingMi?.let(engine::destroyMaterialInstance); strokeMi?.let(engine::destroyMaterialInstance)
        casingMi = null; strokeMi = null
        vb?.let(engine::destroyVertexBuffer); ib?.let(engine::destroyIndexBuffer)
        vb = null; ib = null; ring = null; lines = null
        along = FloatArray(0)
    }

    fun destroy() = clear()
}
