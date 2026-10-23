package com.faustinoloeza.parquex.nav

import com.faustinoloeza.parquex.data.ParkData
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * Grafo peatonal de un parque y rutas a pie más cortas sobre él: port uno a uno de
 * `shared/nav-graph.js` de la web (mismas constantes, pesos, índice espacial, A*, recorte de
 * picos e indicaciones). Funciones puras, sin Android: se prueban en la JVM.
 *
 *   val graph = buildGraph(park)
 *   val r = route(graph, from, to, toName, t, landmarks)
 *   r.points  del inicio al destino (conectores incluidos)
 *   r.steps   indicaciones giro a giro
 *
 * Aristas: `park.json → walkways` (líneas que se tocan en coordenadas idénticas; las puntas
 * sueltas a ≤ Nav.SNAP m se unen a la línea más cercana) y las líneas de las rutas oficiales
 * (`routes[].lines`), atadas a los andadores cada Nav.LINK_STEP m donde corren a ≤ Nav.LINK m.
 * Las piezas de la red que quedan separadas se puentean a ≤ Nav.BRIDGE m. Origen y destino no
 * son nodos: se proyectan sobre la arista más cercana al calcular la ruta.
 */

/** Constantes de `NAV` (nav-graph.js). */
object Nav {
    /** m: puntas sueltas de andador a esta distancia de otra línea se unen a ella. */
    const val SNAP = 4.0
    /** m: una ruta oficial a esta distancia de un andador se ata a él. */
    const val LINK = 6.0
    /** m: separación de esas ataduras a lo largo de la ruta. */
    const val LINK_STEP = 20.0
    /** m: un callejón sin salida tan cerca de otro andador se ata a él si la vuelta es larga. */
    const val DEAD_END = 25.0
    /** m: piezas separadas de la red más cercanas que esto se puentean. */
    const val BRIDGE = 30.0
    /** m: lo más lejos que puede quedar una actividad de la red. */
    const val REACH = 60.0
    /** Velocidad a pie (m/s), ≈ 4.5 km/h. */
    const val SPEED = 1.25

    /** Costo por metro según el tipo de arista: rutas oficiales y andadores antes que ataduras y puentes. */
    val WEIGHTS: Map<EdgeKind, Double> = mapOf(
        EdgeKind.ROUTE to 0.95, EdgeKind.WALKWAY to 1.0, EdgeKind.LINK to 1.15, EdgeKind.BRIDGE to 1.4,
    )
}

/** Parámetros de [buildGraph] (por omisión, los de [Nav]). */
data class NavOptions(
    val snap: Double = Nav.SNAP,
    val link: Double = Nav.LINK,
    val linkStep: Double = Nav.LINK_STEP,
    val deadEnd: Double = Nav.DEAD_END,
    val bridge: Double = Nav.BRIDGE,
    val reach: Double = Nav.REACH,
    val speed: Double = Nav.SPEED,
    val weights: Map<EdgeKind, Double> = Nav.WEIGHTS,
)

enum class EdgeKind { WALKWAY, ROUTE, LINK, BRIDGE }

/** Punto del suelo en metros locales (x = este, z = sur). */
data class NavPoint(val x: Double, val z: Double)

/** Lugar que se puede nombrar en una indicación ("… cerca de {near}"). */
data class Landmark(val name: String, val x: Double, val z: Double)

class NavEdge(val a: Int, val b: Int, val kind: EdgeKind, val len: Double)

data class NavStats(val nodes: Int, val edges: Int, val components: Int, val bridges: Int, val length: Double)

/**
 * Grafo peatonal. [adj] por nodo: pares intercalados (arista, otro nodo); [comp] componente
 * conexa de cada nodo y [compSize] nodos por componente; [grid] índice espacial de aristas
 * (celdas de 25 m).
 */
class NavGraph internal constructor(
    val x: DoubleArray,
    val z: DoubleArray,
    val edges: List<NavEdge>,
    val adj: Array<IntArray>,
    val comp: IntArray,
    val compSize: IntArray,
    internal val grid: Map<Long, List<Int>>,
    val weights: Map<EdgeKind, Double>,
    val reach: Double,
    val stats: NavStats,
) {
    /** Número de aristas que llegan al nodo [n]. */
    fun degree(n: Int) = adj[n].size / 2
}

/** Punto más cercano sobre una arista: índice [e], parámetro [t] (0…1), punto y distancia [d]. */
data class EdgeHit(val e: Int, val t: Double, val x: Double, val z: Double, val d: Double, val comp: Int = -1)

/** Proyección de (x, z) sobre el segmento a → b. */
data class SegmentProjection(val t: Double, val x: Double, val z: Double, val d: Double)

/** Punto a cierta distancia de una polilínea, con el rumbo de ese tramo ([dx], [dz] unitarios) y su segmento [i]. */
data class Along(val x: Double, val z: Double, val dx: Double, val dz: Double, val i: Int)

