package com.faustinoloeza.parquex.map

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Cámara tipo mapa (equivalente a MapControls del proyecto web), independiente de Filament.
 *
 * Estado: punto objetivo en el suelo ([targetX], [targetZ]), [distance] al objetivo, [yaw]
 * (rumbo; 0 = norte arriba) y [pitch] (ángulo desde la vertical; 0 = vista cenital).
 * Coordenadas del parque: metros, x = este, z = sur, y = arriba.
 *
 * Todas las operaciones se hacen anclando un punto del suelo bajo el dedo, así el mapa "se pega"
 * al dedo también en vista oblicua. [snapshot] produce una copia inmutable para proyectar puntos
 * del mundo a pantalla (marcadores en Compose).
 */
class MapCamera {
    var targetX = 0f; private set
    var targetZ = 0f; private set
    /** Altura del plano de referencia del suelo (paneo, zoom y rayos). */
    var groundY = 0f
    var distance = 800f; private set
    var yaw = 0f; private set
    var pitch = HOME_PITCH; private set

    var fovDegrees = 40f
    var viewportWidth = 1; private set
    var viewportHeight = 1; private set

    // Límites (se ajustan al cargar el parque).
    var minX = -1000f; var maxX = 1000f
    var minZ = -1000f; var maxZ = 1000f
    var minDistance = 25f; var maxDistance = 4000f
    var maxPitch = 1.15f

    private var home = floatArrayOf(0f, 0f, 800f, 0f, HOME_PITCH)
    /** Rectángulo del parque (x0, x1, z0, z1) que encuadra la vista inicial; null antes de [fitPark]. */
    private var homeBounds: FloatArray? = null
    private var userMoved = false

    /** Se incrementa cada vez que cambia la vista; sirve para saber si hay que volver a dibujar. */
    var version = 0L; private set

    // Animación (vuelo) y paneo con inercia.
    private var anim: Anim? = null
    private var flingVx = 0f
    private var flingVz = 0f

    private class Anim(
        val from: FloatArray, val to: FloatArray, val start: Long, val durationNs: Long,
    )

    val isAnimating get() = anim != null || flingVx != 0f || flingVz != 0f

    fun setViewport(width: Int, height: Int) {
        if (width == viewportWidth && height == viewportHeight) return
        viewportWidth = max(1, width); viewportHeight = max(1, height)
        // El encuadre inicial depende de la proporción de la pantalla.
        homeBounds?.let { b ->
            home[2] = fitHome(b)
            if (!userMoved) setState(home)
        }
        changed()
    }

    /**
     * Ajusta límites y vista inicial a un rectángulo del parque (metros). La vista inicial es
     * oblicua (~54° de elevación, como la web) y encuadra todo el parque.
     *
     * Con [keepView] no mueve la cámara (la usa el cambio de parque vecino, que luego vuela con
     * [goHome]): los límites se amplían lo justo para contener la vista actual, así nada salta.
     */
    fun fitPark(x0: Float, x1: Float, z0: Float, z1: Float, groundY: Float = 0f, keepView: Boolean = false) {
        val w = x1 - x0; val d = z1 - z0
        val size = max(w, d)
        val margin = size * 0.15f
        minX = x0 - margin; maxX = x1 + margin
        minZ = z0 - margin; maxZ = z1 + margin
        this.groundY = groundY
        minDistance = 25f
        maxDistance = size * 2.2f
        val bounds = floatArrayOf(x0, x1, z0, z1)
        homeBounds = bounds
        home = floatArrayOf((x0 + x1) / 2f, (z0 + z1) / 2f, fitHome(bounds), 0f, HOME_PITCH)
        cancelMotion()
        if (keepView) {
            minX = min(minX, targetX); maxX = max(maxX, targetX)
            minZ = min(minZ, targetZ); maxZ = max(maxZ, targetZ)
            maxDistance = max(maxDistance, distance)
            changed()
            return
        }
        userMoved = false
        setState(home)
    }

