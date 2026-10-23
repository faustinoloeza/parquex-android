package com.faustinoloeza.parquex.map

import com.faustinoloeza.parquex.data.ParkAnimations
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Los drivers contra los `update()` reales de la web: matrices locales (por columnas, 3×4) y vértices que
 * three.js dio para las mismas entradas (`scenic-tower.js`, `voladores.js`, `parts/flag.js`, `net-dome.js`,
 * `layers/fauna.js`), redondeados a 5 decimales.
 */
class AnimationDriversTest {

    private fun assertClose(expected: FloatArray, actual: FloatArray, message: String = "") =
        assertArrayEquals(message, expected, actual, 2e-4f)

    @Test fun towerCabinClimbsAndTurns() {
        val m = FloatArray(12)
        val cases = mapOf(
            0.0 to floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 5f, 0f),
            35.0 to floatArrayOf(-0.49026f, 0f, 0.87158f, 0f, 1f, 0f, -0.87158f, 0f, -0.49026f, 0f, 67f, 0f),
            70.5 to floatArrayOf(-0.5696f, 0f, -0.82192f, 0f, 1f, 0f, 0.82192f, 0f, -0.5696f, 0f, 5.03121f, 0f),
        )
        for ((t, expected) in cases) {
            AnimationDrivers.towerCabin(t, 80f, m)
            assertClose(expected, m, "t = $t")
        }
    }

    @Test fun voladoresSpinDownHeadFirstOnTheirRopes() {
        val m = FloatArray(5 * 12)
        val ropes = FloatArray(24)
        AnimationDrivers.voladores(0.0, 30f, m, ropes)
        assertClose(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 30.9f, 0f), m.copyOfRange(0, 12), "caporal")
        assertClose(floatArrayOf(-1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 1f, 1.2f, 28f, 0f), m.copyOfRange(12, 24), "volador 0")
        assertClose(floatArrayOf(0f, 0f, -1f, 0f, -1f, 0f, -1f, 0f, 0f, 0f, 28f, 1.2f), m.copyOfRange(24, 36), "volador 1")

        AnimationDrivers.voladores(20.0, 30f, m, ropes)
        assertClose(
            floatArrayOf(
                0.66032f, 0f, 0.75099f, 0f, 1f, 0f, -0.75099f, 0f, 0.66032f, 0f, 30.9f, 0f,
                -0.66032f, 0f, 0.75099f, 0f, -1f, 0f, 0.75099f, 0f, 0.66032f, 3.37174f, 14.19792f, -3.83473f,
                -0.75099f, 0f, -0.66032f, 0f, -1f, 0f, -0.66032f, 0f, 0.75099f, 3.83473f, 14.19792f, 3.37174f,
                0.66032f, 0f, -0.75099f, 0f, -1f, 0f, -0.75099f, 0f, -0.66032f, -3.37174f, 14.19792f, 3.83473f,
                0.75099f, 0f, 0.66032f, 0f, -1f, 0f, 0.66032f, 0f, -0.75099f, -3.83473f, 14.19792f, -3.37174f,
            ),
            m, "t = 20",
        )
        // las cuerdas de la web (LineSegments): arriba y abajo de cada una
        assertClose(
            floatArrayOf(
                0.59429f, 29f, -0.67589f, 3.37174f, 14.79792f, -3.83473f,
                0.67589f, 29f, 0.59429f, 3.83473f, 14.79792f, 3.37174f,
                -0.59429f, 29f, 0.67589f, -3.37174f, 14.79792f, 3.83473f,
                -0.67589f, 29f, -0.59429f, -3.83473f, 14.79792f, -3.37174f,
            ),
            ropes, "cuerdas",
        )

        AnimationDrivers.voladores(40.0, 30f, m, ropes) // subiendo de nuevo (después del 80 % del ciclo)
        assertClose(floatArrayOf(0.12796f, 0f, 0.99178f, 0f, -1f, 0f, 0.99178f, 0f, -0.12796f, -0.95333f, 5.91667f, -7.38875f),
            m.copyOfRange(12, 24), "t = 40")
    }

    @Test fun flagWavesFromTheHoist() {
        val width = 6.5f; val height = 3.7142857f; val speed = 3.2f; val t = 1.3
        val rest = listOf(0f to 0f, 3.25f to 0f, 6.5f to 0f, 3.48214f to -1.85714f, 6.5f to -3.71429f)
        val expected = listOf(
            floatArrayOf(0f, 0f, 0f),
            floatArrayOf(3.185f, -0.07518f, -0.14669f),
            floatArrayOf(6.24f, -0.33428f, 0.29548f),
            floatArrayOf(3.40753f, -1.9455f, -0.28957f),
            floatArrayOf(6.24f, -4.04856f, 0.88746f),
        )
        val out = FloatArray(3)
        for ((i, xy) in rest.withIndex()) {
            AnimationDrivers.cloth(t, xy.first, xy.second, width, height, speed, out, 0)
            assertClose(expected[i], out, "vértice $i")
        }
        val m = FloatArray(12)
        AnimationDrivers.flag(t, floatArrayOf(0.1f, 93.2f, 0f), m)
        val a = 0.430886
        assertClose(floatArrayOf(cos(a).toFloat(), 0f, -sin(a).toFloat(), 0f, 1f, 0f, sin(a).toFloat(), 0f, cos(a).toFloat(), 0.1f, 93.2f, 0f), m)
    }

    @Test fun butterfliesFlyAroundTheDome() {
        val flies = listOf(
            ParkAnimations.Fly(0.0002457479947459642f, 0.39730668221474935f, 9.469055465361595f, 0.39061292875121023f, 0xff8c1a),
            ParkAnimations.Fly(4.168891254742114f, 0.22843877949213554f, 4.258141578295334f, 0.4563103298220366f, 0x2f7de1),
            ParkAnimations.Fly(2.4899071432696225f, 0.4020393439112414f, 10.808099719513253f, 0.5881032092208524f, 0xf5d020),
        )
        val expected = listOf(
            floatArrayOf(1.59714f, 0f, 0.09569f, 0f, 1.6f, 0f, -0.09569f, 0f, 1.59714f, 11.69741f, 9.77204f, 0.66785f),
            floatArrayOf(-0.78001f, 0f, -1.39699f, 0f, 1.6f, 0f, 1.39699f, 0f, -0.78001f, -6.67363f, 3.10773f, -7.33355f),
            floatArrayOf(-1.32828f, 0f, 0.89201f, 0f, 1.6f, 0f, -0.89201f, 0f, -1.32828f, -14.64681f, 11.27174f, -2.23589f),
        )
        val m = FloatArray(12)
        for ((i, fly) in flies.withIndex()) {
            AnimationDrivers.netDomeFly(0.15, fly, 30f, 22f, 1.6f, m, 0)
            assertClose(expected[i], m, "mariposa $i")
        }
    }

    @Test fun birdsCircleTheirFlock() {
        val birds = listOf(
            ParkAnimations.Bird(0f, 0.18000187832862224f, 25.12458710201298f, 24.134527732680798f),
            ParkAnimations.Bird(4.71238898038469f, 0.2443262950257986f, 21.707087254574095f, 26.46599292963091f),
        )
        val expected = listOf(
            floatArrayOf(-0.06296f, 0f, 1.39858f, 0f, 1.4f, 0f, -1.39858f, 0f, -0.06296f, 35.10831f, 24.55316f, -19.27656f),
            floatArrayOf(1.39893f, 0f, 0.05473f, 0f, 1.4f, 0f, -0.05473f, 0f, 1.39893f, 11.0603f, 23.49534f, -37.34494f),
        )
        val m = FloatArray(12)
        for ((i, bird) in birds.withIndex()) {
            AnimationDrivers.flockBird(0.2, bird, 10f, -20f, 1.4f, m, 0)
            assertClose(expected[i], m, "ave $i")
        }
    }

    @Test fun wingsFlapOutsideTheBody() {
        val flap = ParkAnimations.Flap(axisX = true, speed = 14f, amp = 0.9f, threshold = 0.04f)
        val angle = AnimationDrivers.flapAngle(0.5, 2, flap)
        assertEquals((sin(0.5 * 14 + 2 * 1.731) * 0.9).toFloat(), angle, 1e-6f)
        // ala derecha (x > 0) sube con +ángulo, la izquierda con −ángulo; el cuerpo no se mueve
        val right = floatArrayOf(0.95f, 0f, -0.45f)
        AnimationDrivers.flap(right, 0, 0.95f, -0.45f, angle, flap)
        assertClose(floatArrayOf(0.95f * cos(angle), 0.95f * sin(angle), -0.45f), right)
        val left = floatArrayOf(-0.6f, 0f, 0.35f)
        AnimationDrivers.flap(left, 0, -0.6f, 0.35f, angle, flap)
        assertClose(floatArrayOf(-0.6f * cos(-angle), -0.6f * sin(-angle), 0.35f), left)
        val body = floatArrayOf(0.03f, 0f, -0.3f)
        AnimationDrivers.flap(body, 0, 0.03f, -0.3f, angle, flap)
        assertClose(floatArrayOf(0.03f, 0f, -0.3f), body)
    }

    @Test fun mostBirdsRestAtNight() {
        assertEquals(26, AnimationDrivers.awake(26, 0.15f, 0f))
        assertEquals(4, AnimationDrivers.awake(26, 0.15f, 1f)) // round(26 · 0.15) = 4
        assertEquals(1, AnimationDrivers.awake(3, 0.15f, 1f))
        assertEquals(0.12f, AnimationDrivers.activity(0.12f, 1f), 1e-6f)
    }
}