/** Tipo de paso: seguir, girar o llegar. [key] es la parte de la llave de texto (`nav_left`…). */
enum class Turn(val key: String) { STRAIGHT("straight"), LEFT("left"), RIGHT("right"), ARRIVE("arrive") }

/**
 * Indicación giro a giro. [along]: m desde el inicio; [distance]: m hasta el siguiente paso;
 * [dir]: rumbo inicial (`n`, `ne`…) solo en el primer paso; [near]: lugar a ≤ Turns.LANDMARK m
 * (o el nombre del destino en la llegada).
 */
data class NavStep(
    val turn: Turn,
    val along: Double,
    val at: NavPoint,
    val distance: Double = 0.0,
    val instruction: String = "",
    val slight: Boolean = false,
    val dir: String? = null,
    val near: String? = null,
)

/**
 * Ruta a pie. [gaps]: metros de conector al inicio y al final (se dibujan punteados); [direct]:
 * línea recta (sin red cercana o más corta que entrar y salir de ella).
 */
data class NavRoute(
    val points: List<NavPoint>,
    val length: Double,
    val steps: List<NavStep>,
    val gaps: DoubleArray,
    val direct: Boolean,
)

/** Traductor de textos: llave `nav_*` → plantilla con marcadores `{dir}`, `{near}`, `{name}`, `{text}`, `{dist}`. */
typealias NavText = (String) -> String

private const val CELL = 25.0

/** Un callejón se ata cuando la vuelta es más larga que DETOUR × su largo + 30 m. */
private const val DETOUR = 3.0

// ------------------------------------------------------------------------------ grafo

/** Grafo peatonal de un parque (`walkways` + líneas de `routes`). */
fun buildGraph(park: ParkData, options: NavOptions = NavOptions()): NavGraph =
    buildGraph(park.walkways, park.routes.flatMap { it.lines }, options, entrance = park.entrance)

/**
 * Grafo peatonal a partir de polilíneas [x0, z0, x1, z1, ...]: [walkways] (andadores) y
 * [routeLines] (líneas de rutas oficiales).
 */
fun buildGraph(
    walkways: List<FloatArray>,
    routeLines: List<FloatArray> = emptyList(),
    options: NavOptions = NavOptions(),
    entrance: ParkData.Entrance? = null,
): NavGraph {
    val o = options
    val b = Builder()
    var lineId = 0
    class Line(val id: Int, val first: Int, val last: Int)
    val walkLines = ArrayList<Line>()
    for (line in walkways) {
        val id = lineId++
        val nodes = IntArray(line.size / 2) { b.node(line[it * 2].toDouble(), line[it * 2 + 1].toDouble()) }
        for (i in 1 until nodes.size) b.edge(nodes[i - 1], nodes[i], EdgeKind.WALKWAY, id)
        if (nodes.size > 1) walkLines += Line(id, nodes[0], nodes.last())
    }
    val routeLineList = ArrayList<Line>()
    for (line in routeLines) {
        val id = lineId++
        val nodes = IntArray(line.size / 2) { b.node(line[it * 2].toDouble(), line[it * 2 + 1].toDouble()) }
        for (i in 1 until nodes.size) b.edge(nodes[i - 1], nodes[i], EdgeKind.ROUTE, id)
        if (nodes.size > 1) routeLineList += Line(id, nodes[0], nodes.last())
    }

    // puntas sueltas: se unen a la otra línea más cercana a ≤ snap m
    for (l in walkLines + routeLineList) {
        for (n in intArrayOf(l.first, l.last)) {
            if (b.degree(n) != 1) continue
            val hit = b.nearest(b.x[n], b.z[n], o.snap) { e -> e.line != l.id && e.a != n && e.b != n } ?: continue
            val m = b.split(hit.e, hit.t)
            if (m != n) b.edge(n, m, b.edges[hit.e].kind, l.id)
        }
    }

    // las rutas oficiales corren junto a andadores: se atan cada linkStep m (y en sus extremos)
    if (walkLines.isNotEmpty()) {
        for (l in routeLineList) {
            val samples = b.samplesAlong(l.id, o.linkStep)
            for (s in samples) {
                val hit = b.nearest(s.x, s.z, o.link) { e -> e.kind == EdgeKind.WALKWAY } ?: continue
                b.resolve(s)
                val from = b.split(s.e, s.t)
                val to = b.split(hit.e, hit.t)
                if (from != to && !b.connected(from, to)) b.edge(from, to, EdgeKind.LINK, -1)
            }
        }
    }

    // callejones que se quedan cortos de otro andador (plazas, huecos de OSM): una atadura corta
    // cuando dar la vuelta sería mucho más largo
    var n = 0
    while (n < b.x.size) {
        if (b.degree(n) != 1) { n++; continue }
        // lo ya alcanzable a pie sin una vuelta larga no cuenta
        val near = b.within(n, DETOUR * o.deadEnd + 30)
        val xn = b.x[n]; val zn = b.z[n]
        val hit = b.nearest(xn, zn, o.deadEnd) { e ->
            val da = near[e.a] ?: Double.POSITIVE_INFINITY
            val db = near[e.b] ?: Double.POSITIVE_INFINITY
            min(da, db) > DETOUR * hypot(b.x[e.a] - xn, b.z[e.a] - zn) + 30
        }
        if (hit != null) {
            val m = b.split(hit.e, hit.t)
            b.edge(n, m, EdgeKind.BRIDGE, -1)
        }
        n++
    }

    if (entrance != null) {
        val hit = b.nearest(entrance.x.toDouble(), entrance.z.toDouble(), max = 100.0)
        if (hit != null) {
            val entNode = b.node(entrance.x.toDouble(), entrance.z.toDouble())
            val hitNode = b.split(hit.e, hit.t)
            if (entNode != hitNode && !b.connected(entNode, hitNode)) {
                b.edge(entNode, hitNode, EdgeKind.WALKWAY, -1)
            }
        }
    }

    return finish(b, o)
}