    /**
     * Pasa el objetivo a otro marco de coordenadas (mismo punto del mundo, otro origen) sin
     * animación ni límites: la vista no cambia. Llamar justo antes de [fitPark] con keepView.
     */
    fun reframe(x: Float, z: Float) {
        cancelMotion()
        targetX = x; targetZ = z
        changed()
    }

    /**
     * Distancia de la vista inicial: el parque completo en pantalla. Sube [maxDistance] si hace falta
     * (en un teléfono vertical el ancho manda y 2.2 × el lado no alcanza), con 25 % de holgura.
     */
    private fun fitHome(b: FloatArray): Float {
        val d = fitDistance(b[0], b[1], b[2], b[3], 0f, HOME_PITCH, clamp = false)
        maxDistance = max(maxDistance, d * 1.25f)
        return d
    }

    /**
     * Distancia mínima para que el rectángulo [x0, x1] × [z0, z1] del suelo, visto desde su centro
     * con rumbo [yaw] e inclinación [pitch], quepa en pantalla dejando libres los lados ([FIT_X]) y
     * la barra superior y la fila de chips ([FIT_Y]).
     *
     * Exacto en perspectiva: las esquinas son los extremos de la proyección y, para cada esquina,
     * la condición |pantalla| ≤ margen es lineal en la distancia (ver [CameraSnapshot.project]).
     */
    private fun fitDistance(x0: Float, x1: Float, z0: Float, z1: Float, yaw: Float, pitch: Float, clamp: Boolean = true): Float {
        val cx = (x0 + x1) / 2f; val cz = (z0 + z1) / 2f
        val t = tan(Math.toRadians(fovDegrees / 2.0).toFloat())
        val kx = viewportHeight.toFloat() / viewportWidth / t // píxel horizontal normalizado por metro a 1 m
        val ky = 1f / t
        val sp = sin(pitch); val cp = cos(pitch)
        val sy = sin(yaw); val cy = cos(yaw)
        var d = minDistance
        for ((x, z) in arrayOf(x0 to z0, x1 to z0, x0 to z1, x1 to z1)) {
            val dx = x - cx; val dz = z - cz
            val a = dx * cy - dz * sy // a lo largo de "derecha" en pantalla
            val b = dx * sy + dz * cy // hacia la cámara (abajo en pantalla)
            d = max(d, sp * b + abs(a) * kx / FIT_X)
            d = max(d, sp * b + cp * abs(b) * ky / FIT_Y)
        }
        return if (clamp) d.coerceIn(minDistance, maxDistance) else d
    }

    // ---------------------------------------------------------------------------- gestos

    /** Paneo: mueve el mapa para que el punto del suelo bajo (fromX, fromY) quede bajo (toX, toY). */
    fun pan(fromX: Float, fromY: Float, toX: Float, toY: Float): Boolean {
        val a = groundAt(fromX, fromY) ?: return false
        val b = groundAt(toX, toY) ?: return false
        userMoved = true
        targetX += a[0] - b[0]
        targetZ += a[1] - b[1]
        clamp(); changed()
        return true
    }

    /**
     * Gesto de dos dedos: zoom ([scale] > 1 acerca), giro ([rotation] = cambio del ángulo entre
     * los dedos en pantalla, radianes) y desplazamiento del centroide, manteniendo el punto bajo el centroide fijo.
     */
    fun transform(prevCx: Float, prevCy: Float, cx: Float, cy: Float, scale: Float, rotation: Float) {
        userMoved = true
        val anchor = groundAt(prevCx, prevCy)
        distance = (distance / scale).coerceIn(minDistance, maxDistance)
        yaw = wrapAngle(yaw + rotation)
        if (anchor != null) {
            groundAt(cx, cy)?.let { now ->
                targetX += anchor[0] - now[0]
                targetZ += anchor[1] - now[1]
            }
        }
        clamp(); changed()
    }

