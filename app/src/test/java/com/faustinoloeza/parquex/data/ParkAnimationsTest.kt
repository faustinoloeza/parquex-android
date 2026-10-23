package com.faustinoloeza.parquex.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** `animated.json` de Xcaret tal como lo exporta la web. */
class ParkAnimationsTest {

    private val xc = ParkAnimations.parse(JSONObject(File("src/main/assets/parks/xc/${ParkAnimations.FILE}").readText()))

    @Test fun readsEveryPieceOfXcaret() {
        val drivers = xc.pieces.groupingBy { it.driver::class.simpleName }.eachCount()
        assertEquals(1, drivers["TowerCabin"])
        assertEquals(2, drivers["Flag"]) // torre y bandera monumental
        assertEquals(1, drivers["Voladores"])
        assertEquals(2, drivers["NetDome"]) // mariposario y aviario
        assertTrue((drivers["Flock"] ?: 0) >= 1)
        assertTrue(xc.nightBirds < 1f && xc.nightPace < 1f)
    }

    @Test fun piecesNameTheirNodesAndBoundTheirMotion() {
        for (p in xc.pieces) {
            assertTrue(p.id, p.parts.isNotEmpty() && p.parts.none { it.startsWith("anim:") })
            assertTrue(p.id, p.box[3] > p.box[0] && p.box[4] > p.box[1] && p.box[5] > p.box[2])
            assertTrue(p.id, p.size > 0f)
        }
        val voladores = xc.pieces.first { it.driver is ParkAnimations.Voladores }
        assertEquals(5, voladores.parts.size) // caporal + 4 voladores
        val dome = xc.pieces.first { it.driver is ParkAnimations.NetDome }
        assertEquals(26, dome.copies)
    }
}
