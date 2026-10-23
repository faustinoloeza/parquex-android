package com.faustinoloeza.parquex.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Casos de `tests/shared/nav-graph.test.js` de la web, con los mismos números. */
class NavGraphTest {

    /** `toBeCloseTo(expected, digits)` de vitest: |a − b| < 10^−digits / 2. */
    private fun close(expected: Double, actual: Double, digits: Int = 2) {
        val tol = Math.pow(10.0, -digits.toDouble()) / 2
        assertTrue("esperado $expected ± $tol, fue $actual", abs(expected - actual) < tol)
    }

    // ---------------------------------------------------------------- buildGraph

    @Test fun joinsWalkwaysAtExactlyEqualCoordinates() {
        val g = buildGraph(listOf(line(0 to 0, 100 to 0), line(100 to 0, 100 to 100), line(100 to 0, 200 to 0)))
        assertEquals(4, g.stats.nodes)
        assertEquals(3, g.stats.edges)
        assertEquals(1, g.stats.components)
    }

    @Test fun snapsALooseEndOntoTheMiddleOfAnotherLine() {
        val g = buildGraph(listOf(line(0 to 0, 100 to 0), line(50 to 3, 50 to 80)))
        assertEquals(1, g.stats.components)
        // la primera línea se partió en (50, 0)
        assertTrue(g.x.indices.any { g.x[it] == 50.0 && g.z[it] == 0.0 })
        val r = route(g, pt(0, 0), pt(50, 80))
        close(50.0 + 3 + 77, r.length, 0)
    }

    @Test fun tiesOfficialRouteLinesToTheWalkwaysTheyRunAlong() {
        val g = buildGraph(listOf(line(0 to 0, 0 to 200)), listOf(line(3 to 0, 3 to 100, 150 to 100)))
        assertEquals(1, g.stats.components)
        assertTrue(g.edges.any { it.kind == EdgeKind.LINK })
        // del extremo lejano del andador al final de la línea de ruta
        val r = route(g, pt(0, 200), pt(150, 100))
        assertFalse(r.direct)
        assertTrue(r.length < 100 + 3 + 150 + 10)
    }

    @Test fun bridgesSeparatePiecesCloserThan30mNotFartherOnes() {
        fun pieces(gap: Int) = buildGraph(listOf(line(0 to 0, 100 to 0), line(100 + gap to 0, 200 + gap to 0)))
        assertEquals(1, pieces(20).stats.components)
        assertTrue(pieces(20).edges.any { it.kind == EdgeKind.BRIDGE })
        assertEquals(2, pieces(50).stats.components)
    }

    @Test fun tiesADeadEndToAWalkwayJustBeyondItWhenTheWayAroundIsLong() {
        // una U: la punta de un brazo se queda a 15 m del otro brazo
        val g = buildGraph(listOf(line(0 to 0, 0 to 200, 100 to 200, 100 to 15), line(100 to 0, 0 to 0)))
        val r = route(g, pt(100, 15), pt(100, 0))
        assertTrue(r.length < 40)
    }

    @Test fun buildsTheNetworksOfTheRealParksQuicklyInFewPieces() {
        for (key in listOf("xc", "xh")) {
            val park = loadPark(key)
            val t0 = System.nanoTime()
            val g = buildGraph(park)
            val ms = (System.nanoTime() - t0) / 1e6
            assertTrue("$key: $ms ms", ms < 500)
            assertTrue(g.stats.nodes > 500)
            val main = g.compSize.max()
            assertTrue(main.toDouble() / g.stats.nodes > 0.9)
            // casi todas las actividades quedan al alcance de un conector
            val pois = park.pois.filter { it.active }
            val near = pois.filter { nearestPoint(g, it.x.toDouble(), it.z.toDouble(), max = 60.0) != null }
            assertTrue(near.size.toDouble() / pois.size > 0.9)
        }
    }

    // ---------------------------------------------------------------- route

    /** Cuadrícula de calles cada 100 m, 3 × 3 manzanas. */
    private val grid = buildGraph(
        (0..3).flatMap { i ->
            listOf(
                line(i * 100 to 0, i * 100 to 100, i * 100 to 200, i * 100 to 300),
                line(0 to i * 100, 100 to i * 100, 200 to i * 100, 300 to i * 100),
            )
        },
    )

    @Test fun findsTheShortestPathManhattanDistanceOnAGrid() {
        val r = route(grid, pt(0, 0), pt(300, 200))
        close(500.0, r.length, 3)
        assertEquals(pt(0, 0), r.points.first())
        assertEquals(pt(300, 200), r.points.last())
    }

    @Test fun addsConnectorsFromOffNetworkPoints() {
        val r = route(grid, pt(50, -20), pt(250, 330))
        close(20.0, r.gaps[0], 5)
        close(30.0, r.gaps[1], 5)
        assertEquals(pt(50, 0), r.points[1])
    }

    @Test fun routesAlongASingleEdgeWhenBothEndsProjectOntoIt() {
        val r = route(grid, pt(10, 2), pt(80, -2))
        assertFalse(r.direct)
        close(2.0 + 70 + 2, r.length, 3)
        assertEquals(4, r.points.size)
        // caminar recto es más corto que ir a la red y volver
        assertTrue(route(grid, pt(40, 45), pt(60, 55)).direct)
        val r3 = route(grid, pt(10, 5), pt(290, 5))
        assertFalse(r3.direct)
        close(5.0 + 280 + 5, r3.length, 3)
    }

