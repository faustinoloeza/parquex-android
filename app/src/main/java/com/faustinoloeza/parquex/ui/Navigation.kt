package com.faustinoloeza.parquex.ui

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.faustinoloeza.parquex.R
import com.faustinoloeza.parquex.data.ParkData
import com.faustinoloeza.parquex.map.CameraSnapshot
import com.faustinoloeza.parquex.nav.Fix
import com.faustinoloeza.parquex.nav.GuideState
import com.faustinoloeza.parquex.nav.Landmark
import com.faustinoloeza.parquex.nav.NavGraph
import com.faustinoloeza.parquex.nav.NavPoint
import com.faustinoloeza.parquex.nav.NavStep
import com.faustinoloeza.parquex.nav.NavText
import com.faustinoloeza.parquex.nav.PreparedRoute
import com.faustinoloeza.parquex.nav.Start
import com.faustinoloeza.parquex.nav.StartReason
import com.faustinoloeza.parquex.nav.Turn
import com.faustinoloeza.parquex.nav.bannerText
import com.faustinoloeza.parquex.nav.chooseStart
import com.faustinoloeza.parquex.nav.formatDistance
import com.faustinoloeza.parquex.nav.guidance
import com.faustinoloeza.parquex.nav.prepareRoute
import com.faustinoloeza.parquex.nav.route
import com.faustinoloeza.parquex.nav.walkMinutes
import com.faustinoloeza.parquex.ui.theme.LocalMapColors
import kotlin.math.atan2

/** Verde del letrero de navegación y rojo del pin de destino (constantes de la web, no de night.json). */
private val BannerGreen = Color(0xFF0D7A4A)
private val PinRed = Color(0xFFEA4335)
private val ArrivedBlue = Color(0xFF1A73E8)

/**
 * Estado de la navegación a pie (como `ui/navigation.js` de la web): destino, salida, ruta, guía y
 * simulación. La lógica es la de `nav/` (portada de la web); aquí solo se decide qué mostrar.
 */