/** Estado de construcción: nodos por coordenadas exactas y aristas con índice de celdas. */
private class Builder {
    class Edge(val a: Int, val b: Int, val kind: EdgeKind, val len: Double, val line: Int) {
        var dead = false
    }

    /** Muestra a lo largo de una línea; su arista puede partirse después ([resolve]). */
    class Sample(var e: Int, var t: Double, val x: Double, val z: Double)

    val x = ArrayList<Double>()
    val z = ArrayList<Double>()
    private val keys = HashMap<NavPoint, Int>()
    val edges = ArrayList<Edge>()
    /** Por nodo: aristas que llegan (vivas o no). */
    val inc = ArrayList<MutableList<Int>>()
    val grid = HashMap<Long, MutableList<Int>>()

    fun node(x: Double, z: Double): Int {
        val key = NavPoint(x + 0.0, z + 0.0) // + 0.0: −0 y 0 son la misma clave, como en la web
        return keys.getOrPut(key) {
            this.x.add(x); this.z.add(z); inc.add(ArrayList())
            this.x.size - 1
        }
    }

    fun edge(a: Int, b: Int, kind: EdgeKind, line: Int): Int {
        if (a == b) return -1
        val len = hypot(x[b] - x[a], z[b] - z[a])
        val e = edges.size
        edges += Edge(a, b, kind, len, line)
        inc[a] += e
        inc[b] += e
        indexEdge(grid, e, x[a], z[a], x[b], z[b])
        return e
    }

    /** Nodos alcanzables desde [n] a ≤ [limit] m por aristas vivas (Dijkstra acotado). */
    fun within(n: Int, limit: Double): Map<Int, Double> {
        val dist = HashMap<Int, Double>()
        dist[n] = 0.0
        val heap = MinHeap()
        heap.push(0.0, n)
        while (heap.size > 0) {
            val i = heap.pop()
            val d = dist.getValue(i)
            for (e in inc[i]) {
                val ed = edges[e]
                if (ed.dead) continue
                val m = if (ed.a == i) ed.b else ed.a
                val nd = d + ed.len
                if (nd <= limit && nd < (dist[m] ?: Double.POSITIVE_INFINITY)) {
                    dist[m] = nd
                    heap.push(nd, m)
                }
            }
        }
        return dist
    }

    fun degree(n: Int) = inc[n].count { !edges[it].dead }

    fun connected(a: Int, b: Int) = inc[a].any { !edges[it].dead && (edges[it].a == b || edges[it].b == b) }

    /** Punto más cercano sobre una arista viva a ≤ [max] m, o null. */
    fun nearest(px: Double, pz: Double, max: Double, accept: (Edge) -> Boolean = { true }): EdgeHit? =
        nearestOnGrid(grid, { edges[it].a }, { edges[it].b }, { x[it] }, { z[it] }, px, pz, max) { e ->
            val ed = edges[e]
            !ed.dead && accept(ed)
        }

    /** Nodo en el punto (e, t) de una arista; la parte en dos si el punto cae dentro. */
    fun split(e: Int, t: Double): Int {
        val ed = edges[e]
        if (t * ed.len < 0.05) return ed.a
        if ((1 - t) * ed.len < 0.05) return ed.b
        val nx = x[ed.a] + (x[ed.b] - x[ed.a]) * t
        val nz = z[ed.a] + (z[ed.b] - z[ed.a]) * t
        val n = node(nx, nz)
        ed.dead = true
        edge(ed.a, n, ed.kind, ed.line)
        edge(n, ed.b, ed.kind, ed.line)
        return n
    }

