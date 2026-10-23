package com.faustinoloeza.parquex.map

import android.util.Log
import com.faustinoloeza.parquex.data.ParkAnimations
import com.faustinoloeza.parquex.data.ParkNight
import com.faustinoloeza.parquex.map.AnimationDrivers.MATRIX
import com.faustinoloeza.parquex.map.PlainMeshes.Primitive
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.SurfaceOrientation
import com.google.android.filament.VertexBuffer
import com.google.android.filament.VertexBuffer.AttributeType
import com.google.android.filament.VertexBuffer.VertexAttribute
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Piezas animadas de `animated.json`: en Xcaret la cabina y la bandera de la Torre Escénica, los voladores, la
 * bandera monumental, las mariposas y aves de los domos y las bandadas.
 *
 * Cada pieza es un renderable con una primitiva por material, armado con sus nodos `anim:*` del GLB
 * ([PlainMeshes]) y los MaterialInstance de gltfio; las copias (aves, mariposas) van seguidas en el mismo
 * búfer, así de noche se dibujan solo las primeras. En cada cuadro de animación [AnimationDrivers] da la matriz
 * de cada parte (más la ola de la tela o el aleteo) y aquí se transforman en la CPU los vértices de las piezas
 * que se ven (~3.6 k en Xcaret): posiciones y tangentes se suben con búferes rotativos; color y UV no cambian.
 * Las cuerdas de los voladores (líneas en la web) son dos planos cruzados con al menos [ROPE_MIN_PX] de ancho.
 */