    /** Inclinación con dos dedos en vertical: [dy] en píxeles (hacia arriba inclina más). */
    fun tilt(dy: Float) {
        userMoved = true
        pitch = (pitch - dy / viewportHeight * 2.2f).coerceIn(0f, maxPitch)
        changed()
    }

    fun startFling(vxPx: Float, vyPx: Float, atX: Float, atY: Float) {
        // Convierte la velocidad en pantalla a velocidad en el suelo (m/s) con dos rayos.
        val dt = 0.016f
        val a = groundAt(atX, atY) ?: return
        val b = groundAt(atX - vxPx * dt, atY - vyPx * dt) ?: return
        flingVx = (b[0] - a[0]) / dt
        flingVz = (b[1] - a[1]) / dt
        lastUpdate = 0L
        if (sqrt(flingVx * flingVx + flingVz * flingVz) < distance * 0.05f) { flingVx = 0f; flingVz = 0f }
    }

    fun cancelMotion() {
        anim = null; flingVx = 0f; flingVz = 0f
    }

    // ---------------------------------------------------------------------------- vuelos

    fun zoomBy(factor: Float, durationMs: Long = 300) =
        flyTo(distance = (distance * factor).coerceIn(minDistance, maxDistance), durationMs = durationMs)

    /** Doble toque: acerca hacia el punto tocado. */
    fun zoomInAt(sx: Float, sy: Float) {
        val p = groundAt(sx, sy) ?: return zoomBy(0.5f)
        flyTo(x = targetX + (p[0] - targetX) * 0.5f, z = targetZ + (p[1] - targetZ) * 0.5f,
            distance = (distance * 0.5f).coerceIn(minDistance, maxDistance))
    }

    fun resetNorth() = flyTo(yaw = 0f, durationMs = 400)

    /** Encuadra un rectángulo del suelo (metros) sin cambiar rumbo ni inclinación. */
    fun flyToBounds(x0: Float, x1: Float, z0: Float, z1: Float, durationMs: Long = 700) {
        // Al menos 80 m de lado y 10 % de margen, para no acercarse de más a un grupo pequeño.
        val hw = max((x1 - x0) / 2f, 40f) * 1.1f; val hd = max((z1 - z0) / 2f, 40f) * 1.1f
        val cx = (x0 + x1) / 2f; val cz = (z0 + z1) / 2f
        flyTo(x = cx, z = cz, distance = fitDistance(cx - hw, cx + hw, cz - hd, cz + hd, yaw, pitch), durationMs = durationMs)
    }

    /** Centra un punto del suelo acercando hasta [maxDistance] metros como mucho. */
    fun flyToPoint(x: Float, z: Float, maxDistance: Float = 320f) =
        flyTo(x = x, z = z, distance = min(distance, maxDistance).coerceIn(minDistance, this.maxDistance), durationMs = 700)

    /**
     * Seguimiento de navegación: acerca suavemente (tasa [rate] s⁻¹) el objetivo a ([x], [z]), el rumbo a
     * [yaw] (el sentido de la marcha arriba), la distancia a [dist] y la inclinación a [tilt]. Sin límites
     * de paneo: el caminante puede ir a cualquier parte del parque.
     */
    fun follow(x: Float, z: Float, yaw: Float, dist: Float, tilt: Float, dt: Float, rate: Float = 3f) {
        anim = null; flingVx = 0f; flingVz = 0f
        val a = 1f - exp(-rate * dt)
        targetX += (x - targetX) * a
        targetZ += (z - targetZ) * a
        this.yaw = wrapAngle(this.yaw + wrapAngle(yaw - this.yaw) * a)
        distance += (dist.coerceIn(minDistance, maxDistance) - distance) * a
        pitch += (tilt.coerceIn(0f, maxPitch) - pitch) * a
        userMoved = true
        changed()
    }

