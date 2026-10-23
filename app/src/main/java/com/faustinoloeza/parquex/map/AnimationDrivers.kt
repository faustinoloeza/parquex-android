package com.faustinoloeza.parquex.map

import com.faustinoloeza.parquex.data.ParkAnimations
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Port 1:1 de los `update()` de la web que mueven las piezas de `animated.json` (Kotlin puro, sin Filament):
 * `scenic-tower.js`, `voladores.js`, `parts/flag.js`, `net-dome.js`, `layers/fauna.js` y el aleteo de
 * `graphics/shaders/flap.js`. Si cambia una fórmula en la web, cambiarla aquí (y en `AnimationDriversTest`).
 *
 * Matrices afines 3×4 por columnas desde un desplazamiento `o`: eje x (o..o+2), eje y, eje z y traslación
 * (o+9..o+11); dan la matriz local completa de la parte en el marco de su landmark, como la pondría three.js
 * (posición · rotación · escala). El tiempo `t` va en segundos (Double: precisión en sesiones largas).
 */
internal object AnimationDrivers {
    const val MATRIX = 12

    /** T(x, y, z) · R_y(angle) · S(scale) en [m] desde [o] (R_y como `makeRotationY` de three.js). */
    fun translateRotateY(m: FloatArray, o: Int, x: Float, y: Float, z: Float, angle: Double, scale: Float = 1f) {
        val c = cos(angle).toFloat() * scale
        val s = sin(angle).toFloat() * scale
        m[o] = c; m[o + 1] = 0f; m[o + 2] = -s
        m[o + 3] = 0f; m[o + 4] = scale; m[o + 5] = 0f
        m[o + 6] = s; m[o + 7] = 0f; m[o + 8] = c
        m[o + 9] = x; m[o + 10] = y; m[o + 11] = z
    }

    /** Cabina de la Torre Escénica: sube y baja en 70 s y gira 0.12 rad/s. */
    fun towerCabin(t: Double, height: Float, m: FloatArray) {
        val phase = 0.5 - 0.5 * cos(t / 70.0 * PI * 2)
        translateRotateY(m, 0, 0f, (5 + phase * (height - 18)).toFloat(), 0f, t * 0.12)
    }

    /**
     * Voladores: el caporal gira arriba ([m] desde 0) y los 4 voladores, cabeza abajo, bajan en espiral en 80 %
     * de un ciclo de 48 s y suben en el resto ([m] desde 12·(i+1)). [ropes]: por cuerda, x, y, z de su
     * extremo de arriba y de abajo (24 floats).
     */
    fun voladores(t: Double, height: Float, m: FloatArray, ropes: FloatArray) {
        val cycle = (t % 48.0) / 48.0
        val descend = if (cycle < 0.8) cycle / 0.8 else 1 - (cycle - 0.8) / 0.2
        val len = 1 + descend * (height - 3.5)
        val r = 1.2 + descend * 7.5
        translateRotateY(m, 0, 0f, height + 0.9f, 0f, t * 0.9)
        for (i in 0 until 4) {
            val a = t * 0.9 + i * PI / 2
            val x = (cos(a) * r).toFloat()
            val y = (height - 1 - len).toFloat()
            val z = (sin(a) * r).toFloat()
            // T · R_y(−a) · R_z(π) (rotation.z = π del modelo): R_z(π) niega los ejes x e y.
            val o = (i + 1) * MATRIX
            translateRotateY(m, o, x, y, z, -a)
            for (k in 0 until 6) m[o + k] = -m[o + k]
            val q = i * 6
            ropes[q] = (cos(a) * 0.9).toFloat(); ropes[q + 1] = height - 1; ropes[q + 2] = (sin(a) * 0.9).toFloat()
            ropes[q + 3] = x; ropes[q + 4] = y + 0.6f; ropes[q + 5] = z
        }
    }

