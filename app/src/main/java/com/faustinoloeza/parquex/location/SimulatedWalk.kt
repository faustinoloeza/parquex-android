package com.faustinoloeza.parquex.location

import com.faustinoloeza.parquex.data.ParkData
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Paseo de demostración para probar "Estás aquí" sin estar en el parque: un punto que recorre una
 * línea de ida y vuelta. [path] = [x0, z0, x1, z1, ...] en metros locales.
 */
class SimulatedWalk(private val path: FloatArray, val source: Source, val routeSlug: String? = null) {

    /** De dónde sale el recorrido (para el aviso en pantalla). */
    enum class Source { ROUTE, WALKWAYS, ACTIVITIES }

    /** Distancia acumulada hasta cada vértice. */
    private val along = FloatArray(path.size / 2).also { d ->
        for (i in 1 until d.size) {
            d[i] = d[i - 1] + hypot(path[i * 2] - path[i * 2 - 2], path[i * 2 + 1] - path[i * 2 - 1])
        }
    }
    val length get() = along.last()

    val startX get() = path[0]
    val startZ get() = path[1]

    /** Posición [x, z] tras recorrer [distance] metros (rebota en los extremos). */
    fun positionAt(distance: Float, out: FloatArray = FloatArray(2)): FloatArray {
        val len = length
        if (len <= 0f) { out[0] = path[0]; out[1] = path[1]; return out }
        var s = distance % (2 * len)
        if (s > len) s = 2 * len - s
        // Búsqueda binaria del tramo.
        var lo = 0; var hi = along.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (along[mid] <= s) lo = mid else hi = mid
        }
        val t = if (along[hi] > along[lo]) (s - along[lo]) / (along[hi] - along[lo]) else 0f
        out[0] = path[lo * 2] + (path[hi * 2] - path[lo * 2]) * t
        out[1] = path[lo * 2 + 1] + (path[hi * 2 + 1] - path[lo * 2 + 1]) * t
        return out
    }

    companion object {
        /** Velocidad del paseo (m/s): rápida a propósito para que se note el movimiento. */
        const val SPEED = 6f

        /** Largo del recorrido por andadores antes de dar la vuelta (m). */
        private const val WALKWAY_TOUR = 4_000f

        /**
         * Recorrido para [park], en este orden:
         * 1. la polilínea más larga de la ruta [preferredRoute] (o de la ruta más larga);
         * 2. si no hay rutas (Xel-Há), un paseo por los andadores de OSM (`park.json → walkways`);
         * 3. si tampoco hay andadores, las actividades en orden de número.
         */
        fun forPark(park: ParkData, preferredRoute: String? = null): SimulatedWalk? {
            val route = park.routes.firstOrNull { it.slug == preferredRoute }
                ?: park.routes.maxByOrNull { r -> r.lines.sumOf { lineLength(it).toDouble() } }
            route?.lines?.maxByOrNull(::lineLength)?.takeIf { it.size >= 4 }?.let {
                return SimulatedWalk(it, Source.ROUTE, route.slug)
            }

            val stops = park.pois.filter { it.active }.sortedBy { it.number }
            val startX = stops.firstOrNull()?.x ?: park.bounds.centerX
            val startZ = stops.firstOrNull()?.z ?: park.bounds.centerZ
            walkwayTour(park.walkways, startX, startZ)?.let { return SimulatedWalk(it, Source.WALKWAYS) }

            if (stops.size < 2) return null
            val path = FloatArray(stops.size * 2)
            stops.forEachIndexed { i, p -> path[i * 2] = p.x; path[i * 2 + 1] = p.z }
            return SimulatedWalk(path, Source.ACTIVITIES)
        }

        /**
         * Paseo aleatorio (semilla fija) por el grafo de andadores: los cruces de OSM comparten
         * coordenadas exactas. Arranca en el componente conectado más grande, en el nodo más cercano
         * a ([startX], [startZ]); prefiere tramos no recorridos y no se regresa salvo en callejones.
         */
        private fun walkwayTour(walkways: List<FloatArray>, startX: Float, startZ: Float): FloatArray? {
            val index = HashMap<Long, Int>()
            val xs = ArrayList<Float>(); val zs = ArrayList<Float>()
            val adj = ArrayList<MutableSet<Int>>()
            fun node(x: Float, z: Float): Int {
                val key = (Math.round(x * 100.0) shl 32) xor (Math.round(z * 100.0) and 0xFFFFFFFFL)
                return index.getOrPut(key) { xs += x; zs += z; adj.add(LinkedHashSet()); xs.size - 1 }
            }
            for (line in walkways) {
                var prev = -1
                for (i in 0 until line.size - 1 step 2) {
                    val n = node(line[i], line[i + 1])
                    if (prev >= 0 && prev != n) { adj[prev] += n; adj[n] += prev }
                    prev = n
                }
            }
            if (xs.isEmpty()) return null

            // Componente más grande.
            val comp = IntArray(xs.size) { -1 }
            var best = -1; var bestSize = 0
            for (seed in xs.indices) {
                if (comp[seed] >= 0) continue
                val stack = ArrayDeque(listOf(seed)); comp[seed] = seed; var size = 0
                while (stack.isNotEmpty()) {
                    val n = stack.removeLast(); size++
                    for (m in adj[n]) if (comp[m] < 0) { comp[m] = seed; stack += m }
                }
                if (size > bestSize) { bestSize = size; best = seed }
            }
            if (bestSize < 2) return null
            val start = xs.indices.filter { comp[it] == best }
                .minBy { hypot(xs[it] - startX, zs[it] - startZ) }

            val rnd = Random(7)
            val used = HashSet<Long>()
            fun edge(a: Int, b: Int) = (minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong()
            val path = ArrayList<Float>()
            path += xs[start]; path += zs[start]
            var prev = -1; var cur = start; var length = 0f; var steps = 0
            while (length < WALKWAY_TOUR && steps++ < 20_000) {
                val nbrs = adj[cur].toList()
                val options = nbrs.filter { it != prev && edge(cur, it) !in used }
                    .ifEmpty { nbrs.filter { it != prev } }
                    .ifEmpty { nbrs }
                if (options.isEmpty()) break
                val next = options[rnd.nextInt(options.size)]
                used += edge(cur, next)
                length += hypot(xs[next] - xs[cur], zs[next] - zs[cur])
                path += xs[next]; path += zs[next]
                prev = cur; cur = next
            }
            return if (path.size >= 4) path.toFloatArray() else null
        }

        private fun lineLength(line: FloatArray): Float {
            var d = 0f
            for (i in 2 until line.size step 2) d += hypot(line[i] - line[i - 2], line[i + 1] - line[i - 1])
            return d
        }
    }
}