    /** true si (x, z) cae dentro de los límites de paneo (parque + margen). */
    fun contains(x: Float, z: Float) = x in minX..maxX && z in minZ..maxZ

    fun goHome() = flyTo(home[0], home[1], home[2], home[3], home[4], 700)

    fun flyTo(
        x: Float = targetX, z: Float = targetZ, distance: Float = this.distance,
        yaw: Float = this.yaw, pitch: Float = this.pitch, durationMs: Long = 500,
    ) {
        flingVx = 0f; flingVz = 0f
        userMoved = true
        // Gira por el camino corto.
        var dyaw = wrapAngle(yaw - this.yaw)
        if (abs(dyaw) < 1e-4f) dyaw = 0f
        anim = Anim(
            from = state(),
            to = floatArrayOf(x, z, distance, this.yaw + dyaw, pitch),
            start = -1L,
            durationNs = durationMs * 1_000_000,
        )
    }

    private var lastUpdate = 0L

    /** Avanza animaciones; devuelve true si la cámara sigue en movimiento. */
    fun update(frameTimeNanos: Long): Boolean {
        val dt = if (lastUpdate == 0L) 0.016f else ((frameTimeNanos - lastUpdate) / 1e9f).coerceIn(0f, 0.1f)
        lastUpdate = frameTimeNanos
        anim?.let { a ->
            val start = if (a.start < 0) frameTimeNanos.also { anim = Anim(a.from, a.to, it, a.durationNs) } else a.start
            val t = ((frameTimeNanos - start).toFloat() / a.durationNs).coerceIn(0f, 1f)
            val k = if (t < 0.5f) 4 * t * t * t else 1 - (-2 * t + 2).let { it * it * it } / 2
            setAnimState(a.from, a.to, k)
            if (t >= 1f) { anim = null; yaw = wrapAngle(yaw) }
        }
        if (flingVx != 0f || flingVz != 0f) {
            targetX += flingVx * dt; targetZ += flingVz * dt
            val decay = exp(-4.5f * dt)
            flingVx *= decay; flingVz *= decay
            if (sqrt(flingVx * flingVx + flingVz * flingVz) < distance * 0.02f) { flingVx = 0f; flingVz = 0f }
            clamp(); changed()
        }
        if (!isAnimating) lastUpdate = 0L
        return isAnimating
    }

    // ---------------------------------------------------------------------------- geometría

    private val eye = FloatArray(3)
    private val fwd = FloatArray(3)
    private val right = FloatArray(3)
    private val up = FloatArray(3)

    /** Calcula posición y base ortonormal de la cámara. */
    fun computeBasis() {
        val sp = sin(pitch); val cp = cos(pitch)
        val sy = sin(yaw); val cy = cos(yaw)
        // Desde el objetivo hacia el ojo.
        val ox = sp * sy; val oy = cp; val oz = sp * cy
        eye[0] = targetX + ox * distance; eye[1] = groundY + oy * distance; eye[2] = targetZ + oz * distance
        fwd[0] = -ox; fwd[1] = -oy; fwd[2] = -oz
        right[0] = cy; right[1] = 0f; right[2] = -sy
        // up = right × fwd
        up[0] = right[1] * fwd[2] - right[2] * fwd[1]
        up[1] = right[2] * fwd[0] - right[0] * fwd[2]
        up[2] = right[0] * fwd[1] - right[1] * fwd[0]
    }

    /** Escribe ojo, objetivo y vector "arriba" en [out] (9 valores) para Camera.lookAt de Filament. */
    fun lookAt(out: DoubleArray) {
        computeBasis()
        out[0] = eye[0].toDouble(); out[1] = eye[1].toDouble(); out[2] = eye[2].toDouble()
        out[3] = targetX.toDouble(); out[4] = groundY.toDouble(); out[5] = targetZ.toDouble()
        out[6] = up[0].toDouble(); out[7] = up[1].toDouble(); out[8] = up[2].toDouble()
    }