    /** Tela de la bandera colgada en [at], con el vaivén del asta: T(at) · R_y(0.35 + 0.3·sin 0.21t). */
    fun flag(t: Double, at: FloatArray, m: FloatArray) =
        translateRotateY(m, 0, at[0], at[1], at[2], 0.35 + sin(t * 0.21) * 0.3)

    /**
     * Ola de la tela (`createFlagCloth().update`): vértice de reposo (x, y, 0) del plano de [width] × [height]
     * → posición en [out] desde [o]. El lado del asta (x = 0) no se mueve.
     */
    fun cloth(t: Double, x: Float, y: Float, width: Float, height: Float, speed: Float, out: FloatArray, o: Int) {
        val w = x / width
        val wave = sin(x * (7.0 / width) - t * speed + y * (1.2 / height)) * 0.13 * width +
            sin(x * (15.0 / width) - t * speed * 1.7 + 1.3) * 0.035 * width
        out[o] = x - w * w * 0.04f * width
        out[o + 1] = (y - w * w * 0.08f * height + sin(t * 1.3 + w * 3) * 0.01 * height * w).toFloat()
        out[o + 2] = (wave * w).toFloat()
    }

    /** Mariposa o ave [fly] de un domo de [rx] × [rz], con el reloj de vuelo [pace]. */
    fun netDomeFly(pace: Double, fly: ParkAnimations.Fly, rx: Float, rz: Float, scale: Float, m: FloatArray, o: Int) {
        val a = fly.a + pace * fly.s
        translateRotateY(
            m, o,
            (cos(a) * rx * fly.rr).toFloat(), (fly.h + sin(pace * 1.7 + fly.a) * 1.2).toFloat(), (sin(a * 1.3) * rz * fly.rr).toFloat(),
            -a, scale,
        )
    }

    /** Ave [bird] de una bandada que vuela en círculo alrededor de ([x], [z]), con el reloj de vuelo [pace]. */
    fun flockBird(pace: Double, bird: ParkAnimations.Bird, x: Float, z: Float, scale: Float, m: FloatArray, o: Int) {
        val a = bird.phase + pace * bird.speed
        translateRotateY(
            m, o,
            (x + cos(a) * bird.rr).toFloat(), (bird.hh + sin(pace * 0.7 + bird.phase) * 3).toFloat(), (z + sin(a) * bird.rr * 0.8).toFloat(),
            atan2(-cos(a) * 0.8, -sin(a)), scale,
        )
    }

    /** Ángulo de aleteo de la copia [i] (tiempo real: las alas no aletean más lento de noche). */
    fun flapAngle(t: Double, i: Int, flap: ParkAnimations.Flap): Float = (sin(t * flap.speed + i * 1.731) * flap.amp).toFloat()

    /**
     * Aleteo de un vértice o normal ([v] desde [o], en el sitio): si el vértice ([px], [py], [pz]) está a más de
     * `threshold` del cuerpo sobre el eje del ala, gira sign(lado)·[angle] en el plano (eje, y).
     */
    fun flap(v: FloatArray, o: Int, px: Float, pz: Float, angle: Float, flap: ParkAnimations.Flap) {
        val side = if (flap.axisX) px else pz
        if (kotlin.math.abs(side) <= flap.threshold) return
        val a = if (side > 0) angle else -angle
        val c = cos(a); val s = sin(a)
        val k = if (flap.axisX) o else o + 2
        val p = v[k]; val q = v[o + 1]
        v[k] = p * c - q * s
        v[o + 1] = p * s + q * c
    }

    /** Actividad de noche (`nightActivity` de la web): 1 de día … [f] con k = 1. */
    fun activity(f: Float, k: Float) = 1f + (f - 1f) * k

    /** Copias despiertas de una bandada o enjambre de [n] (`mesh.count` de la web). */
    fun awake(n: Int, nightBirds: Float, k: Float) = max(1, (n * activity(nightBirds, k)).roundToInt())
}