    @Test fun fallsBackToAStraightLineWithoutANetwork() {
        val r = route(buildGraph(emptyList()), pt(0, 0), pt(30, 40))
        assertTrue(r.direct)
        close(50.0, r.length, 6)
        assertEquals(Turn.ARRIVE, r.steps.last().turn)
    }

    @Test fun prefersOfficialRoutesSlightlyOverPlainWalkways() {
        // dos caminos paralelos del mismo largo; gana el de la ruta oficial
        val h = buildGraph(
            listOf(line(0 to 0, 0 to 50, 200 to 50, 200 to 0)),
            listOf(line(0 to 0, 0 to -50, 200 to -50, 200 to 0)),
        )
        val r = route(h, pt(0, 0), pt(200, 0))
        assertTrue(r.points.any { it.z == -50.0 })
    }

    // ---------------------------------------------------------------- indicaciones

    @Test fun measuresTurnsRightIsClockwiseSeenFromAboveWithNorthUp() {
        // norte (−z) y luego este (+x)
        close(90.0, turnAngle(0.0, -1.0, 1.0, 0.0), 6)
        close(-90.0, turnAngle(0.0, -1.0, -1.0, 0.0), 6)
        assertEquals("n", compassOf(0.0, -1.0))
        assertEquals("se", compassOf(1.0, 1.0))
    }

    @Test fun headsOffTurnsAndArrives() {
        val steps = makeSteps(listOf(pt(0, 0), pt(0, -100), pt(80, -100), pt(80, -160)), t = es, name = "Capilla")
        assertEquals(listOf(Turn.STRAIGHT, Turn.RIGHT, Turn.LEFT, Turn.ARRIVE), steps.map { it.turn })
        assertEquals("Camina hacia el norte", steps[0].instruction)
        close(100.0, steps[0].distance, 6)
        assertEquals("Gira a la derecha", steps[1].instruction)
        close(80.0, steps[1].distance, 6)
        assertEquals("Llegas a Capilla", steps[3].instruction)
        close(240.0, steps[3].along, 6)
    }

    @Test fun ignoresGentleBendsAndMergesZigZagsIntoOneTurn() {
        val bends = makeSteps(listOf(pt(0, 0), pt(0, -100), pt(10, -200))) // curva de ≈ 6°
        assertEquals(listOf(Turn.STRAIGHT, Turn.ARRIVE), bends.map { it.turn })
        // una esquina cortada en dos vértices de 45° a 5 m: un solo giro a la derecha
        val corner = makeSteps(listOf(pt(0, 0), pt(0, -100), pt(4, -104), pt(100, -104)))
        assertEquals(listOf(Turn.STRAIGHT, Turn.RIGHT, Turn.ARRIVE), corner.map { it.turn })
    }

    @Test fun flagsSlightTurnsAndMentionsANearbyLandmark() {
        val steps = makeSteps(listOf(pt(0, 0), pt(0, -100), pt(40, -170), pt(40, -170)), t = es) // ≈ 30°: sin giro
        assertEquals(2, steps.size)
        val slight = makeSteps(
            listOf(pt(0, 0), pt(0, -100), pt(50, -170)), // ≈ 35.5°
            t = en, landmarks = listOf(Landmark("Capilla de Guadalupe", 10.0, -105.0)),
        )
        assertEquals(Turn.RIGHT, slight[1].turn)
        assertTrue(slight[1].slight)
        assertEquals("Capilla de Guadalupe", slight[1].near)
        assertEquals("Turn slightly right near Capilla de Guadalupe", slight[1].instruction)
    }

    @Test fun turnsOnARealParkRouteAreConsistent() {
        val park = loadPark("xc")
        val g = buildGraph(park)
        val pois = park.pois.filter { it.active }
        val r = route(g, pt(pois[0].x, pois[0].z), pt(pois[40].x, pois[40].z), toName = pois[40].name, t = es)
        assertEquals(Turn.STRAIGHT, r.steps[0].turn)
        assertEquals(Turn.ARRIVE, r.steps.last().turn)
        for (i in 1 until r.steps.size) assertTrue(r.steps[i].along >= r.steps[i - 1].along)
        close(r.length, r.steps.sumOf { it.distance }, 3)
    }

    // ---------------------------------------------------------------- pruneSpikes

    @Test fun cutsAnOutAndBackSpikeBetweenParallelLines() {
        val pts = listOf(pt(0, 0), pt(50, 0), pt(80, 0), pt(80, 4), pt(52, 4), pt(50, 30))
        assertEquals(listOf(pt(0, 0), pt(50, 0), pt(52, 4), pt(50, 30)), pruneSpikes(pts))
    }

    @Test fun keepsAnOrdinaryPath() {
        val pts = listOf(pt(0, 0), pt(0, 50), pt(50, 50), pt(50, 0))
        assertEquals(pts, pruneSpikes(pts))
    }

    // ---------------------------------------------------------------- cruces

    @Test fun onlyAnnouncesBendsOfASinglePathWhenTheyAreSharp() {
        // un andador que dobla 90° (sin cruce), y la misma esquina con un ramal
        val bend = buildGraph(listOf(line(0 to 0, 0 to -100, 100 to -100)))
        assertEquals(listOf(Turn.STRAIGHT, Turn.ARRIVE), route(bend, pt(0, 0), pt(100, -100)).steps.map { it.turn })
        val crossing = buildGraph(listOf(line(0 to 0, 0 to -100, 100 to -100), line(0 to -100, 0 to -200)))
        assertEquals(
            listOf(Turn.STRAIGHT, Turn.RIGHT, Turn.ARRIVE),
            route(crossing, pt(0, 0), pt(100, -100)).steps.map { it.turn },
        )
    }
}