    val nearPlane get() = (distance * 0.02f).coerceIn(0.5f, 50f)
    val farPlane get() = distance * 4f + max(maxX - minX, maxZ - minZ) * 1.5f

    private val focalPx get() = viewportHeight / 2f / tan(Math.toRadians(fovDegrees / 2.0).toFloat())

    /** Punto del plano del suelo bajo un píxel de pantalla, como [x, z]; null si el rayo no lo toca. */
    fun groundAt(sx: Float, sy: Float): FloatArray? {
        computeBasis()
        val f = focalPx
        val u = (sx - viewportWidth / 2f) / f
        val v = -(sy - viewportHeight / 2f) / f
        val dx = fwd[0] + right[0] * u + up[0] * v
        val dy = fwd[1] + right[1] * u + up[1] * v
        val dz = fwd[2] + right[2] * u + up[2] * v
        if (dy > -0.02f) return null // casi paralelo al horizonte
        val t = (groundY - eye[1]) / dy
        if (t <= 0f || t > farPlane) return null
        return floatArrayOf(eye[0] + dx * t, eye[2] + dz * t)
    }

    /** Copia inmutable de la vista actual para proyectar puntos a pantalla. */
    fun snapshot(): CameraSnapshot {
        computeBasis()
        return CameraSnapshot(
            version, eye.copyOf(), fwd.copyOf(), right.copyOf(), up.copyOf(), focalPx,
            viewportWidth, viewportHeight, nearPlane, farPlane, yaw, pitch, distance,
        )
    }

    // ---------------------------------------------------------------------------- interno

    private fun state() = floatArrayOf(targetX, targetZ, distance, yaw, pitch)

    private fun setState(s: FloatArray) {
        targetX = s[0]; targetZ = s[1]; distance = s[2]; yaw = s[3]; pitch = s[4]
        clamp(); changed()
    }

    private fun setAnimState(from: FloatArray, to: FloatArray, k: Float) {
        targetX = from[0] + (to[0] - from[0]) * k
        targetZ = from[1] + (to[1] - from[1]) * k
        distance = from[2] + (to[2] - from[2]) * k
        yaw = from[3] + (to[3] - from[3]) * k
        pitch = from[4] + (to[4] - from[4]) * k
        clamp(); changed()
    }

    private fun clamp() {
        targetX = targetX.coerceIn(minX, maxX)
        targetZ = targetZ.coerceIn(minZ, maxZ)
        distance = distance.coerceIn(minDistance, maxDistance)
        pitch = pitch.coerceIn(0f, maxPitch)
        // Si se sale por un borde, frena la inercia.
        if (targetX <= minX || targetX >= maxX) flingVx = 0f
        if (targetZ <= minZ || targetZ >= maxZ) flingVz = 0f
    }

    private fun changed() { version++ }

    companion object {
        /** Ángulo desde la vertical en la vista inicial (el mismo `polar` de la web). */
        const val HOME_PITCH = 0.62f
        /** Fracción de media pantalla que puede ocupar el parque al encuadrarlo: horizontal y vertical. */
        private const val FIT_X = 0.9f
        private const val FIT_Y = 0.76f

        fun wrapAngle(a: Float): Float {
            var r = a
            while (r > PI) r -= (2 * PI).toFloat()
            while (r < -PI) r += (2 * PI).toFloat()
            return r
        }

        fun angle(x0: Float, y0: Float, x1: Float, y1: Float) = atan2(y1 - y0, x1 - x0)
    }
}

/**
 * Vista inmutable de la cámara en un instante. [project] convierte un punto del mundo (metros) a
 * píxeles de pantalla sin asignar memoria; es lo que usan los marcadores de Compose.
 */