    /**
     * Puntos cada [step] m a lo largo de una línea (con sus dos extremos), cada uno sobre la arista
     * viva en que cae. Recorre la línea desde una punta.
     */
    fun samplesAlong(line: Int, step: Double): List<Sample> {
        val out = ArrayList<Sample>()
        val lineEdges = edges.indices.filter { !edges[it].dead && edges[it].line == line }
        if (lineEdges.isEmpty()) return out
        val byNode = LinkedHashMap<Int, MutableList<Int>>()
        for (i in lineEdges) for (n in intArrayOf(edges[i].a, edges[i].b)) byNode.getOrPut(n) { ArrayList() } += i
        val start = byNode.keys.firstOrNull { byNode.getValue(it).size == 1 } ?: edges[lineEdges[0]].a
        val used = LinkedHashSet<Int>()
        var n = start
        var walked = 0.0
        var next = 0.0
        while (true) {
            val e = byNode.getValue(n).firstOrNull { it !in used } ?: break
            used += e
            val ed = edges[e]
            val fwd = ed.a == n
            while (next <= walked + ed.len) {
                val k = (next - walked) / ed.len
                val t = if (fwd) k else 1 - k
                out += Sample(e, t, x[ed.a] + (x[ed.b] - x[ed.a]) * t, z[ed.a] + (z[ed.b] - z[ed.a]) * t)
                next += step
            }
            walked += ed.len
            n = if (fwd) ed.b else ed.a
        }
        val lastE = used.last()
        val last = edges[lastE]
        val end = if (last.a == n) 0.0 else 1.0
        out += Sample(lastE, end, x[n], z[n])
        return out
    }

    /** Si la arista de la muestra ya se partió, la vuelve a encontrar entre las vivas de su línea. */
    fun resolve(s: Sample) {
        if (!edges[s.e].dead) return
        val line = edges[s.e].line
        val hit = nearest(s.x, s.z, 0.5) { it.line == line } ?: return
        s.e = hit.e
        s.t = hit.t
    }
}

private fun cellKey(i: Int, j: Int) = (i.toLong() shl 32) xor (j.toLong() and 0xFFFFFFFFL)

private fun indexEdge(grid: MutableMap<Long, MutableList<Int>>, e: Int, x0: Double, z0: Double, x1: Double, z1: Double) {
    val i0 = floor(min(x0, x1) / CELL).toInt(); val i1 = floor(max(x0, x1) / CELL).toInt()
    val j0 = floor(min(z0, z1) / CELL).toInt(); val j1 = floor(max(z0, z1) / CELL).toInt()
    for (i in i0..i1) for (j in j0..j1) grid.getOrPut(cellKey(i, j)) { ArrayList() } += e
}

/** Punto más cercano a (x, z) sobre las aristas indexadas que acepta [accept], a ≤ [max] m. */
private inline fun nearestOnGrid(
    grid: Map<Long, List<Int>>,
    edgeA: (Int) -> Int, edgeB: (Int) -> Int,
    xs: (Int) -> Double, zs: (Int) -> Double,
    x: Double, z: Double, max: Double,
    accept: (Int) -> Boolean,
): EdgeHit? {
    var best: EdgeHit? = null
    val ci = floor(x / CELL).toInt(); val cj = floor(z / CELL).toInt()
    val seen = HashSet<Int>()
    val rings = ceil(max / CELL).toInt() + 1
    for (r in 0..rings) {
        // celdas a distancia de Chebyshev r: todo lo que hay ahí está a ≥ (r − 1) · CELL
        if (best != null && best.d < (r - 1) * CELL) break
        for (i in ci - r..ci + r) for (j in cj - r..cj + r) {
            if (max(abs(i - ci), abs(j - cj)) != r) continue
            for (e in grid[cellKey(i, j)] ?: continue) {
                if (!seen.add(e)) continue
                if (!accept(e)) continue
                val a = edgeA(e); val b = edgeB(e)
                val p = projectSegment(x, z, xs(a), zs(a), xs(b), zs(b))
                if (p.d <= max && (best == null || p.d < best.d)) best = EdgeHit(e, p.t, p.x, p.z, p.d)
            }
        }
    }
    return best
}

/** Proyección de (x, z) sobre el segmento a → b. */
fun projectSegment(x: Double, z: Double, ax: Double, az: Double, bx: Double, bz: Double): SegmentProjection {
    val dx = bx - ax; val dz = bz - az
    val l2 = dx * dx + dz * dz
    val t = if (l2 != 0.0) max(0.0, min(1.0, ((x - ax) * dx + (z - az) * dz) / l2)) else 0.0
    val px = ax + dx * t; val pz = az + dz * t
    return SegmentProjection(t, px, pz, hypot(x - px, z - pz))
}