internal class AnimatedModels(
    private val engine: Engine,
    private val scene: Scene,
    private val animations: ParkAnimations,
    meshes: Map<String, List<Primitive>>,
) {
    companion object {
        private const val TAG = "AnimatedModels"
        const val PREFIX = "anim:"
        /** Píxeles que debe cubrir la figura más chica de una pieza para que valga la pena animarla. */
        private const val MIN_PX = 2f
        private const val ROPE_HALF_WIDTH = 0.03f
        private const val ROPE_MIN_PX = 1.2f
        /** Por cuerda: 2 planos cruzados × 2 caras × 4 vértices; 2 triángulos por cara. */
        private const val ROPE_VERTICES = 16
        private const val ROPE_INDICES = 24
        private const val ROPES = 4
        /** color (4 × u8) + uv (2 × f32) */
        private const val STATIC_STRIDE = 12
        /** Paso máximo del reloj de vuelo (s), como la web. */
        private const val MAX_STEP = 0.1
        private const val RING = 4
    }

    /** Una copia de una primitiva dentro del búfer de su grupo, desde el vértice [first]. */
    private class Slot(val prim: Primitive, val instance: Int, val copy: Int, val first: Int)

    /** Primitiva del renderable: las partes de un mismo material (y las cuerdas, si las hay, al final). */
    private inner class Group(val mi: MaterialInstance, val slots: List<Slot>, val ropes: Int, tint: (Int) -> FloatArray?, ropeColor: FloatArray?) {
        val ropeStart = slots.sumOf { it.prim.vertexCount }
        val vertexCount = ropeStart + ropes * ROPE_VERTICES
        val positions = FloatArray(vertexCount * 3)
        val normals = FloatArray(vertexCount * 3)
        private val normalBuffer: FloatBuffer = UnlitMaterials.directBuffer(vertexCount * 12).asFloatBuffer()
        private val positionRing = PositionRing(vertexCount)
        private val quatRing = Array(RING) { UnlitMaterials.directBuffer(vertexCount * 8) }
        private var nextQuat = 0
        val indexCount: Int
        /** Índices hasta el final de cada copia: de noche se dibujan solo las primeras. */
        val indicesThroughCopy: IntArray
        val vb: VertexBuffer
        val ib: IndexBuffer

        init {
            val copies = (slots.maxOfOrNull { it.copy } ?: 0) + 1
            indicesThroughCopy = IntArray(copies)
            val static = UnlitMaterials.directBuffer(vertexCount * STATIC_STRIDE)
            val indices = ArrayList<Int>()
            for (slot in slots) {
                val p = slot.prim
                val c = tint(slot.copy)
                for (v in 0 until p.vertexCount) {
                    for (k in 0 until 3) static.put(unorm(p.colors[v * 4 + k] * (c?.get(k) ?: 1f)))
                    static.put(unorm(p.colors[v * 4 + 3]))
                    static.putFloat(p.uvs?.get(v * 2) ?: 0f).putFloat(p.uvs?.get(v * 2 + 1) ?: 0f)
                }
                for (i in p.indices) indices += slot.first + i
                indicesThroughCopy[slot.copy] = indices.size
            }
            repeat(ropes * ROPE_VERTICES) {
                for (k in 0 until 3) static.put(unorm(ropeColor?.get(k) ?: 1f))
                static.put(unorm(1f)).putFloat(0f).putFloat(0f)
            }
            for (r in 0 until ropes) for (face in 0 until 4) {
                val b = ropeStart + r * ROPE_VERTICES + face * 4
                // caras impares: las mismas esquinas con el giro contrario (la otra cara del plano)
                val order = if (face % 2 == 0) intArrayOf(0, 1, 2, 0, 2, 3) else intArrayOf(0, 2, 1, 0, 3, 2)
                for (i in order) indices += b + i
            }
            indexCount = indices.size
            if (ropes > 0) indicesThroughCopy.fill(indexCount)
            static.flip()

            vb = VertexBuffer.Builder()
                .bufferCount(3)
                .vertexCount(vertexCount)
                .attribute(VertexAttribute.POSITION, 0, AttributeType.FLOAT3, 0, 12)
                .attribute(VertexAttribute.COLOR, 1, AttributeType.UBYTE4, 0, STATIC_STRIDE)
                .normalized(VertexAttribute.COLOR)
                .attribute(VertexAttribute.UV0, 1, AttributeType.FLOAT2, 4, STATIC_STRIDE)
                .attribute(VertexAttribute.UV1, 1, AttributeType.FLOAT2, 4, STATIC_STRIDE)
                .attribute(VertexAttribute.TANGENTS, 2, AttributeType.SHORT4, 0, 8)
                .normalized(VertexAttribute.TANGENTS)
                .build(engine)
            vb.setBufferAt(engine, 1, static)
            val wide = vertexCount > 65_535
            val idx = UnlitMaterials.directBuffer(indexCount * if (wide) 4 else 2)
            for (i in indices) if (wide) idx.putInt(i) else idx.putShort(i.toShort())
            idx.flip()
            ib = IndexBuffer.Builder().indexCount(indexCount)
                .bufferType(if (wide) IndexBuffer.Builder.IndexType.UINT else IndexBuffer.Builder.IndexType.USHORT)
                .build(engine)
            ib.setBuffer(engine, idx)
        }

        /** Sube posiciones y tangentes (de las normales) al búfer siguiente de cada anillo. */
        fun upload() {
            positionRing.upload(engine, vb, positions)
            normalBuffer.clear(); normalBuffer.put(normals); normalBuffer.flip()
            val orientation = SurfaceOrientation.Builder().vertexCount(vertexCount).normals(normalBuffer).build()
            val quats = quatRing[nextQuat++ % RING]
            quats.clear()
            orientation.getQuatsAsShort(quats.asShortBuffer())
            orientation.destroy()
            vb.setBufferAt(engine, 2, quats)
        }

        fun destroy() {
            engine.destroyVertexBuffer(vb)
            engine.destroyIndexBuffer(ib)
        }
    }

    /** Una pieza en escena: sus grupos, matrices por instancia (copia · partes + parte) y estado. */
    private inner class Animated(val piece: ParkAnimations.Piece, parts: List<List<Primitive>>) {
        val driver = piece.driver
        val partCount = parts.size
        val local = FloatArray(piece.copies * partCount * MATRIX)
        val world = FloatArray(local.size)
        val ropes = if (driver is ParkAnimations.Voladores) FloatArray(ROPES * 6) else null
        val groups: List<Group>
        val entity: Int = EntityManager.get().create()
        val cx = (piece.box[0] + piece.box[3]) / 2
        val cy = (piece.box[1] + piece.box[4]) / 2
        val cz = (piece.box[2] + piece.box[5]) / 2
        var visible = false
        var awake = piece.copies

        init {
            // Por material, en orden de instancia: las copias quedan seguidas (las despiertas, primero).
            val byMaterial = LinkedHashMap<Int, MutableList<Pair<Primitive, Int>>>()
            for (copy in 0 until piece.copies) for ((part, prims) in parts.withIndex()) for (p in prims)
                byMaterial.getOrPut(p.material) { ArrayList() } += p to copy * partCount + part
            val tints = (driver as? ParkAnimations.NetDome)?.flies?.map { ParkNight.linear(it.color) }
            val ropeColor = (driver as? ParkAnimations.Voladores)?.let { ParkNight.linear(it.ropeColor) }
            val ropeMaterial = parts.firstOrNull()?.firstOrNull()?.material
            groups = byMaterial.map { (material, list) ->
                var first = 0
                val slots = list.map { (p, instance) -> Slot(p, instance, instance / partCount, first).also { first += p.vertexCount } }
                Group(list[0].first.materialInstance, slots, if (ropes != null && material == ropeMaterial) ROPES else 0,
                    { copy -> tints?.get(copy) }, ropeColor)
            }
            val b = piece.box
            val builder = RenderableManager.Builder(groups.size)
                .boundingBox(Box(cx, cy, cz, (b[3] - b[0]) / 2, (b[4] - b[1]) / 2, (b[5] - b[2]) / 2))
                .channel(ParkRenderer.CHANNEL_MODELS)
                .castShadows(true)
                .receiveShadows(true)
            groups.forEachIndexed { i, g ->
                builder.geometry(i, RenderableManager.PrimitiveType.TRIANGLES, g.vb, g.ib, 0, g.indexCount).material(i, g.mi)
            }
            builder.build(engine, entity)
        }
    }

    private val pieces: List<Animated>
    private var shown = false
    private var startNanos = 0L
    private var lastNanos = 0L
    /** Reloj del vuelo de aves y mariposas: más lento de noche (`pace` de la web). */
    private var pace = 0.0
    private var statFrames = 0
    private var statNanos = 0L

    val renderableCount get() = pieces.size

    init {
        pieces = animations.pieces.mapNotNull { piece ->
            val parts = piece.parts.map { meshes[it] }
            if (parts.any { it.isNullOrEmpty() }) {
                Log.w(TAG, "${piece.id}: faltan nodos $PREFIX${piece.parts.filterIndexed { i, _ -> parts[i].isNullOrEmpty() }}")
                null
            } else Animated(piece, parts.map { it!! })
        }
        for (a in pieces) pose(a, 0.0, 0f, null)
        Log.i(TAG, "Animaciones: ${pieces.size} piezas, ${pieces.sumOf { a -> a.groups.size }} primitivas, " +
            "${pieces.sumOf { a -> a.groups.sumOf { it.vertexCount } }} vértices")
    }

    /** ¿Se ve alguna pieza (en el frustum y de al menos [MIN_PX])? Marca cuáles para [animate]. */
    fun visible(snap: CameraSnapshot): Boolean {
        var any = false
        for (a in pieces) {
            val b = a.piece.box
            a.visible = snap.boxVisible(b[0], b[3], b[1], b[4], b[2], b[5]) &&
                a.piece.size * snap.focalPx / max(snap.depth(a.cx, a.cy, a.cz), snap.near) >= MIN_PX
            any = any || a.visible
        }
        return any
    }

    /** Pone en la pose del instante [frameTimeNanos] las piezas que se ven (ver [visible]); [nightK] 0 día … 1 noche. */
    fun animate(frameTimeNanos: Long, nightK: Float, snap: CameraSnapshot) {
        val started = System.nanoTime()
        if (startNanos == 0L) startNanos = frameTimeNanos
        val dt = if (lastNanos == 0L) 0.0 else ((frameTimeNanos - lastNanos) / 1e9).coerceIn(0.0, MAX_STEP)
        lastNanos = frameTimeNanos
        pace += dt * AnimationDrivers.activity(animations.nightPace, nightK)
        val t = (frameTimeNanos - startNanos) / 1e9
        for (a in pieces) if (a.visible) pose(a, t, nightK, snap)
        statNanos += System.nanoTime() - started
        if (++statFrames == 300) {
            Log.d(TAG, "Animaciones: ${"%.2f".format(statNanos / statFrames / 1e6)} ms por cuadro (CPU), " +
                "${pieces.count { it.visible }} de ${pieces.size} piezas a la vista")
            statFrames = 0; statNanos = 0L
        }
    }

    private fun pose(a: Animated, t: Double, nightK: Float, snap: CameraSnapshot?) {
        val m = a.local
        val flap = when (val d = a.driver) {
            is ParkAnimations.TowerCabin -> { AnimationDrivers.towerCabin(t, d.height, m); null }
            is ParkAnimations.Flag -> { AnimationDrivers.flag(t, d.at, m); null }
            is ParkAnimations.Voladores -> { AnimationDrivers.voladores(t, d.height, m, a.ropes!!); null }
            is ParkAnimations.NetDome -> {
                for (i in d.flies.indices) AnimationDrivers.netDomeFly(pace, d.flies[i], d.rx, d.rz, d.scale, m, i * MATRIX)
                d.flap
            }
            is ParkAnimations.Flock -> {
                for (i in d.birds.indices) AnimationDrivers.flockBird(pace, d.birds[i], d.x, d.z, d.scale, m, i * MATRIX)
                d.flap
            }
        }
        toWorld(a)
        val cloth = a.driver as? ParkAnimations.Flag
        for (g in a.groups) {
            for (slot in g.slots) {
                val angle = flap?.let { AnimationDrivers.flapAngle(t, slot.copy, it) }
                writeSlot(g, slot, a.world, slot.instance * MATRIX, t, cloth, flap, angle)
            }
            if (g.ropes > 0) writeRopes(a, g, snap)
            g.upload()
        }
        // De noche la mayoría de las aves y mariposas descansa: se dibujan solo las primeras copias.
        if (a.piece.copies > 1) {
            val n = AnimationDrivers.awake(a.piece.copies, animations.nightBirds, nightK)
            if (n != a.awake) {
                a.awake = n
                val rcm = engine.renderableManager
                val ri = rcm.getInstance(a.entity)
                a.groups.forEachIndexed { i, g ->
                    rcm.setGeometryAt(ri, i, RenderableManager.PrimitiveType.TRIANGLES, g.vb, g.ib, 0, g.indicesThroughCopy[n - 1])
                }
            }
        }
    }

    /** Matriz de cada instancia en el parque: T(pieza) · R_y(rotY) · local. */
    private fun toWorld(a: Animated) {
        val c = cos(a.piece.rotY); val s = sin(a.piece.rotY)
        val m = a.local; val w = a.world
        var o = 0
        while (o < m.size) {
            for (col in 0 until 4) {
                val x = m[o + col * 3]; val y = m[o + col * 3 + 1]; val z = m[o + col * 3 + 2]
                w[o + col * 3] = c * x + s * z
                w[o + col * 3 + 1] = y
                w[o + col * 3 + 2] = -s * x + c * z
            }
            w[o + 9] += a.piece.x; w[o + 10] += a.piece.y; w[o + 11] += a.piece.z
            o += MATRIX
        }
    }

    /** Vértices y normales de una copia: reposo (o tela deformada / ala aleteando) × su matriz en el parque. */
    private fun writeSlot(
        g: Group, slot: Slot, w: FloatArray, wo: Int, t: Double,
        cloth: ParkAnimations.Flag?, flap: ParkAnimations.Flap?, angle: Float?,
    ) {
        val p = slot.prim
        val pos = g.positions; val nor = g.normals
        val first = slot.first
        if (cloth != null) {
            for (v in 0 until p.vertexCount) {
                AnimationDrivers.cloth(t, p.positions[v * 3], p.positions[v * 3 + 1], cloth.width, cloth.height, cloth.speed, pos, (first + v) * 3)
            }
            triangleNormals(pos, nor, p.indices, first, p.vertexCount)
        } else {
            System.arraycopy(p.positions, 0, pos, first * 3, p.vertexCount * 3)
            System.arraycopy(p.normals, 0, nor, first * 3, p.vertexCount * 3)
            if (flap != null && angle != null) for (v in 0 until p.vertexCount) {
                val o = (first + v) * 3
                val px = p.positions[v * 3]; val pz = p.positions[v * 3 + 2]
                AnimationDrivers.flap(pos, o, px, pz, angle, flap)
                AnimationDrivers.flap(nor, o, px, pz, angle, flap)
            }
        }
        for (v in first until first + p.vertexCount) {
            val o = v * 3
            val x = pos[o]; val y = pos[o + 1]; val z = pos[o + 2]
            pos[o] = w[wo] * x + w[wo + 3] * y + w[wo + 6] * z + w[wo + 9]
            pos[o + 1] = w[wo + 1] * x + w[wo + 4] * y + w[wo + 7] * z + w[wo + 10]
            pos[o + 2] = w[wo + 2] * x + w[wo + 5] * y + w[wo + 8] * z + w[wo + 11]
            val nx = nor[o]; val ny = nor[o + 1]; val nz = nor[o + 2]
            val rx = w[wo] * nx + w[wo + 3] * ny + w[wo + 6] * nz
            val ry = w[wo + 1] * nx + w[wo + 4] * ny + w[wo + 7] * nz
            val rz = w[wo + 2] * nx + w[wo + 5] * ny + w[wo + 8] * nz
            val len = sqrt(rx * rx + ry * ry + rz * rz).takeIf { it > 0f } ?: 1f
            nor[o] = rx / len; nor[o + 1] = ry / len; nor[o + 2] = rz / len
        }
    }

    /** Normales suaves por triángulos (como `computeVertexNormals` de three.js) de [count] vértices desde [first]. */
    private fun triangleNormals(pos: FloatArray, nor: FloatArray, indices: IntArray, first: Int, count: Int) {
        nor.fill(0f, first * 3, (first + count) * 3)
        var i = 0
        while (i + 2 < indices.size) {
            val a = (first + indices[i]) * 3; val b = (first + indices[i + 1]) * 3; val c = (first + indices[i + 2]) * 3
            val ux = pos[b] - pos[a]; val uy = pos[b + 1] - pos[a + 1]; val uz = pos[b + 2] - pos[a + 2]
            val vx = pos[c] - pos[a]; val vy = pos[c + 1] - pos[a + 1]; val vz = pos[c + 2] - pos[a + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            nor[a] += nx; nor[a + 1] += ny; nor[a + 2] += nz
            nor[b] += nx; nor[b + 1] += ny; nor[b + 2] += nz
            nor[c] += nx; nor[c + 1] += ny; nor[c + 2] += nz
            i += 3
        }
        for (v in first until first + count) {
            val o = v * 3
            val len = sqrt(nor[o] * nor[o] + nor[o + 1] * nor[o + 1] + nor[o + 2] * nor[o + 2])
            if (len > 0f) { nor[o] /= len; nor[o + 1] /= len; nor[o + 2] /= len } else { nor[o + 1] = 1f }
        }
    }

    /**
     * Cuerdas de los voladores, de su extremo de arriba al de abajo: dos planos cruzados (cada uno con sus dos
     * caras), con medio ancho de al menos [ROPE_HALF_WIDTH] m o [ROPE_MIN_PX]/2 px a la distancia de la pieza.
     */
    private fun writeRopes(a: Animated, g: Group, snap: CameraSnapshot?) {
        val r = a.ropes ?: return
        val half = if (snap == null) ROPE_HALF_WIDTH
            else max(ROPE_HALF_WIDTH, ROPE_MIN_PX * 0.5f * max(snap.depth(a.cx, a.cy, a.cz), snap.near) / snap.focalPx)
        val c = cos(a.piece.rotY); val s = sin(a.piece.rotY)
        val pos = g.positions; val nor = g.normals
        val end = FloatArray(6)
        for (i in 0 until ROPES) {
            // extremos en el parque
            for (e in 0 until 2) {
                val x = r[i * 6 + e * 3]; val y = r[i * 6 + e * 3 + 1]; val z = r[i * 6 + e * 3 + 2]
                end[e * 3] = a.piece.x + c * x + s * z
                end[e * 3 + 1] = a.piece.y + y
                end[e * 3 + 2] = a.piece.z - s * x + c * z
            }
            var dx = end[3] - end[0]; var dy = end[4] - end[1]; var dz = end[5] - end[2]
            val dl = sqrt(dx * dx + dy * dy + dz * dz).takeIf { it > 0f } ?: 1f
            dx /= dl; dy /= dl; dz /= dl
            // u ⟂ d (con el eje y, o con x si la cuerda va casi vertical) y w = d × u
            var ux: Float; var uy: Float; var uz: Float
            if (abs(dy) < 0.99f) { ux = dz; uy = 0f; uz = -dx } else { ux = 0f; uy = -dz; uz = dy }
            val ul = sqrt(ux * ux + uy * uy + uz * uz)
            ux /= ul; uy /= ul; uz /= ul
            val wx = dy * uz - dz * uy; val wy = dz * ux - dx * uz; val wz = dx * uy - dy * ux
            for (plane in 0 until 2) {
                val sx = (if (plane == 0) ux else wx) * half
                val sy = (if (plane == 0) uy else wy) * half
                val sz = (if (plane == 0) uz else wz) * half
                // normal de la cara par con el giro 0-1-2: (2·s) × (B − A + 2·s) ∥ s × d
                var nx = sy * dz - sz * dy; var ny = sz * dx - sx * dz; var nz = sx * dy - sy * dx
                val nl = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 0f } ?: 1f
                nx /= nl; ny /= nl; nz /= nl
                for (side in 0 until 2) {
                    val base = g.ropeStart + i * ROPE_VERTICES + (plane * 2 + side) * 4
                    val sign = if (side == 0) 1f else -1f
                    for (corner in 0 until 4) {
                        val e = if (corner < 2) 0 else 3 // 0, 1: arriba; 2, 3: abajo
                        val k = if (corner == 0 || corner == 3) -1f else 1f
                        val o = (base + corner) * 3
                        pos[o] = end[e] + sx * k; pos[o + 1] = end[e + 1] + sy * k; pos[o + 2] = end[e + 2] + sz * k
                        nor[o] = nx * sign; nor[o + 1] = ny * sign; nor[o + 2] = nz * sign
                    }
                }
            }
        }
    }

    private fun unorm(v: Float): Byte = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()

    /** Agrega las piezas a la escena. */
    fun show() {
        if (shown) return
        shown = true
        for (a in pieces) scene.addEntity(a.entity)
    }

    /** Quita y destruye todo (antes de destruir el asset: los MaterialInstance son de gltfio). */
    fun clear() {
        for (a in pieces) {
            scene.remove(a.entity)
            engine.destroyEntity(a.entity)
            EntityManager.get().destroy(a.entity)
            a.groups.forEach { it.destroy() }
        }
        shown = false
    }
}
