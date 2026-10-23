package com.faustinoloeza.parquex.nav

import com.faustinoloeza.parquex.data.ParkData
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * Ayudas de navegación a pie (puras, probadas): dónde empieza la caminata (ubicación real o
 * simulada), avance sobre la ruta, siguiente maniobra, salida de la ruta y textos del letrero.
 * Port uno a uno de `shared/navigation.js` de la web; las rutas salen de [route] (NavGraph.kt).
 */

/** Constantes de `GUIDANCE` (navigation.js). */
object Guidance {
    /** m fuera del área diseñada a partir de los cuales no se usa la ubicación real (se simula en la entrada). */
    const val FAR_FROM_PARK = 300.0
    /** m de la ruta a partir de los cuales se calcula una nueva. */
    const val OFF_ROUTE = 25.0
    /** m del destino a los que termina la caminata. */
    const val ARRIVE = 12.0
    /** m: una maniobra más cerca que esto es "ya". */
    const val NOW = 8.0
    /** m que se buscan atrás / adelante de la última posición conocida sobre la ruta. */
    const val BACK = 40.0
    const val AHEAD = 120.0
}

// ------------------------------------------------------------------------------ entrada

/** Entrada del parque (inicio de una caminata simulada); [name] si es una actividad. */
data class Entrance(val x: Double, val z: Double, val name: String? = null)

/** Actividad candidata a entrada: nombre en español (se ignoran acentos y mayúsculas). */
data class EntranceCandidate(val nameEs: String, val x: Double, val z: Double, val active: Boolean = true)

/** Nombres de actividad que marcan la entrada, por prioridad (texto ya sin acentos y en minúsculas). */
private val ENTRANCE_NAMES = listOf(
    Regex("""^(entrada|acceso|ingreso)( principal| al parque| y salida.*)?$"""),
    Regex("""^ingreso"""),
    Regex("""^plaza principal$"""),
    Regex("""^taquillas?\b"""),
)

private val MARKS = Regex("[̀-ͯ]")

private fun plain(s: String?) = MARKS.replace(Normalizer.normalize((s ?: "").lowercase(), Normalizer.Form.NFD), "").trim()

/**
 * La entrada del parque: [override] si viene; si no, la actividad activa cuyo nombre en español
 * dice entrada ("Entrada", "Acceso", "Ingreso…", "Plaza Principal", "Taquillas"); si no, la
 * primera activa; si no, el centro del área diseñada ([center], o el origen).
 */
fun findEntrance(pois: List<EntranceCandidate>, center: NavPoint? = null, override: Entrance? = null): Entrance {
    if (override != null && override.x.isFinite() && override.z.isFinite()) return override
    val active = pois.filter { it.active }
    for (re in ENTRANCE_NAMES) {
        val p = active.firstOrNull { re.containsMatchIn(plain(it.nameEs)) }
        if (p != null) return Entrance(p.x, p.z, p.nameEs)
    }
    active.firstOrNull()?.let { return Entrance(it.x, it.z, it.nameEs) }
    return Entrance(center?.x ?: 0.0, center?.z ?: 0.0)
}

/**
 * Entrada de [park]: por omisión `park.json → entrance` (la que elige la web); sin ella, por el
 * nombre de las actividades. Los nombres son los del idioma con que se leyó [park]: para buscar
 * por nombre, leerlo en español.
 */
fun findEntrance(
    park: ParkData,
    override: Entrance? = park.entrance?.let { Entrance(it.x.toDouble(), it.z.toDouble()) },
): Entrance = findEntrance(
    park.pois.map { EntranceCandidate(it.name, it.x.toDouble(), it.z.toDouble(), it.active) },
    park.designArea?.let { NavPoint(it.cx.toDouble(), it.cz.toDouble()) },
    override,
)

// ------------------------------------------------------------------------------ inicio

/** Ubicación real en metros locales; [heading] en grados (o null). */
data class Fix(val x: Double, val z: Double, val accuracy: Double? = null, val heading: Double? = null)

/** Por qué la caminata empieza donde empieza. */
enum class StartReason { GPS, MANUAL, FAR, DENIED, UNAVAILABLE, WAITING }

data class Start(
    val x: Double,
    val z: Double,
    val simulated: Boolean,
    val reason: StartReason,
    val accuracy: Double? = null,
    val heading: Double? = null,
)

/**
 * Dónde empieza la caminata:
 *  - [manual] (un punto tocado en el mapa) gana;
 *  - una ubicación [fix] dentro del parque, o a menos de [far] m fuera de su área diseñada
 *    ([signedDistance] < 0 dentro), se usa tal cual;
 *  - si no (lejos, permiso negado, sin ubicación), la entrada, simulada. [error]: DENIED o UNAVAILABLE.
 */
fun chooseStart(
    fix: Fix?,
    signedDistance: (Double, Double) -> Double,
    entrance: NavPoint,
    error: StartReason? = null,
    manual: NavPoint? = null,
    far: Double = Guidance.FAR_FROM_PARK,
): Start {
    if (manual != null) return Start(manual.x, manual.z, simulated = true, reason = StartReason.MANUAL)
    if (fix != null && signedDistance(fix.x, fix.z) <= far) {
        return Start(fix.x, fix.z, simulated = false, reason = StartReason.GPS, accuracy = fix.accuracy, heading = fix.heading)
    }
    val reason = if (fix != null) StartReason.FAR else error ?: StartReason.WAITING
    return Start(entrance.x, entrance.z, simulated = true, reason = reason)
}

// ------------------------------------------------------------------------------ guía

