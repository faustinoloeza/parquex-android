package com.faustinoloeza.parquex.map

import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.VertexBuffer
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Polilíneas del suelo dibujadas como cintas de **ancho constante en pantalla** (rutas y andadores).
 *
 * Cada punto se proyecta a pantalla y se desplaza perpendicular a la línea *en pantalla*; ese
 * desplazamiento se lleva de vuelta al mundo en el plano de la cámara, a la misma profundidad.
 * [project] lo recalcula en CPU para una vista; [writeRibbon] escribe 2 vértices por punto con el
 * medio ancho (px) de su dueño.
 *
 * Las líneas se remuestrean cada [step] m: acota cuánto se adelgaza un tramo que cruza el plano
 * cercano (sus puntos detrás de la cámara quedan con ancho 0).
 *
 * @param lines polilíneas [x0, z0, x1, z1, ...] en metros
 * @param owners dueño de cada polilínea (p. ej. índice de ruta), para anchos e índices por dueño
 * @param height altura de cada punto remuestreado (índice de su polilínea, x, z), más [lift]
 * @param flat la cinta va acostada en el plano horizontal de cada punto en vez de en el plano de la
 *   cámara: con prueba de profundidad no se hunde en lo que pisa (andadores sobre un puente)
 */
internal class ScreenLines(
    lines: List<FloatArray>,
    owners: IntArray,
    step: Float,
    lift: Float,
    height: (line: Int, x: Float, z: Float) -> Float = { _, _, _ -> 0f },
    private val flat: Boolean = false,
) {

    /** Puntos remuestreados de todas las líneas, en orden. */
    val count: Int
    private val px: FloatArray
    private val py: FloatArray
    private val pz: FloatArray
    /** Dueño de cada punto. */
    private val owner: IntArray
    /** Distancia (m) a lo largo de su polilínea hasta cada punto. */
    val along: FloatArray
    /** Inicio de cada polilínea en los puntos, más un centinela al final. */
    private val lineStart: IntArray

    private val sx: FloatArray
    private val sy: FloatArray
    private val depth: FloatArray
    /** Desplazamiento en el mundo por píxel de medio ancho (x, y, z) para la última vista. */
    private val unit: FloatArray
    private val tmp = FloatArray(2)

    init {
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>(); val zs = ArrayList<Float>(); val own = ArrayList<Int>(); val dist = ArrayList<Float>()
        val starts = ArrayList<Int>()
        lines.forEachIndexed { l, line ->
            val start = xs.size
            resample(line, step) { x, z ->
                dist += if (xs.size == start) 0f else dist.last() + hypot(x - xs.last(), z - zs.last())
                xs += x; ys += lift + height(l, x, z); zs += z; own += owners[l]
            }
            if (xs.size - start < 2) {
                while (xs.size > start) {
                    xs.removeAt(xs.lastIndex); ys.removeAt(ys.lastIndex); zs.removeAt(zs.lastIndex)
                    own.removeAt(own.lastIndex); dist.removeAt(dist.lastIndex)
                }
            } else {
                starts += start
            }
        }
        count = xs.size
        px = xs.toFloatArray(); py = ys.toFloatArray(); pz = zs.toFloatArray(); owner = own.toIntArray(); along = dist.toFloatArray()
        lineStart = (starts + count).toIntArray()
        sx = FloatArray(count); sy = FloatArray(count); depth = FloatArray(count); unit = FloatArray(count * 3)
    }

    /** Índices de la cinta (2 vértices por punto a partir de [base]) de las líneas de [owner] (o todas si es null). */
    fun appendIndices(indices: MutableList<Int>, base: Int, owner: Int? = null) {
        for (l in 0 until lineStart.size - 1) {
            val s = lineStart[l]; val e = lineStart[l + 1]
            if (owner != null && this.owner[s] != owner) continue
            for (i in s until e - 1) {
                val a = base + i * 2; val b = a + 1; val c = a + 2; val d = a + 3
                indices += a; indices += c; indices += b; indices += b; indices += c; indices += d
            }
        }
    }

    /** Proyecta los puntos y calcula la normal en pantalla (con inglete) de cada uno para [snap]. */
    fun project(snap: CameraSnapshot) {
        val f = snap.focalPx
        val right = snap.right; val up = snap.up; val eye = snap.eye
        for (i in 0 until count) {
            depth[i] = snap.project(px[i], py[i], pz[i], tmp)
            sx[i] = tmp[0]; sy[i] = tmp[1]
        }
        for (l in 0 until lineStart.size - 1) {
            val s = lineStart[l]; val e = lineStart[l + 1]
            for (i in s until e) {
                var nx = 0f; var ny = 0f; var scale = 0f
                val prevOk = i > s && segment(i - 1, i)
                val n0x = tmp[0]; val n0y = tmp[1]
                val nextOk = i < e - 1 && segment(i, i + 1)
                val n1x = tmp[0]; val n1y = tmp[1]
                if (depth[i] > 0f) {
                    when {
                        prevOk && nextOk -> {
                            val mx = n0x + n1x; val my = n0y + n1y
                            val ml = hypot(mx, my)
                            if (ml < 1e-3f) { nx = n1x; ny = n1y; scale = 1f } else {
                                nx = mx / ml; ny = my / ml
                                scale = min(MAX_MITER, 1f / max(nx * n1x + ny * n1y, 0.2f))
                            }
                        }
                        nextOk -> { nx = n1x; ny = n1y; scale = 1f }
                        prevOk -> { nx = n0x; ny = n0y; scale = 1f }
                    }
                }
                // Píxeles de pantalla → metros en el plano de la cámara a esa profundidad (y de pantalla hacia abajo).
                val k = scale * depth[i] / f
                var ux = (right[0] * nx - up[0] * ny) * k
                var uy = (right[1] * nx - up[1] * ny) * k
                var uz = (right[2] * nx - up[2] * ny) * k
                if (flat) {
                    // A lo largo de la visual hasta el plano horizontal del punto: se ve igual de ancha.
                    val vx = px[i] - eye[0]; val vy = py[i] - eye[1]; val vz = pz[i] - eye[2]
                    if (vy < -1e-3f) { val t = uy / vy; ux -= vx * t; uz -= vz * t; uy = 0f }
                }
                unit[i * 3] = ux; unit[i * 3 + 1] = uy; unit[i * 3 + 2] = uz
            }
        }
    }

    /**
     * Escribe en [out] desde [offset] 2 vértices (x, y, z) por punto, separados [halfWidth] px del
     * centro según su dueño (`halfWidth[owner]`). Devuelve el siguiente índice libre de [out].
     */
    fun writeRibbon(out: FloatArray, offset: Int, halfWidth: FloatArray): Int {
        var o = offset
        for (i in 0 until count) {
            val h = halfWidth[owner[i]]
            val ux = unit[i * 3] * h; val uy = unit[i * 3 + 1] * h; val uz = unit[i * 3 + 2] * h
            out[o++] = px[i] + ux; out[o++] = py[i] + uy; out[o++] = pz[i] + uz
            out[o++] = px[i] - ux; out[o++] = py[i] - uy; out[o++] = pz[i] - uz
        }
        return o
    }

    /** Normal unitaria en pantalla del tramo a→b en [tmp]; false si no se puede proyectar. */
    private fun segment(a: Int, b: Int): Boolean {
        if (depth[a] <= 0f || depth[b] <= 0f) return false
        val dx = sx[b] - sx[a]; val dy = sy[b] - sy[a]
        val len = hypot(dx, dy)
        if (len < 1e-3f) return false
        tmp[0] = -dy / len; tmp[1] = dx / len
        return true
    }

    companion object {
        private const val MAX_MITER = 2.5f

        /** Puntos cada ≤ [step] m a lo largo de [line], sin repetidos. */
        fun resample(line: FloatArray, step: Float, emit: (Float, Float) -> Unit) {
            var lastX = Float.NaN; var lastZ = Float.NaN
            fun push(x: Float, z: Float) {
                if (lastX.isNaN() || hypot(x - lastX, z - lastZ) > 1e-3f) { emit(x, z); lastX = x; lastZ = z }
            }
            for (i in 2 until line.size step 2) {
                val x0 = line[i - 2]; val z0 = line[i - 1]; val x1 = line[i]; val z1 = line[i + 1]
                val n = max(1, ceil(hypot(x1 - x0, z1 - z0) / step).toInt())
                for (k in 0 until n) push(x0 + (x1 - x0) * k / n, z0 + (z1 - z0) * k / n)
            }
            if (line.size >= 2) push(line[line.size - 2], line[line.size - 1])
        }

        /** Búfer de índices (USHORT, o UINT si hace falta) con [indices]. */
        fun indexBuffer(engine: Engine, indices: List<Int>, vertexCount: Int): IndexBuffer {
            val wide = vertexCount > 65_535
            val ib = IndexBuffer.Builder().indexCount(indices.size)
                .bufferType(if (wide) IndexBuffer.Builder.IndexType.UINT else IndexBuffer.Builder.IndexType.USHORT)
                .build(engine)
            val data = UnlitMaterials.directBuffer(indices.size * if (wide) 4 else 2)
            if (wide) indices.forEach { data.putInt(it) } else indices.forEach { data.putShort(it.toShort()) }
            data.flip()
            ib.setBuffer(engine, data)
            return ib
        }
    }
}

/**
 * Búferes de posición que se rotan para un [VertexBuffer] que cambia en cada cuadro: Filament lee el
 * anterior mientras se llena el siguiente.
 */
internal class PositionRing(vertexCount: Int, size: Int = 4) {
    private val bytes: Array<ByteBuffer> = Array(size) { UnlitMaterials.directBuffer(vertexCount * 12) }
    private val floats: Array<FloatBuffer> = Array(size) { bytes[it].asFloatBuffer() }
    private var next = 0

    /** Sube [positions] (x, y, z por vértice) al búfer 0 de [vb]. */
    fun upload(engine: Engine, vb: VertexBuffer, positions: FloatArray) {
        val slot = next++ % bytes.size
        floats[slot].clear()
        floats[slot].put(positions)
        bytes[slot].clear()
        vb.setBufferAt(engine, 0, bytes[slot])
    }
}