class CameraSnapshot(
    val version: Long,
    internal val eye: FloatArray,
    internal val fwd: FloatArray,
    internal val right: FloatArray,
    internal val up: FloatArray,
    /** Distancia focal en píxeles: un objeto de 1 m a profundidad d mide focalPx / d píxeles. */
    val focalPx: Float,
    val width: Int,
    val height: Int,
    val near: Float,
    val far: Float,
    val yaw: Float,
    val pitch: Float,
    val distance: Float,
) {
    /** Rumbo en grados (0 = norte arriba; positivo = horario). */
    val bearingDegrees get() = Math.toDegrees(-yaw.toDouble()).toFloat()

    /**
     * Proyecta (x, y, z) a pantalla. Escribe [px, py] en [out] y devuelve la profundidad (distancia
     * a lo largo de la vista) o un valor negativo si el punto queda detrás de la cámara.
     */
    fun project(x: Float, y: Float, z: Float, out: FloatArray): Float {
        val vx = x - eye[0]; val vy = y - eye[1]; val vz = z - eye[2]
        val cz = vx * fwd[0] + vy * fwd[1] + vz * fwd[2]
        if (cz < near) return -1f
        val cx = vx * right[0] + vy * right[1] + vz * right[2]
        val cy = vx * up[0] + vy * up[1] + vz * up[2]
        out[0] = width / 2f + cx / cz * focalPx
        out[1] = height / 2f - cy / cz * focalPx
        return cz
    }

    /** Profundidad de (x, y, z) a lo largo de la vista (negativa si queda detrás del ojo). */
    fun depth(x: Float, y: Float, z: Float) = (x - eye[0]) * fwd[0] + (y - eye[1]) * fwd[1] + (z - eye[2]) * fwd[2]

    /**
     * true si la caja [x0, x1] × [y0, y1] × [z0, z1] puede verse: no queda entera fuera de alguno
     * de los planos laterales del frustum ni más allá del plano lejano.
     */
    fun boxVisible(x0: Float, x1: Float, y0: Float, y1: Float, z0: Float, z1: Float): Boolean {
        val hw = width / 2f; val hh = height / 2f; val f = focalPx
        // Planos laterales por el ojo, normales hacia dentro: ±right·f + fwd·hw y ±up·f + fwd·hh.
        for (side in 0 until 4) {
            val axis = if (side < 2) right else up
            val s = if (side % 2 == 0) f else -f
            val k = if (side < 2) hw else hh
            if (allOutside(axis[0] * s + fwd[0] * k, axis[1] * s + fwd[1] * k, axis[2] * s + fwd[2] * k, 0f,
                    x0, x1, y0, y1, z0, z1)) return false
        }
        // Plano lejano: dentro si depth <= far → (-fwd)·v + far >= 0.
        return !allOutside(-fwd[0], -fwd[1], -fwd[2], far, x0, x1, y0, y1, z0, z1)
    }

    /** true si las 8 esquinas cumplen n·(p − ojo) + d < 0. */
    private fun allOutside(
        nx: Float, ny: Float, nz: Float, d: Float,
        x0: Float, x1: Float, y0: Float, y1: Float, z0: Float, z1: Float,
    ): Boolean {
        // Esquina más "positiva" respecto a la normal: si ni esa entra, ninguna entra.
        val px = (if (nx >= 0) x1 else x0) - eye[0]
        val py = (if (ny >= 0) y1 else y0) - eye[1]
        val pz = (if (nz >= 0) z1 else z0) - eye[2]
        return nx * px + ny * py + nz * pz + d < 0f
    }

    /** Distancia del ojo al punto más cercano de un rectángulo del suelo a la altura [y]. */
    fun distanceToRect(x0: Float, x1: Float, z0: Float, z1: Float, y: Float = 0f): Float {
        val dx = eye[0] - eye[0].coerceIn(x0, x1)
        val dz = eye[2] - eye[2].coerceIn(z0, z1)
        val dy = eye[1] - y
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
