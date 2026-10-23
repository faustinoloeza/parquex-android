package com.faustinoloeza.parquex.nav

import com.faustinoloeza.parquex.data.ParkData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Casos de `tests/shared/navigation.test.js` de la web, con los mismos números. */
class NavigationTest {

    private fun close(expected: Double, actual: Double, digits: Int = 2) {
        val tol = Math.pow(10.0, -digits.toDouble()) / 2
        assertTrue("esperado $expected ± $tol, fue $actual", abs(expected - actual) < tol)
    }

    // ---------------------------------------------------------------- findEntrance

    private fun poi(es: String, x: Number, z: Number, active: Boolean = true) =
        EntranceCandidate(es, x.toDouble(), z.toDouble(), active)

    private fun assertAt(x: Number, z: Number, e: Entrance) {
        assertEquals(x.toDouble(), e.x, 0.0)
        assertEquals(z.toDouble(), e.z, 0.0)
    }

    @Test fun prefersAnActivityNamedLikeTheEntrance() {
        assertAt(3, 3, findEntrance(listOf(poi("Delfines", 1, 1), poi("Plaza Principal", 2, 2), poi("Entrada", 3, 3))))
        assertAt(2, 2, findEntrance(listOf(poi("Delfines", 1, 1), poi("Plaza Principal", 2, 2))))
        // "Entrada a Ríos Subterráneos" es una actividad, no la entrada del parque
        assertAt(6, 6, findEntrance(listOf(poi("Entrada a Ríos Subterráneos", 5, 5), poi("Taquillas", 6, 6))))
    }

    @Test fun usesTheOverrideThenTheFirstActivityThenTheCenter() {
        assertAt(9, 8, findEntrance(listOf(poi("Entrada", 3, 3)), override = Entrance(9.0, 8.0)))
        assertAt(4, 4, findEntrance(listOf(poi("Delfines", 1, 1, active = false), poi("Río", 4, 4))))
        assertEquals(Entrance(7.0, 6.0), findEntrance(emptyList(), center = pt(7, 6)))
    }

    @Test fun findsTheEntranceOfTheRealParks() {
        // por nombre (sin `entrance` de park.json), como la web sin `sources.entrance`
        assertEquals("Plaza Principal", findEntrance(loadPark("xc"), override = null).name)
        assertTrue(findEntrance(loadPark("xs"), override = null).name!!.startsWith("Ingreso"))
        assertEquals("Entrada", findEntrance(loadPark("xo"), override = null).name)
        assertEquals("Entrada", findEntrance(loadPark("xp"), override = null).name)
        // con `entrance` de park.json (la que usa la app): Xel-Há en la plaza de llegada
        assertAt(140, -14, findEntrance(loadPark("xh")))
    }

    // ---------------------------------------------------------------- chooseStart

    private val area = ParkData.DesignArea(0f, 0f, FloatArray(36) { 500f })
    private val signed: (Double, Double) -> Double = { x, z -> area.signedDistance(x.toFloat(), z.toFloat()).toDouble() }
    private val entrance = pt(10, 20)

    @Test fun usesALocationInsideTheParkOrNearIt() {
        val s = chooseStart(Fix(100.0, 100.0, accuracy = 8.0), signed, entrance)
        assertEquals(100.0, s.x, 0.0)
        assertFalse(s.simulated)
        assertEquals(StartReason.GPS, s.reason)
        assertEquals(8.0, s.accuracy!!, 0.0)
        assertFalse(chooseStart(Fix(0.0, 790.0), signed, entrance).simulated) // 290 m fuera
    }

    @Test fun simulatesAtTheEntranceWhenFarAwayDeniedOrUnavailable() {
        assertEquals(Start(10.0, 20.0, simulated = true, reason = StartReason.FAR), chooseStart(Fix(0.0, 810.0), signed, entrance))
        assertEquals(StartReason.DENIED, chooseStart(null, signed, entrance, error = StartReason.DENIED).reason)
        assertEquals(StartReason.UNAVAILABLE, chooseStart(null, signed, entrance, error = StartReason.UNAVAILABLE).reason)
        assertEquals(StartReason.WAITING, chooseStart(null, signed, entrance).reason)
    }