@Stable
class NavSession(
    val park: ParkData,
    private val graph: NavGraph,
    val destination: ParkData.Poi,
    private val text: NavText,
    /** Idioma de las distancias ("es" usa coma decimal). */
    val lang: String,
) {
    enum class Phase { PREVIEW, NAVIGATING }

    var phase by mutableStateOf(Phase.PREVIEW)
        private set
    var start by mutableStateOf<Start?>(null)
        private set
    var route by mutableStateOf<PreparedRoute?>(null)
        private set
    var guide by mutableStateOf<GuideState?>(null)
        private set
    /** Elegir salida: el próximo toque en el mapa la fija. */
    var picking by mutableStateOf(false)
    var simulate by mutableStateOf(false)
        private set
    var speed by mutableFloatStateOf(1f)
    /** Metros recorridos por el caminante simulado. */
    var simulated by mutableFloatStateOf(0f)
        private set
    /** La cámara sigue al caminante (un gesto lo pausa; «Recentrar» lo reanuda). */
    var following by mutableStateOf(true)
    var rerouting by mutableStateOf(false)
        private set
    private var manual: NavPoint? = null
    private var lastReroute = 0L
    private var hint: Double? = null

    private val landmarks = park.pois.map { Landmark(it.name, it.x.toDouble(), it.z.toDouble()) }
    private val target = NavPoint(destination.x.toDouble(), destination.z.toDouble())

    /** Calcula salida y ruta con la ubicación actual ([fix] en metros locales, [error] si no hay). */
    fun plan(fix: Fix?, error: StartReason?) {
        val entrance = park.entrance?.let { NavPoint(it.x.toDouble(), it.z.toDouble()) }
            ?: NavPoint(park.bounds.centerX.toDouble(), park.bounds.centerZ.toDouble())
        val area = park.designArea
        val s = chooseStart(
            fix,
            signedDistance = { x, z -> area?.signedDistance(x.toFloat(), z.toFloat())?.toDouble() ?: 0.0 },
            entrance = entrance, error = error, manual = manual,
        )
        start = s
        route = prepareRoute(route(graph, NavPoint(s.x, s.z), target, destination.name, text, landmarks))
        hint = null
        guide = route?.let { guidance(it, NavPoint(s.x, s.z), destination = target) }
    }

    fun pick(x: Float, z: Float, fix: Fix?, error: StartReason?) {
        picking = false
        manual = NavPoint(x.toDouble(), z.toDouble())
        plan(fix, error)
    }

    fun begin(simulated: Boolean) {
        simulate = simulated || start?.simulated == true
        this.simulated = 0f
        following = true
        phase = Phase.NAVIGATING
    }

    /** Avanza la simulación [dt] s y devuelve la posición del caminante. */
    fun stepSimulation(dt: Float): NavPoint? {
        val r = route ?: return null
        simulated = (simulated + dt * 1.25f * speed).coerceAtMost(r.length.toFloat())
        val w = com.faustinoloeza.parquex.nav.walkerAt(r, simulated.toDouble())
        update(w.point)
        return w.point
    }

    /** Guía para la posición [pos]; con GPS real recalcula la ruta si te sales (≥ 4 s entre recálculos). */
    fun update(pos: NavPoint, fix: Fix? = null) {
        val r = route ?: return
        val g = guidance(r, pos, hint, target)
        hint = g.along
        guide = g
        if (g.offRoute && !simulate && fix != null) {
            val now = System.currentTimeMillis()
            if (now - lastReroute >= 4_000) {
                lastReroute = now
                rerouting = true
                plan(fix, null)
                rerouting = false
            }
        }
    }

    /** Rumbo de la cámara para que el sentido de la marcha quede arriba (yaw de [MapCamera]). */
    fun headingYaw(): Float? {
        val r = route ?: return null
        val g = guide ?: return null
        val a = com.faustinoloeza.parquex.nav.walkerAt(r, (g.along + 15).coerceAtMost(r.length))
        val b = com.faustinoloeza.parquex.nav.walkerAt(r, g.along)
        val dx = a.x - b.x; val dz = a.z - b.z
        if (dx * dx + dz * dz < 1e-6) return null
        return atan2(-dx, -dz).toFloat()
    }

    fun banner(): String {
        val r = route ?: return ""
        val g = guide ?: return ""
        return bannerText(r, g, text, lang)
    }

    /** «Después: …» si la maniobra siguiente está a menos de 60 m de la actual. */
    fun then(): String? {
        val r = route ?: return null
        val g = guide ?: return null
        val next = r.steps.getOrNull(g.next + 1) ?: return null
        val cur = r.steps[g.next]
        return if (next.along - cur.along < 60) next.instruction else null
    }

    val minutes get() = walkMinutes(guide?.remaining ?: route?.length ?: 0.0)
    val distanceText get() = formatDistance(guide?.remaining ?: route?.length ?: 0.0, lang)

    companion object {
        /** Traductor de llaves `nav_*` con los recursos del [context] (ya en el idioma de la app). */
        @SuppressLint("DiscouragedApi")
        fun translator(context: Context): NavText {
            val cache = HashMap<String, String>()
            return { key ->
                cache.getOrPut(key) {
                    val id = context.resources.getIdentifier(key, "string", context.packageName)
                    if (id != 0) context.getString(id) else key
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- hoja de indicaciones

/** Vista previa: destino, minutos y distancia, salida (con «Elegir salida»), Iniciar / Simular y pasos. */
@Composable
fun DirectionsSheet(nav: NavSession, onClose: () -> Unit, onPick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalMapColors.current
    val r = nav.route
    val s = nav.start
    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 12.dp, bottomEnd = 12.dp),
        color = colors.surface, contentColor = colors.text, shadowElevation = 8.dp, modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.nav_goTo), style = MaterialTheme.typography.labelMedium, color = colors.secondary)
                    Text(nav.destination.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2)
                }
                TextButton(onClick = onClose) { Text(stringResource(R.string.nav_cancel)) }
            }
            if (r != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${walkMinutes(r.length)} ${stringResource(R.string.nav_min)}", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        color = if (colors.night) Color(0xFF81C995) else BannerGreen)
                    Spacer(Modifier.width(8.dp))
                    Text("${nav.distanceText} · ${stringResource(R.string.nav_walk)}", style = MaterialTheme.typography.bodyMedium,
                        color = colors.secondary, modifier = Modifier.padding(bottom = 3.dp))
                }
            }
            if (s != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val from = stringResource(when (s.reason) {
                        StartReason.GPS -> R.string.nav_from_gps
                        StartReason.MANUAL -> R.string.nav_from_manual
                        else -> R.string.nav_from_sim
                    })
                    Text("${stringResource(R.string.nav_from)}: $from", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onPick) {
                        Text(stringResource(R.string.nav_pick), color = if (colors.night) Color(0xFF8AB4F8) else MaterialTheme.colorScheme.primary)
                    }
                }
                val warn = when (s.reason) {
                    StartReason.FAR -> R.string.nav_far
                    StartReason.DENIED -> R.string.nav_denied
                    StartReason.UNAVAILABLE -> R.string.nav_unavailable
                    StartReason.MANUAL -> R.string.nav_manual
                    StartReason.WAITING -> R.string.nav_locating
                    StartReason.GPS -> null
                }
                warn?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = colors.secondary) }
                if (r?.direct == true) Text(stringResource(R.string.nav_direct), style = MaterialTheme.typography.bodySmall, color = colors.secondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { nav.begin(simulated = false) }, enabled = r != null && s?.simulated == false,
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = colors.text.copy(alpha = 0.12f), disabledContentColor = colors.text.copy(alpha = 0.38f),
                    ),
                ) {
                    Text(stringResource(R.string.nav_start))
                }
                OutlinedButton(
                    onClick = { nav.begin(simulated = true) }, enabled = r != null,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text),
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.text.copy(alpha = 0.4f)),
                ) {
                    Text(stringResource(R.string.nav_simulate))
                }
            }
            if (r != null) {
                HorizontalDivider(color = colors.divider)
                Text(stringResource(R.string.nav_steps), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                LazyColumn(Modifier.heightIn(max = 180.dp)) {
                    itemsIndexed(r.steps) { i, step ->
                        StepRow(step, if (i + 1 < r.steps.size) r.steps[i + 1].along - step.along else 0.0, nav)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: NavStep, toNext: Double, nav: NavSession) {
    val colors = LocalMapColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        TurnArrow(step, colors.text, Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(step.instruction, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (toNext > 0) Text(formatDistance(toNext, nav.lang), style = MaterialTheme.typography.labelMedium, color = colors.secondary)
    }
}

/** Flecha de la maniobra: recto, izquierda/derecha (ligera o marcada) o llegada. */
@Composable
fun TurnArrow(step: NavStep?, color: Color, modifier: Modifier = Modifier, arrived: Boolean = false) {
    val angle = when (step?.turn) {
        Turn.LEFT -> if (step.slight) -45f else -90f
        Turn.RIGHT -> if (step.slight) 45f else 90f
        else -> 0f
    }
    val arrive = arrived || step?.turn == Turn.ARRIVE
    Box(modifier.drawBehind {
        val w = size.width; val h = size.height; val s = w * 0.13f
        if (arrive) {
            drawCircle(color, radius = w * 0.32f, style = androidx.compose.ui.graphics.drawscope.Stroke(s))
            drawCircle(color, radius = w * 0.12f)
            return@drawBehind
        }
        val rad = Math.toRadians(angle.toDouble())
        val base = Offset(w / 2, h * 0.92f); val mid = Offset(w / 2, h * 0.5f)
        val tip = Offset((w / 2 + kotlin.math.sin(rad) * h * 0.42f).toFloat(), (h * 0.5f - kotlin.math.cos(rad) * h * 0.42f).toFloat())
        drawLine(color, base, mid, s)
        drawLine(color, mid, tip, s)
        val dir = Offset(tip.x - mid.x, tip.y - mid.y)
        val len = kotlin.math.hypot(dir.x, dir.y)
        val ux = dir.x / len; val uy = dir.y / len
        val head = Path().apply {
            moveTo(tip.x + ux * s, tip.y + uy * s)
            lineTo(tip.x - ux * w * 0.22f - uy * w * 0.2f, tip.y - uy * w * 0.22f + ux * w * 0.2f)
            lineTo(tip.x - ux * w * 0.22f + uy * w * 0.2f, tip.y - uy * w * 0.22f - ux * w * 0.2f)
            close()
        }
        drawPath(head, color)
    })
}

// ---------------------------------------------------------------------------- durante la navegación

/** Letrero verde de arriba: flecha, maniobra y «Después: …»; azul «Has llegado» al final. */
@Composable
fun NavBanner(nav: NavSession, modifier: Modifier = Modifier) {
    val g = nav.guide
    val r = nav.route
    val arrived = g?.arrived == true
    Surface(
        shape = RoundedCornerShape(16.dp), color = if (arrived) ArrivedBlue else BannerGreen, contentColor = Color.White,
        shadowElevation = 6.dp, modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TurnArrow(r?.steps?.getOrNull(g?.next ?: 0), Color.White, Modifier.size(34.dp), arrived = arrived)
                Spacer(Modifier.width(12.dp))
                Text(
                    if (nav.rerouting) stringResource(R.string.nav_rerouting) else nav.banner(),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                )
            }
            if (!arrived) nav.then()?.let {
                Text("${stringResource(R.string.nav_then)}: $it", style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(start = 46.dp, top = 4.dp))
            }
        }
    }
}

/** Barra inferior: minutos y metros restantes, velocidad de la simulación y Terminar. */
@Composable
fun NavBottomBar(nav: NavSession, onEnd: () -> Unit, onRecenter: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = LocalMapColors.current
    Surface(shape = RoundedCornerShape(16.dp), color = colors.panel, contentColor = colors.text, shadowElevation = 6.dp, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${nav.minutes} ${stringResource(R.string.nav_min)}", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    color = if (colors.night) Color(0xFF81C995) else BannerGreen)
                Text(nav.distanceText + if (nav.simulate) " · ${stringResource(R.string.nav_simulating)}" else "",
                    style = MaterialTheme.typography.bodySmall, color = colors.secondary)
            }
            if (nav.simulate) {
                val label = stringResource(R.string.nav_speed)
                Row(Modifier.semantics { contentDescription = label }) {
                    for (v in floatArrayOf(1f, 4f, 10f)) {
                        val on = nav.speed == v
                        Box(
                            Modifier.padding(horizontal = 2.dp).size(width = 40.dp, height = 32.dp)
                                .drawBehind { if (on) drawRoundRect(colors.text.copy(alpha = 0.12f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx())) }
                                .clickable { nav.speed = v },
                            contentAlignment = Alignment.Center,
                        ) { Text("${v.toInt()}×", fontWeight = if (on) FontWeight.Bold else FontWeight.Normal) }
                    }
                }
            }
            onRecenter?.let { TextButton(onClick = it) { Text(stringResource(R.string.nav_recenter)) } }
            Button(onClick = onEnd, colors = ButtonDefaults.buttonColors(containerColor = PinRed)) { Text(stringResource(R.string.nav_end)) }
        }
    }
}