/** Compacta las aristas vivas, puentea las piezas separadas y arma la adyacencia. */
private fun finish(b: Builder, o: NavOptions): NavGraph {
    val keep = b.edges.filter { !it.dead }
    // quita los nodos que ninguna arista viva usa
    val used = HashSet<Int>()
    for (e in keep) { used += e.a; used += e.b }
    val remap = HashMap<Int, Int>()
    val xl = ArrayList<Double>(); val zl = ArrayList<Double>()
    for (n in b.x.indices) if (n in used) {
        remap[n] = xl.size
        xl += b.x[n]; zl += b.z[n]
    }
    val x = xl.toDoubleArray(); val z = zl.toDoubleArray()
    val edges = keep.map { NavEdge(remap.getValue(it.a), remap.getValue(it.b), it.kind, it.len) }.toMutableList()

    // puentes: los pares de nodos más cercanos entre piezas, al estilo Kruskal, hasta `bridge` m
    val uf = UnionFind(x.size)
    for (e in edges) uf.union(e.a, e.b)
    val nodeGrid = HashMap<Long, MutableList<Int>>()
    for (n in x.indices) nodeGrid.getOrPut(cellKey(floor(x[n] / o.bridge).toInt(), floor(z[n] / o.bridge).toInt())) { ArrayList() } += n
    class Pair3(val d: Double, val a: Int, val b: Int)
    val pairs = LinkedHashMap<Long, Pair3>() // (raízA, raízB) → par más cercano
    for (n in x.indices) {
        val ci = floor(x[n] / o.bridge).toInt(); val cj = floor(z[n] / o.bridge).toInt()
        val rn = uf.find(n)
        for (i in ci - 1..ci + 1) for (j in cj - 1..cj + 1) for (m in nodeGrid[cellKey(i, j)] ?: continue) {
            if (m <= n) continue
            val rm = uf.find(m)
            if (rm == rn) continue
            val d = hypot(x[m] - x[n], z[m] - z[n])
            if (d > o.bridge) continue
            val key = if (rn < rm) cellKey(rn, rm) else cellKey(rm, rn)
            val cur = pairs[key]
            if (cur == null || d < cur.d) pairs[key] = Pair3(d, n, m)
        }
    }
    var bridges = 0
    for (p in pairs.values.sortedBy { it.d }) {
        if (uf.find(p.a) == uf.find(p.b)) continue
        uf.union(p.a, p.b)
        edges += NavEdge(p.a, p.b, EdgeKind.BRIDGE, p.d)
        bridges++
    }

    val adjLists = Array(x.size) { ArrayList<Int>() }
    val grid = HashMap<Long, MutableList<Int>>()
    edges.forEachIndexed { i, e ->
        adjLists[e.a] += i; adjLists[e.a] += e.b
        adjLists[e.b] += i; adjLists[e.b] += e.a
        indexEdge(grid, i, x[e.a], z[e.a], x[e.b], z[e.b])
    }
    val roots = LinkedHashMap<Int, Int>()
    val comp = IntArray(x.size) { n -> roots.getOrPut(uf.find(n)) { roots.size } }
    val compSize = IntArray(roots.size)
    for (c in comp) compSize[c]++
    return NavGraph(
        x, z, edges, Array(x.size) { adjLists[it].toIntArray() }, comp, compSize, grid, o.weights, o.reach,
        NavStats(
            nodes = x.size, edges = edges.size, components = roots.size, bridges = bridges,
            length = edges.sumOf { if (it.kind == EdgeKind.LINK || it.kind == EdgeKind.BRIDGE) 0.0 else it.len },
        ),
    )
}

private class UnionFind(n: Int) {
    private val parent = IntArray(n) { it }
    fun find(i0: Int): Int {
        var i = i0
        while (parent[i] != i) {
            parent[i] = parent[parent[i]]
            i = parent[i]
        }
        return i
    }
    fun union(a: Int, b: Int) {
        val ra = find(a); val rb = find(b)
        if (ra != rb) parent[max(ra, rb)] = min(ra, rb)
    }
}

/**
 * Punto del grafo más cercano a (x, z), o null (grafo vacío o nada a ≤ [max] m). [comp] limita
 * la búsqueda a una componente.
 */
fun nearestPoint(graph: NavGraph, x: Double, z: Double, max: Double = Double.POSITIVE_INFINITY, comp: Int? = null): EdgeHit? {
    if (graph.edges.isEmpty()) return null
    // radio de búsqueda creciente en lugar de revisar todo de una vez
    var r = 50.0
    while (true) {
        val lim = min(r, max)
        val hit = nearestOnGrid(
            graph.grid, { graph.edges[it].a }, { graph.edges[it].b }, { graph.x[it] }, { graph.z[it] }, x, z, lim,
        ) { e -> comp == null || graph.comp[graph.edges[e].a] == comp }
        if (hit != null) return hit.copy(comp = graph.comp[graph.edges[hit.e].a])
        if (lim >= max || r > 1e5) return null
        r *= 4
    }
}

// ------------------------------------------------------------------------------ ruta

/**
 * Ruta a pie más corta (A*) de [from] a [to]. Ambos se proyectan sobre la arista más cercana
 * (de la misma componente: gana la más grande); los tramos entre ellos y la red son parte de la
 * ruta ([NavRoute.gaps]). Sin red cercana, línea recta ([NavRoute.direct]).
 *
 * @param toName nombre del destino para el último paso ("Llegas a {name}")
 * @param t traductor de los textos de los pasos (llaves `nav_*`)
 * @param landmarks lugares que se nombran en los giros
 */