    @Test fun aPointChosenOnTheMapWins() {
        val s = chooseStart(Fix(1.0, 1.0), signed, entrance, manual = pt(5, 6))
        assertEquals(5.0, s.x, 0.0)
        assertEquals(6.0, s.z, 0.0)
        assertTrue(s.simulated)
        assertEquals(StartReason.MANUAL, s.reason)
    }

    // ---------------------------------------------------------------- guidance

    // norte 100 m, derecha (este) 80 m, izquierda (norte) 60 m; ramales laterales hacen cruces de las dos esquinas
    private val g = buildGraph(
        listOf(line(0 to 0, 0 to -100, 80 to -100, 80 to -160), line(0 to -100, -50 to -100), line(80 to -100, 150 to -100)),
    )
    private val r = prepareRoute(route(g, pt(0, 0), pt(80, -160), toName = "Capilla", t = es))

    @Test fun advancesTheStepsAsTheWalkerMoves() {
        fun at(s: Double) = guidance(r, walkerAt(r, s).point)
        assertEquals(1, at(0.0).next)
        assertFalse(at(0.0).offRoute)
        assertFalse(at(0.0).arrived)
        close(100.0, at(0.0).toNext, 6)
        close(40.0, at(60.0).toNext, 6)
        assertEquals("En 40 m gira a la derecha", bannerText(r, at(60.0), es))
        assertEquals("Gira a la derecha", bannerText(r, at(95.0), es))
        assertEquals(2, at(110.0).next)
        assertEquals("En 70 m gira a la izquierda", bannerText(r, at(110.0), es))
        assertEquals(3, at(200.0).next)
        assertEquals("En 40 m llegas a Capilla", bannerText(r, at(200.0), es))
    }

    @Test fun detectsArrivalWithin12mOfTheDestination() {
        assertTrue(guidance(r, pt(80, -150)).arrived)
        assertFalse(guidance(r, pt(80, -140)).arrived)
        assertEquals("Has llegado", bannerText(r, guidance(r, pt(80, -155)), es))
    }

    @Test fun detectsLeavingTheRouteByMoreThan25m() {
        assertFalse(guidance(r, pt(20, -50)).offRoute)
        assertTrue(guidance(r, pt(30, -50)).offRoute)
        assertEquals(25.0, Guidance.OFF_ROUTE, 0.0)
    }

    @Test fun keepsThePositionNearTheLastOneWhereTheRouteComesBackOnItself() {
        // ida y vuelta por la misma línea: 0 → 100 → 0 (+ un pequeño desvío)
        val back = PreparedRoute(
            points = listOf(pt(0, 0), pt(100, 0), pt(100, 10), pt(0, 10)),
            steps = listOf(NavStep(Turn.STRAIGHT, 0.0, pt(0, 0)), NavStep(Turn.ARRIVE, 210.0, pt(0, 10))),
        )
        close(20.0, locateOnRoute(back, 20.0, 6.0, 15.0).along, 6) // de ida
        close(190.0, locateOnRoute(back, 20.0, 6.0, 180.0).along, 6) // de vuelta
    }

    // ---------------------------------------------------------------- formato

    @Test fun walkingTimeAt45KmH() {
        assertEquals(1, walkMinutes(75.0))
        assertEquals(10, walkMinutes(750.0))
        assertEquals(1, walkMinutes(10.0))
    }

    @Test fun distancesLikeAWalkingApp() {
        assertEquals("40 m", formatDistance(42.0))
        assertEquals("5 m", formatDistance(3.0))
        assertEquals("350 m", formatDistance(347.0))
        assertEquals("1,2 km", formatDistance(1234.0))
        assertEquals("1.2 km", formatDistance(1234.0, "en"))
    }
}