/** Pin rojo del destino y punto de salida sobre la vista 3D (fase de dibujo, como [LocationLayer]). */
@Composable
fun NavMarkers(nav: NavSession?, snapshot: () -> CameraSnapshot?, modifier: Modifier = Modifier) {
    val out = remember { FloatArray(2) }
    Spacer(modifier.drawBehind {
        val n = nav ?: return@drawBehind
        val snap = snapshot() ?: return@drawBehind
        val d = n.destination
        if (snap.project(d.x, 0.5f, d.z, out) > 0f) {
            val tip = Offset(out[0], out[1])
            val r = 11.dp.toPx()
            val c = Offset(tip.x, tip.y - r * 2.2f)
            val pin = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(c.x - r * 0.75f, c.y + r * 0.6f)
                lineTo(c.x + r * 0.75f, c.y + r * 0.6f)
                close()
            }
            drawPath(pin, PinRed)
            drawCircle(PinRed, r, c)
            drawCircle(Color.White, r * 0.4f, c)
        }
    })
}

/** Indicación «Toca el mapa para elegir el punto de partida» mientras se elige salida. */
@Composable
fun PickHint(onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Surface(shape = CircleShape, color = Color(0xE61F2A2E), contentColor = Color.White, modifier = modifier) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.nav_pick_hint), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onCancel) { Text(stringResource(R.string.nav_cancel), color = Color(0xFF8AB4F8)) }
        }
    }
}