fun route(
    graph: NavGraph,
    from: NavPoint,
    to: NavPoint,
    toName: String? = null,
    t: NavText = { it },
    landmarks: List<Landmark> = emptyList(),
): NavRoute {
    fun finishRoute(points: List<NavPoint>, gaps: DoubleArray, direct: Boolean, isJunction: (Double, Double) -> Boolean = { _, _ -> true }): NavRoute {
        val pts = dedupe(points)
        return NavRoute(pts, polylineLength(pts), makeSteps(pts, t, landmarks, toName, isJunction), gaps, direct)
    }
    fun direct() = finishRoute(listOf(from, to), doubleArrayOf(0.0, 0.0), true)

    var s = nearestPoint(graph, from.x, from.z)
    var e = nearestPoint(graph, to.x, to.z)
    if (s == null || e == null) return direct()
    if (s.comp != e.comp) {
        // se queda la pieza más grande de la red; el otro extremo se lleva a ella
        if (graph.compSize[s.comp] >= graph.compSize[e.comp]) e = nearestPoint(graph, to.x, to.z, comp = s.comp)
        else s = nearestPoint(graph, from.x, from.z, comp = e.comp)
        if (s == null || e == null) return direct()
    }
    // caminar en línea recta es más corto que llegar a la red
    val straight = hypot(to.x - from.x, to.z - from.z)
    if (straight <= s.d + e.d) return direct()
    val path = astar(graph, s, e) ?: return direct()
    val onGraph = pruneSpikes(listOf(NavPoint(s.x, s.z)) + path.map { NavPoint(graph.x[it], graph.z[it]) } + NavPoint(e.x, e.z))
    val points = listOf(from) + onGraph + listOf(to)
    // puntos de decisión: cruces de la red y donde la caminata entra o sale de ella
    val junctions = HashSet<NavPoint>()
    junctions += NavPoint(s.x + 0.0, s.z + 0.0)
    junctions += NavPoint(e.x + 0.0, e.z + 0.0)
    for (n in path) if (graph.degree(n) >= 3) junctions += NavPoint(graph.x[n] + 0.0, graph.z[n] + 0.0)
    return finishRoute(points, doubleArrayOf(s.d, e.d), false) { x, z -> NavPoint(x + 0.0, z + 0.0) in junctions }
}

/** A* entre dos puntos sobre aristas; devuelve los nodos del grafo entre ellos (quizá ninguno). */
private fun astar(graph: NavGraph, s: EdgeHit, e: EdgeHit): List<Int>? {
    val x = graph.x; val z = graph.z; val edges = graph.edges; val adj = graph.adj
    val n = x.size
    val start = n; val end = n + 1
    val es = edges[s.e]; val ee = edges[e.e]
    fun w(ed: NavEdge) = graph.weights[ed.kind] ?: 1.0
    val minW = graph.weights.values.min()
    fun h(i: Int): Double = if (i == end) 0.0 else
        hypot((if (i == start) s.x else x[i]) - e.x, (if (i == start) s.z else z[i]) - e.z) * minW
    // inicio virtual (sobre la arista s.e) y fin virtual (sobre e.e)
    fun neighbors(i: Int, visit: (Int, Double) -> Unit) {
        if (i == start) {
            visit(es.a, s.t * es.len * w(es))
            visit(es.b, (1 - s.t) * es.len * w(es))
            if (s.e == e.e) visit(end, abs(s.t - e.t) * es.len * w(es))
            return
        }
        val list = adj[i]
        var k = 0
        while (k < list.size) {
            val ed = edges[list[k]]
            visit(list[k + 1], ed.len * w(ed))
            k += 2
        }
        if (i == ee.a) visit(end, e.t * ee.len * w(ee))
        if (i == ee.b) visit(end, (1 - e.t) * ee.len * w(ee))
    }
    val g = DoubleArray(n + 2) { Double.POSITIVE_INFINITY }
    val prev = IntArray(n + 2) { -1 }
    val closed = BooleanArray(n + 2)
    g[start] = 0.0
    val heap = MinHeap()
    heap.push(h(start), start)
    while (heap.size > 0) {
        val i = heap.pop()
        if (closed[i]) continue
        if (i == end) break
        closed[i] = true
        neighbors(i) { m, c ->
            val ng = g[i] + c
            if (ng < g[m]) {
                g[m] = ng
                prev[m] = i
                heap.push(ng + h(m), m)
            }
        }
    }
    if (g[end] == Double.POSITIVE_INFINITY) return null
    val path = ArrayList<Int>()
    var i = prev[end]
    while (i != start && i != -1) {
        path += i
        i = prev[i]
    }
    path.reverse()
    return path
}

/** Montículo mínimo de (clave, valor), idéntico al de la web (mismo desempate). */
private class MinHeap {
    private var k = DoubleArray(64)
    private var v = IntArray(64)
    var size = 0
        private set

