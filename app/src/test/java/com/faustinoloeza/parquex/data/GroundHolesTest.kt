package com.faustinoloeza.parquex.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `park.json → groundHoles` y [ParkData.surfaceAt], con los casos de `tests/shared/ground-holes.test.js` de la web. */
class GroundHolesTest {

    private fun square(x0: Int, z0: Int, x1: Int, z1: Int) = "[[$x0,$z0],[$x1,$z0],[$x1,$z1],[$x0,$z1]]"

    private fun park(holes: String) = ParkData.parse(
        JSONObject(
            """{"key":"t","center":{"lat":20.58,"lng":-87.12},"bounds":{"x0":-50,"x1":50,"z0":-50,"z1":50},
               "extent":{"cx":0,"cz":0,"size":100},"groundHoles":$holes}""",
        ),
    )

    // Un foso: cuadrado de 20 m con una isla de 10 m en medio (par-impar), con puente a y = 4.
    private val moat = park("""[{"rings":[${square(0, 0, 20, 20)},${square(5, 5, 15, 15)}],"y":4}]""")

    @Test fun fillsRingsWithTheEvenOddRule() {
        assertEquals(4f, moat.deckAt(2f, 2f))
        assertEquals(4f, moat.deckAt(2f, 18f))
        assertNull(moat.deckAt(10f, 10f)) // la isla
        assertNull(moat.deckAt(25f, 10f))
    }

    @Test fun putsWhatCrossesAHoleOnItsBridge() {
        assertEquals(4f, moat.surfaceAt(2f, 2f))
        assertEquals(0f, moat.surfaceAt(10f, 10f))
        assertEquals(0f, park("[]").surfaceAt(2f, 2f))
    }

    @Test fun ignoresHolesWithoutABridge() {
        val pond = park("""[{"rings":[${square(0, 0, 20, 20)}]}]""")
        assertEquals(0, pond.groundHoles.size)
        assertEquals(0f, pond.surfaceAt(2f, 2f))
    }
}