/** Ruta lista para guiar: la polilínea con sus distancias acumuladas [cum]. */
class PreparedRoute(
    val points: List<NavPoint>,
    val steps: List<NavStep>,
    val length: Double = polylineLength(points),
    val gaps: DoubleArray = doubleArrayOf(0.0, 0.0),
    val direct: Boolean = false,
) {
    val cum: DoubleArray = cumulative(points)
}

fun prepareRoute(r: NavRoute) = PreparedRoute(r.points, r.steps, r.length, r.gaps, r.direct)

/** Punto de la ruta más cercano: [along] m desde el inicio, a [dist] m de la posición. */
data class OnRoute(val along: Double, val dist: Double, val x: Double, val z: Double)

/**
 * Punto de la ruta más cercano a (x, z); primero alrededor de [hint] (m a lo largo de la ruta de
 * la última posición: una ruta que vuelve cerca de sí misma no hace saltar la posición), luego en
 * toda la ruta.
 */
fun locateOnRoute(r: PreparedRoute, x: Double, z: Double, hint: Double? = null): OnRoute {
    val points = r.points; val cum = r.cum
    fun scan(from: Double, to: Double): OnRoute? {
        var best: OnRoute? = null
        for (i in 1 until points.size) {
            if (cum[i] < from || cum[i - 1] > to) continue
            val a = points[i - 1]; val b = points[i]
            val len = (cum[i] - cum[i - 1]).takeIf { it != 0.0 } ?: 1.0
            var t = projectSegment(x, z, a.x, a.z, b.x, b.z).t
            // dentro de la ventana
            t = max((from - cum[i - 1]) / len, min((to - cum[i - 1]) / len, t))
            t = max(0.0, min(1.0, t))
            val px = a.x + (b.x - a.x) * t; val pz = a.z + (b.z - a.z) * t
            val d = hypot(x - px, z - pz)
            if (best == null || d < best.dist) best = OnRoute(cum[i - 1] + len * t, d, px, pz)
        }
        return best
    }
    if (hint != null) {
        val local = scan(hint - Guidance.BACK, hint + Guidance.AHEAD)
        if (local != null && local.dist <= Guidance.OFF_ROUTE) return local
    }
    return scan(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
        ?: OnRoute(0.0, hypot(x - points[0].x, z - points[0].z), points[0].x, points[0].z)
}

/**
 * Estado de la guía en una posición. [next]: índice de la siguiente maniobra en `steps`;
 * [toNext]: m hasta ella; [remaining]: m hasta el final.
 */
data class GuideState(
    val along: Double,
    val dist: Double,
    val remaining: Double,
    val next: Int,
    val toNext: Double,
    val offRoute: Boolean,
    val arrived: Boolean,
)

/** Avance, siguiente maniobra y si se salió de la ruta o llegó. */
fun guidance(r: PreparedRoute, pos: NavPoint, hint: Double? = null, destination: NavPoint? = null): GuideState {
    val at = locateOnRoute(r, pos.x, pos.z, hint)
    val total = r.cum.last()
    val remaining = max(0.0, total - at.along)
    val end = destination ?: r.points.last()
    val distToDest = hypot(pos.x - end.x, pos.z - end.z)
    val arrived = distToDest < Guidance.ARRIVE || (remaining < Guidance.ARRIVE && distToDest <= Guidance.ARRIVE * 1.5)
    // la siguiente maniobra todavía adelante (un par de metros de holgura al pasarla)
    var next = r.steps.size - 1
    for (i in 1 until r.steps.size) {
        if (r.steps[i].along > at.along + 2) { next = i; break }
    }
    return GuideState(
        along = at.along,
        dist = at.dist,
        remaining = remaining,
        next = next,
        toNext = max(0.0, r.steps[next].along - at.along),
        offRoute = !arrived && at.dist > Guidance.OFF_ROUTE,
        arrived = arrived,
    )
}

/** Caminante simulado: posición y rumbo tras recorrer [s] m de la ruta. */
data class Walker(val x: Double, val z: Double, val dx: Double, val dz: Double) {
    val point get() = NavPoint(x, z)
}

fun walkerAt(r: PreparedRoute, s: Double): Walker {
    val p = pointAlong(r.points, r.cum, s)
    return Walker(p.x, p.z, p.dx, p.dz)
}

// ------------------------------------------------------------------------------ textos

/** Minutos a pie para una distancia (al menos 1). */
fun walkMinutes(meters: Double, speed: Double = Nav.SPEED): Int = max(1, floor(meters / speed / 60 + 0.5).toInt())

/** "40 m", "350 m", "1,2 km" (redondeo de app de caminar: de 5 en 5 m bajo 100 m, de 10 en 10 bajo 1 km). */
fun formatDistance(m: Double, lang: String = "es"): String {
    if (m >= 1000) {
        // como toFixed(1) de JS: redondeo del valor binario exacto, empates hacia arriba
        val km = BigDecimal(m / 1000).setScale(1, RoundingMode.HALF_UP).toPlainString()
        return "${if (lang == "es") km.replace('.', ',') else km} km"
    }
    val step = if (m < 100) 5 else 10
    return "${max(step.toLong(), floor(m / step + 0.5).toLong() * step)} m"
}

private fun lowerFirst(s: String) = if (s.isEmpty()) s else s[0].lowercase() + s.substring(1)

/**
 * Línea principal del letrero: "En 40 m gira a la izquierda", la maniobra sola cuando está a
 * pocos metros y "Has llegado" al final.
 */
fun bannerText(r: PreparedRoute, g: GuideState, t: NavText, lang: String = "es"): String {
    if (g.arrived) return t("nav_arrived")
    val step = r.steps[g.next]
    if (g.toNext <= Guidance.NOW) return step.instruction
    return t("nav_in").replaceFirst("{dist}", formatDistance(g.toNext, lang)).replaceFirst("{text}", lowerFirst(step.instruction))
}