    fun push(key: Double, value: Int) {
        if (size == k.size) { k = k.copyOf(size * 2); v = v.copyOf(size * 2) }
        var i = size++
        while (i > 0) {
            val p = (i - 1) shr 1
            if (k[p] <= key) break
            k[i] = k[p]; v[i] = v[p]
            i = p
        }
        k[i] = key; v[i] = value
    }

    fun pop(): Int {
        val top = v[0]
        size--
        val lk = k[size]; val lv = v[size]
        if (size > 0) {
            var i = 0
            while (true) {
                var c = 2 * i + 1
                if (c >= size) break
                if (c + 1 < size && k[c + 1] < k[c]) c++
                if (k[c] >= lk) break
                k[i] = k[c]; v[i] = v[c]
                i = c
            }
            k[i] = lk; v[i] = lv
        }
        return top
    }
}

// ------------------------------------------------------------------------------ polilíneas

/** Picos más cortos que esto (m de lado a lado) se cortan (ver [pruneSpikes]). */
object Spike {
    const val ACROSS = 8.0
    const val LOOKAHEAD = 120.0
}

/**
 * Corta idas y vueltas y lazos chicos de un camino: donde regresa a ≤ Spike.ACROSS m de un punto
 * anterior tras caminar más de tres veces esa distancia (+ 10 m), el tramo intermedio se cambia
 * por el cruce corto. Aparecen donde un andador y una ruta oficial corren lado a lado y el camino
 * salta entre ellos en una atadura.
 */
fun pruneSpikes(points: List<NavPoint>): List<NavPoint> {
    val out = ArrayList<NavPoint>()
    var i = 0
    while (i < points.size) {
        out += points[i]
        var walked = 0.0
        var cut = -1
        var j = i + 1
        while (j < points.size && walked < Spike.LOOKAHEAD) {
            walked += hypot(points[j].x - points[j - 1].x, points[j].z - points[j - 1].z)
            val gap = hypot(points[j].x - points[i].x, points[j].z - points[i].z)
            if (gap < Spike.ACROSS && walked > 3 * gap + 10) cut = j
            j++
        }
        i = if (cut > 0) cut else i + 1
    }
    return out
}

private fun dedupe(points: List<NavPoint>): List<NavPoint> {
    val out = ArrayList<NavPoint>()
    for (p in points) {
        val q = out.lastOrNull()
        if (q == null || hypot(p.x - q.x, p.z - q.z) > 0.05) out += p
    }
    if (out.size == 1) out += out[0].copy()
    return out
}

fun polylineLength(points: List<NavPoint>): Double {
    var s = 0.0
    for (i in 1 until points.size) s += hypot(points[i].x - points[i - 1].x, points[i].z - points[i - 1].z)
    return s
}

/** Distancia acumulada en cada punto. */
fun cumulative(points: List<NavPoint>): DoubleArray {
    val cum = DoubleArray(points.size)
    for (i in 1 until points.size) cum[i] = cum[i - 1] + hypot(points[i].x - points[i - 1].x, points[i].z - points[i - 1].z)
    return cum
}

/** Punto a [s] m a lo largo de la polilínea, con el rumbo de ese tramo. */
fun pointAlong(points: List<NavPoint>, cum: DoubleArray, s0: Double): Along {
    val total = cum.last()
    val s = max(0.0, min(total, s0))
    // búsqueda binaria del segmento
    var lo = 1
    var hi = points.size - 1
    while (lo < hi) {
        val mid = (lo + hi) shr 1
        if (cum[mid] < s) lo = mid + 1 else hi = mid
    }
    val i = lo
    val a = points[i - 1]; val b = points[i]
    val len = (cum[i] - cum[i - 1]).takeIf { it != 0.0 } ?: 1.0
    val k = (s - cum[i - 1]) / len
    return Along(a.x + (b.x - a.x) * k, a.z + (b.z - a.z) * k, (b.x - a.x) / len, (b.z - a.z) / len, i)
}

// ------------------------------------------------------------------------------ giro a giro

/**
 * Detección de giros: cambio de rumbo (°) arriba del cual un vértice es giro ([MIN]; [BEND] donde
 * el camino solo se curva sin otro camino que tomar), debajo del cual el giro es "ligero"
 * ([SLIGHT]); metros medidos a cada lado ([WINDOW]), entre dos giros ([MERGE]) y a un lugar
 * nombrado en un giro ([LANDMARK]).
 */
object Turns {
    const val MIN = 35.0
    const val BEND = 100.0
    const val SLIGHT = 60.0
    const val WINDOW = 10.0
    const val MERGE = 18.0
    const val LANDMARK = 30.0
}

private val COMPASS = arrayOf("n", "ne", "e", "se", "s", "sw", "w", "nw")

/** Punto cardinal de un rumbo (x = este, z = sur): `n`, `ne`, … */
fun compassOf(dx: Double, dz: Double): String {
    val deg = Math.toDegrees(atan2(dx, -dz)) // 0 = norte, 90 = este
    val r = floor(deg / 45 + 0.5).toInt() // Math.round de JS
    return COMPASS[((r % 8) + 8) % 8]
}

/** Cambio de rumbo con signo (°) de la dirección a a la b: > 0 = derecha (horario visto desde arriba, norte arriba). */
fun turnAngle(ax: Double, az: Double, bx: Double, bz: Double): Double {
    val cross = ax * bz - az * bx
    val dot = ax * bx + az * bz
    return Math.toDegrees(atan2(cross, dot))
}

/**
 * Indicaciones de una polilínea: un primer paso "camina hacia el {dir}", uno por giro (cambio de
 * rumbo > Turns.MIN° medido a Turns.WINDOW m de cada lado en un cruce — [isJunction] — o
 * > Turns.BEND° en otra parte; de giros a < Turns.MERGE m queda el más marcado) y la llegada.
 */
fun makeSteps(
    points: List<NavPoint>,
    t: NavText = { it },
    landmarks: List<Landmark> = emptyList(),
    name: String? = null,
    isJunction: (Double, Double) -> Boolean = { _, _ -> true },
): List<NavStep> {
    val cum = cumulative(points)
    val total = cum.last()
    class Candidate(val s: Double, val angle: Double, val at: NavPoint)
    val candidates = ArrayList<Candidate>()
    for (i in 1 until points.size - 1) {
        val s = cum[i]
        if (s < Turns.WINDOW * 0.8 || total - s < Turns.WINDOW * 0.8) continue
        val a = pointAlong(points, cum, s - Turns.WINDOW)
        val b = pointAlong(points, cum, s + Turns.WINDOW)
        val p = points[i]
        val inX = p.x - a.x; val inZ = p.z - a.z
        val outX = b.x - p.x; val outZ = b.z - p.z
        if (hypot(inX, inZ) < 1 || hypot(outX, outZ) < 1) continue
        val angle = turnAngle(inX, inZ, outX, outZ)
        if (abs(angle) > (if (isJunction(p.x, p.z)) Turns.MIN else Turns.BEND)) candidates += Candidate(s, angle, p)
    }
    // supresión de no máximos: queda el giro más marcado dentro de Turns.MERGE m
    val turns = ArrayList<Candidate>()
    for (c in candidates.sortedByDescending { abs(it.angle) }) {
        if (turns.all { abs(it.s - c.s) > Turns.MERGE }) turns += c
    }
    turns.sortBy { it.s }

    fun nearName(x: Double, z: Double): String? {
        var best: Landmark? = null
        var bestD = 0.0
        for (lm in landmarks) {
            val d = hypot(lm.x - x, lm.z - z)
            if (d <= Turns.LANDMARK && (best == null || d < bestD)) { best = lm; bestD = d }
        }
        return best?.name
    }
    val first = pointAlong(points, cum, min(total, Turns.WINDOW))
    val p0 = points[0]
    val hx = first.x - p0.x; val hz = first.z - p0.z
    val steps = ArrayList<NavStep>()
    steps += NavStep(Turn.STRAIGHT, 0.0, p0, dir = compassOf(if (hx != 0.0) hx else first.dx, if (hz != 0.0) hz else first.dz))
    for (u in turns) {
        // el mismo lugar no se repite en giros seguidos
        var near = nearName(u.at.x, u.at.z)
        if (near != null && steps.last().near == near) near = null
        steps += NavStep(
            if (u.angle > 0) Turn.RIGHT else Turn.LEFT, u.s, u.at,
            slight = abs(u.angle) < Turns.SLIGHT, near = near,
        )
    }
    steps += NavStep(Turn.ARRIVE, total, points.last(), near = name)
    return steps.mapIndexed { i, step ->
        val distance = if (i + 1 < steps.size) steps[i + 1].along - step.along else 0.0
        step.copy(distance = distance).let { it.copy(instruction = stepText(it, t)) }
    }
}

private val PLACEHOLDER = Regex("""\{(\w+)\}""")

/** Rellena los marcadores `{clave}` de una plantilla (los que faltan quedan vacíos). */
internal fun fill(template: String, vars: Map<String, String>): String =
    PLACEHOLDER.replace(template) { m -> vars[m.groupValues[1]] ?: "" }

/** Texto de un paso (llaves `nav_*`; marcadores `{dir}`, `{near}`, `{name}`, `{text}`). */
fun stepText(step: NavStep, t: NavText = { it }): String = when (step.turn) {
    Turn.STRAIGHT -> fill(t("nav_head"), mapOf("dir" to t("nav_dir_${step.dir}")))
    Turn.ARRIVE -> if (step.near != null) fill(t("nav_arrive_at"), mapOf("name" to step.near)) else t("nav_arrive")
    else -> {
        val base = t("nav_${if (step.slight) "slight_" else ""}${step.turn.key}")
        if (step.near != null) fill(t("nav_near"), mapOf("text" to base, "near" to step.near)) else base
    }
}
