package com.faustinoloeza.parquex.ui

import android.Manifest
import android.os.SystemClock
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.faustinoloeza.parquex.R
import com.faustinoloeza.parquex.data.AppSettings
import com.faustinoloeza.parquex.data.ParkData
import com.faustinoloeza.parquex.data.ParkNight
import com.faustinoloeza.parquex.nav.Fix
import com.faustinoloeza.parquex.nav.NavGraph
import com.faustinoloeza.parquex.nav.NavPoint
import com.faustinoloeza.parquex.nav.StartReason
import com.faustinoloeza.parquex.nav.buildGraph
import com.faustinoloeza.parquex.location.LocationTracker
import com.faustinoloeza.parquex.location.SimulatedWalk
import com.faustinoloeza.parquex.map.GroundTiles
import com.faustinoloeza.parquex.map.MapGestures
import com.faustinoloeza.parquex.map.ParkRenderer
import com.faustinoloeza.parquex.ui.theme.DayMapColors
import com.faustinoloeza.parquex.ui.theme.LocalMapColors
import com.faustinoloeza.parquex.ui.theme.NightMapColors
import com.faustinoloeza.parquex.ui.theme.LocationBlue
import com.faustinoloeza.parquex.ui.theme.Sunset
import com.faustinoloeza.parquex.ui.theme.Teal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

/** Parques conocidos, en el orden del selector. */
private val PARK_KEYS = listOf("xc", "xh", "xp", "xs", "xo")

/** Blanco translúcido de las piezas flotantes. */
/** Colores de la interfaz sobre el mapa: claros de día, azul marino de noche ([LocalMapColors]). */
private val panel: Color @Composable @ReadOnlyComposable get() = LocalMapColors.current.panel
private val surface: Color @Composable @ReadOnlyComposable get() = LocalMapColors.current.surface
private val ink: Color @Composable @ReadOnlyComposable get() = LocalMapColors.current.text

/**
 * Pantalla principal: mapa real (OSM) con los modelos 3D encima (Filament en un SurfaceView) y la
 * interfaz en Compose: barra compacta del parque, ajustes, controles, punto de ubicación,
 * marcadores y el menú de puntos por ruta/categoría.
 *
 * Se usa **SurfaceView** y no TextureView: el SurfaceView compone su búfer directamente en el
 * compositor del sistema (sin copia extra ni latencia de un cuadro) y Compose dibuja encima sin
 * problema porque el SurfaceView queda detrás de la ventana. TextureView solo haría falta para
 * animar/recortar la vista 3D como un elemento más de Compose (alfa, esquinas redondeadas...).
 */
@Composable
fun ParkMapScreen(settings: AppSettings) {
    val context = LocalContext.current
    val lang = settings.language.tag
    val renderer = remember { ParkRenderer(context.applicationContext) }
    DisposableEffect(renderer) { onDispose { renderer.destroy() } }

    // Parques presentes en assets: con modelo (ParkRenderer.MODEL_FILE) o al menos con datos (park.json).
    val parks = remember {
        val withModel = renderer.availableParks()
        val withData = runCatching {
            context.assets.list("parks")?.filter { context.assets.list("parks/$it")?.contains("park.json") == true }
        }.getOrNull().orEmpty()
        val all = (withModel + withData).toSet()
        PARK_KEYS.filter { it in all } + all.filter { it !in PARK_KEYS }.sorted()
    }
    val withModel = remember { renderer.availableParks().toSet() }
    var parkKey by rememberSaveable { mutableStateOf(parks.firstOrNull { it in withModel } ?: parks.firstOrNull() ?: "xh") }
    var parkData by remember { mutableStateOf<ParkData?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    // Grupos (ruta o categoría) cuyos puntos se muestran. Por defecto ninguno.
    var activeGroups by rememberSaveable(parkKey) { mutableStateOf(emptyList<String>()) }
    var simulate by rememberSaveable { mutableStateOf(false) }
    var tracking by rememberSaveable { mutableStateOf(false) }
    var centerOnFix by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var simPosition by remember { mutableStateOf<UserPosition?>(null) }
    var showList by remember { mutableStateOf(false) }

    LaunchedEffect(parkKey) {
        val data = withContext(Dispatchers.IO) {
            runCatching { ParkData.load(context.assets, parkKey, lang) }.getOrNull()
        }
        // En el hilo principal (Filament). El parque nuevo carga fuera de escena: los datos de la
        // interfaz (píldora, chips, lista, marcadores) cambian cuando el 3D cambia, no antes.
        renderer.loadPark(parkKey, data) {
            parkData = data
            selectedId = null
        }
    }
    // Cambiar de idioma solo vuelve a leer los textos de park.json (no recarga el modelo 3D).
    LaunchedEffect(lang) {
        val current = parkData ?: return@LaunchedEffect
        if (current.lang == lang) return@LaunchedEffect
        parkData = withContext(Dispatchers.IO) {
            runCatching { ParkData.load(context.assets, current.key, lang) }.getOrNull()
        } ?: current
    }
    // Día / noche: automático = noche de 19:00 a 06:00 hora del parque (UTC−5 fijo), revisado cada minuto.
    var nightNow by remember { mutableStateOf(ParkNight.isNightNow()) }
    LaunchedEffect(settings.dayNight) {
        while (true) {
            nightNow = ParkNight.isNightNow()
            renderer.setNight(
                when (settings.dayNight) {
                    AppSettings.DayNight.AUTO -> nightNow
                    AppSettings.DayNight.DAY -> false
                    AppSettings.DayNight.NIGHT -> true
                }
            )
            delay(60_000)
        }
    }

    // Calidad gráfica: en Auto parte de lo que el dispositivo aguanta y la baja sola si hace falta.
    LaunchedEffect(settings.quality) {
        when (settings.quality) {
            AppSettings.QualityMode.AUTO -> renderer.setQuality(ParkRenderer.detectQuality(context), auto = true)
            AppSettings.QualityMode.HIGH -> renderer.setQuality(ParkRenderer.Quality.HIGH, auto = false)
            AppSettings.QualityMode.MEDIUM -> renderer.setQuality(ParkRenderer.Quality.MEDIUM, auto = false)
            AppSettings.QualityMode.LOW -> renderer.setQuality(ParkRenderer.Quality.LOW, auto = false)
        }
    }

    // Torre, voladores, banderas, aves y mariposas (Xcaret): se mueven si se piden y la calidad no es Baja.
    LaunchedEffect(settings.animations) { renderer.setAnimations(settings.animations) }

    val park = parkData
    val filters = remember(park) { park?.let(PoiFilters::of) }
    val groups = filters?.groups.orEmpty()
    val activeSet = remember(activeGroups) { activeGroups.toSet() }
    LaunchedEffect(filters, activeGroups) {
        renderer.setActiveRoutes(groups.filter { it.key in activeSet }.mapNotNullTo(HashSet()) { it.routeSlug })
    }

    // ---- ubicación real (solo en primer plano)
    val tracker = remember { LocationTracker(context.applicationContext) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(tracking, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (tracking) tracker.start()
                Lifecycle.Event.ON_STOP -> tracker.stop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (tracking && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) tracker.start()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            tracker.stop()
        }
    }

    fun centerOn(fix: LocationTracker.Fix) {
        val p = park ?: return
        val local = p.toLocal(fix.lat, fix.lng)
        if (p.contains(local[0], local[1], margin = 0.15f)) {
            renderer.mapCamera.flyToPoint(local[0], local[1])
            renderer.requestRender()
        } else {
            val km = LocationTracker.distanceMeters(fix.lat, fix.lng, p.center.lat, p.center.lng) / 1000.0
            val distance = String.format(Locale.forLanguageTag(lang), if (km < 10) "%.1f" else "%,.0f", km)
            notice = context.getString(R.string.location_outside, distance, p.name)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) {
            tracking = true
            centerOnFix = true
            notice = context.getString(R.string.location_searching)
        } else {
            notice = context.getString(R.string.location_denied)
        }
    }

    val fix = tracker.fix
    LaunchedEffect(fix, centerOnFix) {
        if (centerOnFix && fix != null) {
            centerOnFix = false
            notice = null
            centerOn(fix)
        }
    }

    fun onMyLocation() {
        val sim = simPosition
        when {
            simulate && sim != null -> { renderer.mapCamera.flyToPoint(sim.x, sim.z); renderer.requestRender() }
            !tracker.hasPermission() -> permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
            !tracker.isEnabled() -> notice = context.getString(R.string.location_disabled)
            else -> {
                tracking = true
                val current = tracker.fix
                if (current != null) centerOn(current) else {
                    centerOnFix = true
                    notice = context.getString(R.string.location_searching)
                }
            }
        }
    }

    // ---- ubicación simulada: un punto que recorre una línea del parque (demo)
    val walk = remember(park, simulate) {
        if (simulate && park != null) {
            val preferred = groups.firstOrNull { it.key in activeSet && it.routeSlug != null }?.routeSlug
            SimulatedWalk.forPark(park, preferred)
        } else null
    }
    val walkLabel = when (walk?.source) {
        SimulatedWalk.Source.ROUTE -> park?.routes?.firstOrNull { it.slug == walk.routeSlug }?.let { it.label ?: it.name }.orEmpty()
        SimulatedWalk.Source.WALKWAYS -> stringResource(R.string.simulated_tour_walkways)
        else -> stringResource(R.string.simulated_tour_pois)
    }
    LaunchedEffect(walk) {
        if (walk == null) { simPosition = null; return@LaunchedEffect }
        renderer.mapCamera.flyToPoint(walk.startX, walk.startZ, maxDistance = 450f)
        renderer.requestRender()
        notice = context.getString(R.string.simulating_on, walkLabel)
        val start = SystemClock.uptimeMillis()
        val out = FloatArray(2)
        while (true) {
            walk.positionAt((SystemClock.uptimeMillis() - start) / 1000f * SimulatedWalk.SPEED, out)
            simPosition = UserPosition(out[0], out[1], 0f)
            delay(33) // ~30 Hz: solo se redibuja el punto, no la escena 3D
        }
    }

    LaunchedEffect(notice) {
        if (notice != null) { delay(4_500); notice = null }
    }

    // ---- navegación a pie: grafo por parque (en segundo plano), sesión por destino, salida por GPS o simulada.
    val navGraph by produceState<NavGraph?>(null, park) {
        value = park?.let { p -> withContext(Dispatchers.Default) { runCatching { buildGraph(p) }.getOrNull() } }
    }
    var navTarget by remember(parkKey) { mutableStateOf<ParkData.Poi?>(null) }
    var navPos by remember { mutableStateOf<UserPosition?>(null) }
    val navText = remember(context, lang) { NavSession.translator(context) }
    fun navFix(): Fix? {
        val sim = simPosition ?: navPos
        if (sim != null) return Fix(sim.x.toDouble(), sim.z.toDouble(), sim.accuracy.toDouble())
        return tracker.fix?.let { f -> park?.toLocal(f.lat, f.lng)?.let { Fix(it[0].toDouble(), it[1].toDouble(), f.accuracy.toDouble()) } }
    }
    fun navError(): StartReason? = when {
        !tracker.hasPermission() -> StartReason.DENIED
        !tracker.isEnabled() -> StartReason.UNAVAILABLE
        else -> null
    }
    val nav = remember(navTarget, navGraph, lang) {
        val target = navTarget; val graph = navGraph; val p = park
        if (target == null || graph == null || p == null) null
        else NavSession(p, graph, target, navText, lang).also { it.plan(navFix(), navError()) }
    }
    val navRef = rememberUpdatedState(nav)
    // Con permiso, la ubicación real se sigue mientras hay navegación.
    LaunchedEffect(navTarget) { if (navTarget != null && tracker.hasPermission()) tracking = true }
    // Primera lectura del GPS en la vista previa: replanea desde ahí.
    LaunchedEffect(nav, tracker.fix) {
        val n = nav ?: return@LaunchedEffect
        if (n.phase == NavSession.Phase.PREVIEW && n.start?.reason == StartReason.WAITING && tracker.fix != null) n.plan(navFix(), null)
    }
    // Línea de la ruta en el mapa y encuadre de la vista previa.
    LaunchedEffect(nav, nav?.route) {
        val r = nav?.route
        renderer.setNavRoute(r?.points?.let { pts -> FloatArray(pts.size * 2) { i -> (if (i % 2 == 0) pts[i / 2].x else pts[i / 2].z).toFloat() } })
        if (r != null && nav.phase == NavSession.Phase.PREVIEW) {
            // La hoja tapa ~la mitad de abajo: se encuadra la ruta más un tramo igual hacia la parte de abajo
            // de la pantalla (hacia la cámara), así la ruta queda en lo que la hoja deja libre.
            var x0 = r.points.minOf { it.x }.toFloat(); var x1 = r.points.maxOf { it.x }.toFloat()
            var z0 = r.points.minOf { it.z }.toFloat(); var z1 = r.points.maxOf { it.z }.toFloat()
            val size = maxOf(x1 - x0, z1 - z0, 80f)
            val yaw = renderer.mapCamera.yaw
            val bx = (x0 + x1) / 2 + kotlin.math.sin(yaw) * size * 1.3f
            val bz = (z0 + z1) / 2 + kotlin.math.cos(yaw) * size * 1.3f
            x0 = minOf(x0, bx); x1 = maxOf(x1, bx); z0 = minOf(z0, bz); z1 = maxOf(z1, bz)
            renderer.mapCamera.flyToBounds(x0, x1, z0, z1)
            renderer.requestRender()
        }
        navPos = nav?.start?.takeIf { it.simulated }?.let { UserPosition(it.x.toFloat(), it.z.toFloat(), 0f) }
    }
    // Seguimiento de la cámara: 22 m por delante, 170 m, inclinación 0.92, la marcha hacia arriba.
    fun followCamera(n: NavSession, x: Float, z: Float, dt: Float) {
        if (!n.following) return
        val yaw = n.headingYaw() ?: renderer.mapCamera.yaw
        val ax = x - kotlin.math.sin(yaw) * 22f; val az = z - kotlin.math.cos(yaw) * 22f
        renderer.mapCamera.follow(ax, az, yaw, 170f, 0.92f, dt)
        renderer.requestRender()
    }
    // Simulación a 1.25 m/s × velocidad, cuadro a cuadro.
    LaunchedEffect(nav, nav?.phase, nav?.simulate) {
        val n = nav ?: return@LaunchedEffect
        if (n.phase != NavSession.Phase.NAVIGATING || !n.simulate) return@LaunchedEffect
        var last = 0L
        while (n.guide?.arrived != true) {
            val t = withFrameNanos { it }
            val dt = if (last == 0L) 0.016f else ((t - last) / 1e9f).coerceIn(0f, 0.1f)
            last = t
            val p = n.stepSimulation(dt) ?: break
            navPos = UserPosition(p.x.toFloat(), p.z.toFloat(), 0f)
            renderer.setNavWalked(n.simulated)
            followCamera(n, p.x.toFloat(), p.z.toFloat(), dt)
        }
    }
    // Navegación real: cada lectura del GPS guía, recalcula si te sales y sigue con la cámara.
    LaunchedEffect(nav, nav?.phase, tracker.fix) {
        val n = nav ?: return@LaunchedEffect
        if (n.phase != NavSession.Phase.NAVIGATING || n.simulate) return@LaunchedEffect
        val f = navFix() ?: return@LaunchedEffect
        n.update(NavPoint(f.x, f.z), f)
        renderer.setNavWalked(n.guide?.along?.toFloat() ?: 0f)
        followCamera(n, f.x.toFloat(), f.z.toFloat(), 0.5f)
    }
    // Sin navegación (o al cambiar de parque) no queda línea ni pin.
    DisposableEffect(nav) { onDispose { if (navRef.value == null) { renderer.setNavRoute(null); navPos = null } } }

    // Interfaz de noche cuando el mapa pasa de la mitad de la transición (k > 0.5).
    CompositionLocalProvider(LocalMapColors provides if (renderer.nightStyle) NightMapColors else DayMapColors) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    SurfaceView(ctx).also { sv ->
                        renderer.attach(sv)
                        sv.setOnTouchListener(
                            MapGestures(
                                ctx, renderer.mapCamera, renderer::requestRender,
                                onTap = { x, y ->
                                    val n = navRef.value
                                    val g = if (n?.picking == true) renderer.mapCamera.groundAt(x, y) else null
                                    if (n != null && g != null) n.pick(g[0], g[1], navFix(), navError()) else selectedId = null
                                },
                                onLongPress = { x, y ->
                                    val n = navRef.value
                                    val g = renderer.mapCamera.groundAt(x, y)
                                    if (g != null) {
                                        if (n?.picking == true) {
                                            n.pick(g[0], g[1], navFix(), navError())
                                        } else {
                                            val currentPark = parkData
                                            if (currentPark != null) {
                                                val targetX = g[0]
                                                val targetZ = g[1]
                                                val closestPoi = currentPark.pois.minByOrNull { poi ->
                                                    val dx = poi.x - targetX
                                                    val dz = poi.z - targetZ
                                                    dx * dx + dz * dz
                                                }?.takeIf { poi ->
                                                    val dx = poi.x - targetX
                                                    val dz = poi.z - targetZ
                                                    (dx * dx + dz * dz) <= 25f * 25f
                                                }

                                                val destinationPoi = closestPoi ?: ParkData.Poi(
                                                    id = "custom_${targetX.toInt()}_${targetZ.toInt()}",
                                                    number = 0,
                                                    x = targetX,
                                                    y = 0f,
                                                    z = targetZ,
                                                    name = ctx.getString(R.string.selected_location),
                                                    category = "",
                                                    categoryKey = "other",
                                                    route = null,
                                                    routeKey = null,
                                                    extraCost = false,
                                                    active = true,
                                                )

                                                selectedId = destinationPoi.id
                                                navTarget = destinationPoi
                                            }
                                        }
                                    }
                                },
                                onGesture = { navRef.value?.following = false },
                            )
                        )
                    }
                },
            )

            LocationLayer(
                position = {
                    navPos ?: simPosition ?: tracker.fix?.let { f ->
                        parkData?.toLocal(f.lat, f.lng)?.let { UserPosition(it[0], it[1], f.accuracy) }
                    }
                },
                snapshot = { renderer.cameraSnapshot },
                surfaceAt = { x, z -> parkData?.surfaceAt(x, z) ?: 0f },
                modifier = Modifier.fillMaxSize(),
            )

            NavMarkers(nav, snapshot = { renderer.cameraSnapshot }, modifier = Modifier.fillMaxSize())

            val pois = park?.pois.orEmpty()
            val visiblePois = remember(pois, filters, activeSet, selectedId) {
                if (filters == null) emptyList() else pois.filter { filters.groupOf(it) in activeSet || it.id == selectedId }
            }
            if (renderer.loadState is ParkRenderer.LoadState.Ready && visiblePois.isNotEmpty()) {
                MarkerLayer(
                    pois = visiblePois,
                    snapshot = { renderer.cameraSnapshot },
                    selectedId = selectedId,
                    onSelect = { selectedId = it.id },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // ---- capa de interfaz (respeta barras del sistema y recortes)
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(12.dp)
            ) {
                Column(Modifier.align(Alignment.TopStart).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (nav?.phase == NavSession.Phase.NAVIGATING) NavBanner(nav)
                    else TopBar(
                        parks = parks,
                        selected = parkKey,
                        title = park?.name ?: parkName(parkKey),
                        hours = park?.hours,
                        onSelect = { parkKey = it },
                        onList = { showList = true },
                        settings = SettingsMenuState(
                            dayNight = settings.dayNight,
                            nightNow = nightNow,
                            onDayNight = settings::updateDayNight,
                            language = settings.language,
                            onLanguage = settings::updateLanguage,
                            qualityMode = settings.quality,
                            quality = renderer.quality,
                            onQuality = settings::updateQuality,
                            walkways = if (park?.walkways.isNullOrEmpty()) null else renderer.walkwaysVisible,
                            onWalkways = { renderer.showWalkways(!renderer.walkwaysVisible) },
                            shadows = renderer.shadowsEnabled,
                            shadowsAvailable = renderer.shadowsAvailable,
                            onShadows = { renderer.setShadows(!renderer.shadowsEnabled) },
                            animations = settings.animations,
                            animationsAvailable = renderer.animationsAvailable,
                            onAnimations = { settings.updateAnimations(!settings.animations) },
                            simulate = simulate,
                            onSimulate = { simulate = !simulate },
                        ),
                    )
                    if (nav?.picking == true) PickHint(onCancel = { nav.picking = false })
                    if (simulate) SimulationBadge(onStop = { simulate = false })
                    renderer.pendingKey?.let { LoadingBadge(stringResource(R.string.loading_park, parkName(it))) }
                }

                MapControls(
                    bearing = { renderer.cameraSnapshot?.bearingDegrees ?: 0f },
                    locating = simulate || tracking,
                    onZoomIn = { renderer.mapCamera.zoomBy(0.6f); renderer.requestRender() },
                    onZoomOut = { renderer.mapCamera.zoomBy(1 / 0.6f); renderer.requestRender() },
                    onCompass = { renderer.mapCamera.resetNorth(); renderer.requestRender() },
                    onRecenter = { renderer.mapCamera.goHome(); renderer.requestRender() },
                    onMyLocation = ::onMyLocation,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )

                StatusCard(
                    state = renderer.loadState,
                    parkName = park?.name ?: parkName(parkKey),
                    noParks = parks.isEmpty(),
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // ---- parte inferior: aviso, actividad seleccionada, menú de puntos y créditos del mapa
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                notice?.let { Notice(it, Modifier.padding(horizontal = 24.dp)) }
                val selected = pois.firstOrNull { it.id == selectedId }
                when {
                    nav?.phase == NavSession.Phase.NAVIGATING -> NavBottomBar(
                        nav, onEnd = { navTarget = null },
                        onRecenter = if (nav.following) null else ({ nav.following = true }),
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    nav != null -> DirectionsSheet(
                        nav, onClose = { navTarget = null }, onPick = { nav.picking = true },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    selected != null -> PoiCard(
                        selected, onClose = { selectedId = null },
                        onDirections = { navTarget = selected },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                // key(parkKey): cada parque empieza con la fila de chips al principio.
                if (groups.isNotEmpty() && nav == null) key(parkKey) {
                    GroupRow(
                        groups = groups,
                        active = activeSet,
                        onToggle = { g ->
                            if (g.key in activeSet) {
                                activeGroups = activeGroups - g.key
                            } else {
                                activeGroups = activeGroups + g.key
                                g.bounds?.let { b -> renderer.mapCamera.flyToBounds(b.x0, b.x1, b.z0, b.z1); renderer.requestRender() }
                            }
                        },
                    )
                }
                Attribution(Modifier.align(Alignment.End).padding(end = 12.dp))
            }

            if (showList && park != null) {
                ActivitiesSheet(
                    park = park,
                    onDismiss = { showList = false },
                    onPick = { poi ->
                        showList = false
                        selectedId = poi.id
                        renderer.mapCamera.flyToPoint(poi.x, poi.z, maxDistance = 220f)
                        renderer.requestRender()
                    },
                    onDirections = { poi ->
                        showList = false
                        selectedId = poi.id
                        navTarget = poi
                    },
                )
            }
        }
    }
}

@Composable
private fun parkName(key: String) = when (key) {
    "xc" -> stringResource(R.string.park_xc)
    "xh" -> stringResource(R.string.park_xh)
    "xp" -> stringResource(R.string.park_xp)
    "xs" -> stringResource(R.string.park_xs)
    "xo" -> stringResource(R.string.park_xo)
    else -> key
}

// ---------------------------------------------------------------------------- barra superior

/** Opciones del menú de ajustes (secciones Idioma, Calidad gráfica y Mapa). */
private class SettingsMenuState(
    val dayNight: AppSettings.DayNight,
    /** Si el modo es automático: ¿es de noche ahora mismo? */
    val nightNow: Boolean,
    val onDayNight: (AppSettings.DayNight) -> Unit,
    val language: AppSettings.Language,
    val onLanguage: (AppSettings.Language) -> Unit,
    val qualityMode: AppSettings.QualityMode,
    /** Nivel en uso (en Auto puede ser menor que el inicial). */
    val quality: ParkRenderer.Quality,
    val onQuality: (AppSettings.QualityMode) -> Unit,
    /** null si el parque no tiene andadores. */
    val walkways: Boolean?,
    val onWalkways: () -> Unit,
    val shadows: Boolean,
    val shadowsAvailable: Boolean,
    val onShadows: () -> Unit,
    val animations: Boolean,
    /** No en calidad Baja. */
    val animationsAvailable: Boolean,
    val onAnimations: () -> Unit,
    val simulate: Boolean,
    val onSimulate: () -> Unit,
)

/**
 * Barra superior: lista de actividades (izquierda), píldora del parque con su horario que abre el
 * selector de parque, y ajustes (derecha). Mismo estilo que el menú móvil de la web.
 */
@Composable
private fun TopBar(
    parks: List<String>,
    selected: String,
    title: String,
    hours: ParkData.Hours?,
    onSelect: (String) -> Unit,
    onList: () -> Unit,
    settings: SettingsMenuState,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(stringResource(R.string.activities_list), onClick = onList) { ListGlyph() }
        Spacer(Modifier.width(8.dp))
        // La píldora ocupa lo que necesita dentro del espacio libre entre los dos botones.
        Box(Modifier.weight(1f)) {
            var open by remember { mutableStateOf(false) }
            val canSwitch = parks.size > 1
            val switchLabel = stringResource(R.string.park_switcher)
            Surface(
                onClick = { if (canSwitch) open = true },
                enabled = canSwitch,
                shape = CircleShape,
                color = panel,
                shadowElevation = 3.dp,
                modifier = Modifier.height(44.dp).semantics { contentDescription = "$title. $switchLabel" },
            ) {
                Row(Modifier.padding(start = 16.dp, end = if (canSwitch) 10.dp else 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = ink, maxLines = 1)
                    if (hours != null) {
                        Box(Modifier.padding(horizontal = 8.dp).size(3.dp).background(ink.copy(alpha = 0.35f), CircleShape))
                        Text(
                            stringResource(R.string.hours_short, hours.open, hours.close),
                            style = MaterialTheme.typography.labelMedium, color = ink.copy(alpha = 0.62f), maxLines = 1,
                        )
                    }
                    if (canSwitch) {
                        Spacer(Modifier.width(4.dp))
                        Chevron(open)
                    }
                }
            }
            Menu(open, onDismiss = { open = false }) {
                parks.forEach { key ->
                    ChoiceItem(parkName(key), key == selected) { open = false; if (key != selected) onSelect(key) }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Box {
            var open by remember { mutableStateOf(false) }
            RoundButton(stringResource(R.string.settings), onClick = { open = true }) { TuneGlyph() }
            Menu(open, onDismiss = { open = false }) {
                SettingsMenuContent(settings, close = { open = false })
            }
        }
    }
}

@Composable
private fun SettingsMenuContent(s: SettingsMenuState, close: () -> Unit) {
    MenuSection(stringResource(R.string.section_mode))
    val autoNow = stringResource(if (s.nightNow) R.string.mode_night else R.string.mode_day)
    val autoMode = s.dayNight == AppSettings.DayNight.AUTO
    ChoiceItem(if (autoMode) stringResource(R.string.mode_auto_current, autoNow) else stringResource(R.string.mode_auto), autoMode) {
        s.onDayNight(AppSettings.DayNight.AUTO)
    }
    ChoiceItem(stringResource(R.string.mode_day), s.dayNight == AppSettings.DayNight.DAY) { s.onDayNight(AppSettings.DayNight.DAY) }
    ChoiceItem(stringResource(R.string.mode_night), s.dayNight == AppSettings.DayNight.NIGHT) { s.onDayNight(AppSettings.DayNight.NIGHT) }
    HorizontalDivider(color = LocalMapColors.current.divider)

    MenuSection(stringResource(R.string.section_language))
    ChoiceItem(stringResource(R.string.language_es), s.language == AppSettings.Language.ES) { s.onLanguage(AppSettings.Language.ES) }
    ChoiceItem(stringResource(R.string.language_en), s.language == AppSettings.Language.EN) { s.onLanguage(AppSettings.Language.EN) }
    HorizontalDivider(color = LocalMapColors.current.divider)

    MenuSection(stringResource(R.string.section_quality))
    val levelName = stringResource(
        when (s.quality) {
            ParkRenderer.Quality.HIGH -> R.string.quality_high
            ParkRenderer.Quality.MEDIUM -> R.string.quality_medium
            ParkRenderer.Quality.LOW -> R.string.quality_low
        }
    )
    val auto = s.qualityMode == AppSettings.QualityMode.AUTO
    ChoiceItem(if (auto) stringResource(R.string.quality_auto_current, levelName) else stringResource(R.string.quality_auto), auto) {
        s.onQuality(AppSettings.QualityMode.AUTO)
    }
    ChoiceItem(stringResource(R.string.quality_high), s.qualityMode == AppSettings.QualityMode.HIGH) { s.onQuality(AppSettings.QualityMode.HIGH) }
    ChoiceItem(stringResource(R.string.quality_medium), s.qualityMode == AppSettings.QualityMode.MEDIUM) { s.onQuality(AppSettings.QualityMode.MEDIUM) }
    ChoiceItem(stringResource(R.string.quality_low), s.qualityMode == AppSettings.QualityMode.LOW) { s.onQuality(AppSettings.QualityMode.LOW) }
    HorizontalDivider(color = LocalMapColors.current.divider)

    MenuSection(stringResource(R.string.section_map))
    if (s.walkways != null) ChoiceItem(stringResource(R.string.walkways), s.walkways) { s.onWalkways() }
    ChoiceItem(stringResource(R.string.shadows), s.shadows && s.shadowsAvailable, enabled = s.shadowsAvailable) { s.onShadows() }
    ChoiceItem(stringResource(R.string.animations), s.animations && s.animationsAvailable, enabled = s.animationsAvailable) { s.onAnimations() }
    ChoiceItem(stringResource(R.string.simulate_location), s.simulate) { close(); s.onSimulate() }
}

/** Menú desplegable blanco con esquinas de 4 dp, como el de la web. */
@Composable
private fun Menu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(4.dp),
        containerColor = surface,
        shadowElevation = 6.dp,
    ) {
        PopupLanguage { Column { content() } }
    }
}

/** Título de sección del menú: pequeño, en mayúsculas. */
@Composable
private fun MenuSection(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        letterSpacing = 0.8.sp,
        fontWeight = FontWeight.SemiBold,
        color = ink.copy(alpha = 0.55f),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** Fila de 48 dp; la opción activa va en negritas con palomita teal. */
@Composable
private fun ChoiceItem(label: String, checked: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal) },
        onClick = onClick,
        enabled = enabled,
        colors = MenuDefaults.itemColors(textColor = ink, disabledTextColor = ink.copy(alpha = 0.38f)),
        trailingIcon = { if (checked) CheckGlyph() else Spacer(Modifier.size(18.dp)) },
        modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 220.dp).semantics { selected = checked },
    )
}

/** Aviso discreto mientras el parque elegido carga fuera de escena (el anterior sigue visible). */
@Composable
private fun LoadingBadge(text: String) {
    Surface(shape = CircleShape, color = panel, shadowElevation = 2.dp) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Teal)
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, color = ink)
        }
    }
}

@Composable
private fun SimulationBadge(onStop: () -> Unit) {
    val stop = stringResource(R.string.stop_simulation)
    Surface(shape = CircleShape, color = panel, shadowElevation = 2.dp) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(LocationBlue, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.simulated_badge), style = MaterialTheme.typography.labelMedium, color = ink)
            Box(
                Modifier
                    .size(36.dp)
                    .clickable(onClick = onStop)
                    .semantics { contentDescription = stop },
                contentAlignment = Alignment.Center,
            ) { Text("✕", fontSize = 13.sp, color = ink.copy(alpha = 0.6f)) }
        }
    }
}

// ---------------------------------------------------------------------------- menú de puntos

/** Fila desplazable de chips: cada ruta (con su línea de color) o categoría; activa = se ven sus puntos. */
@Composable
private fun GroupRow(groups: List<PoiFilters.Group>, active: Set<String>, onToggle: (PoiFilters.Group) -> Unit) {
    val other = stringResource(R.string.other_pois)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(groups, key = { it.key }) { g ->
            GroupChip(g, g.label ?: other, selected = g.key in active, onClick = { onToggle(g) })
        }
    }
}

@Composable
private fun GroupChip(group: PoiFilters.Group, label: String, selected: Boolean, onClick: () -> Unit) {
    val color = Color(group.color)
    // Para la línea blanca el acento es su contorno.
    val accent = if (color.luminance() > 0.8f) Color(group.casing) else color
    val description = stringResource(R.string.group_description, label, group.count)
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) lerp(surface, accent, 0.2f) else panel,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) accent else ink.copy(alpha = 0.10f)),
        shadowElevation = 2.dp,
        modifier = Modifier.height(38.dp).semantics(mergeDescendants = true) {
            contentDescription = description
            this.selected = selected
        },
    ) {
        Row(Modifier.padding(start = 10.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Swatch(group)
            Spacer(Modifier.width(8.dp))
            Text(
                label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
            Spacer(Modifier.width(6.dp))
            Text(group.count.toString(), style = MaterialTheme.typography.labelMedium, color = ink.copy(alpha = 0.5f))
        }
    }
}

/** Muestra de la línea (ruta, con contorno) o un punto de color (categoría). */
@Composable
private fun Swatch(group: PoiFilters.Group) {
    if (group.kind == PoiFilters.Kind.ROUTE) {
        Canvas(Modifier.size(width = 22.dp, height = 10.dp)) {
            val y = size.height / 2f
            val a = Offset(3.dp.toPx(), y); val b = Offset(size.width - 3.dp.toPx(), y)
            drawLine(Color(group.casing), a, b, strokeWidth = 7.dp.toPx(), cap = StrokeCap.Round)
            drawLine(Color(group.color), a, b, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        }
    } else {
        Canvas(Modifier.size(10.dp)) { drawCircle(Color(group.color)) }
    }
}

// ---------------------------------------------------------------------------- avisos y créditos

@Composable
private fun Notice(text: String, modifier: Modifier = Modifier) {
    // Aviso siempre oscuro (se lee igual sobre el mapa de día o de noche).
    Surface(shape = RoundedCornerShape(18.dp), color = Color(0xE61F2A2E), contentColor = Color.White, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    }
}

/** Crédito obligatorio de OpenStreetMap; abre la página de derechos de autor. */
@Composable
private fun Attribution(modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    Text(
        stringResource(R.string.osm_attribution),
        style = MaterialTheme.typography.labelSmall,
        fontSize = 10.sp,
        color = ink.copy(alpha = 0.75f),
        modifier = modifier
            .background(panel.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
            .clickable { runCatching { uri.openUri(GroundTiles.ATTRIBUTION_URL) } }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// ---------------------------------------------------------------------------- controles del mapa

@Composable
private fun MapControls(
    bearing: () -> Float,
    locating: Boolean,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onCompass: () -> Unit,
    onRecenter: () -> Unit,
    onMyLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(shape = RoundedCornerShape(24.dp), color = surface, shadowElevation = 3.dp) {
            Column {
                ControlButton(stringResource(R.string.zoom_in), onZoomIn) { GlyphText("+") }
                ControlButton(stringResource(R.string.zoom_out), onZoomOut) { GlyphText("−") }
            }
        }
        RoundButton(stringResource(R.string.compass), onCompass, size = 48) {
            // bearing() se lee en la fase de dibujo: girar el mapa solo redibuja esta brújula.
            val south = ink.copy(alpha = 0.35f)
            Canvas(Modifier.size(26.dp)) {
                val deg = -bearing()
                val c = center
                val r = size.minDimension / 2f
                rotate(deg, c) {
                    val north = Path().apply {
                        moveTo(c.x, c.y - r); lineTo(c.x + r * 0.32f, c.y); lineTo(c.x - r * 0.32f, c.y); close()
                    }
                    val southPath = Path().apply {
                        moveTo(c.x, c.y + r); lineTo(c.x + r * 0.32f, c.y); lineTo(c.x - r * 0.32f, c.y); close()
                    }
                    drawPath(north, Sunset)
                    drawPath(southPath, south)
                }
            }
        }
        RoundButton(stringResource(R.string.recenter), onRecenter, size = 48) {
            // Esquinas de encuadre.
            val c = ink
            Canvas(Modifier.size(20.dp)) {
                val s = 2.dp.toPx(); val l = size.width * 0.32f; val w = size.width; val h = size.height
                for ((x, y, dx, dy) in listOf(
                    floatArrayOf(0f, 0f, 1f, 1f), floatArrayOf(w, 0f, -1f, 1f),
                    floatArrayOf(0f, h, 1f, -1f), floatArrayOf(w, h, -1f, -1f),
                )) {
                    drawLine(c, Offset(x, y), Offset(x + dx * l, y), s, cap = StrokeCap.Round)
                    drawLine(c, Offset(x, y), Offset(x, y + dy * l), s, cap = StrokeCap.Round)
                }
            }
        }
        RoundButton(stringResource(R.string.my_location), onMyLocation, size = 48) {
            val tint = if (locating) LocationBlue else ink
            Canvas(Modifier.size(24.dp)) {
                val r = size.minDimension / 2f
                drawCircle(tint, radius = r * 0.62f, style = Stroke(width = 2.dp.toPx()))
                drawCircle(tint, radius = r * if (locating) 0.32f else 0.2f)
                val s = 2.dp.toPx()
                drawLine(tint, Offset(center.x, 0f), Offset(center.x, r * 0.38f), s)
                drawLine(tint, Offset(center.x, size.height), Offset(center.x, size.height - r * 0.38f), s)
                drawLine(tint, Offset(0f, center.y), Offset(r * 0.38f, center.y), s)
                drawLine(tint, Offset(size.width, center.y), Offset(size.width - r * 0.38f, center.y), s)
            }
        }
    }
}

@Composable
private fun RoundButton(description: String, onClick: () -> Unit, size: Int = 44, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = surface,
        shadowElevation = 3.dp,
        modifier = Modifier.size(size.dp).semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun ControlButton(description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        modifier = Modifier.size(48.dp).semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun GlyphText(glyph: String) {
    Text(glyph, fontSize = 24.sp, fontWeight = FontWeight.Medium, color = ink)
}

@Composable
private fun Chevron(open: Boolean) {
    val c = ink
    Canvas(Modifier.size(16.dp)) {
        val w = size.width; val h = size.height; val s = 1.8.dp.toPx()
        val y0 = if (open) h * 0.62f else h * 0.38f
        val y1 = if (open) h * 0.38f else h * 0.62f
        drawLine(c, Offset(w * 0.25f, y0), Offset(w * 0.5f, y1), s, cap = StrokeCap.Round)
        drawLine(c, Offset(w * 0.5f, y1), Offset(w * 0.75f, y0), s, cap = StrokeCap.Round)
    }
}

/** Ícono de lista: tres renglones. */
@Composable
private fun ListGlyph() {
    val c = ink
    Canvas(Modifier.size(20.dp)) {
        val s = 2.dp.toPx()
        for (i in 0..2) {
            val y = size.height * (0.22f + 0.28f * i)
            drawLine(c, Offset(size.width * 0.08f, y), Offset(size.width * 0.92f, y), s, cap = StrokeCap.Round)
        }
    }
}

/** Ícono de ajustes: tres deslizadores. */
@Composable
private fun TuneGlyph() {
    val c = ink; val fill = surface
    Canvas(Modifier.size(20.dp)) {
        val s = 2.dp.toPx(); val w = size.width
        val knobs = floatArrayOf(0.68f, 0.3f, 0.55f)
        knobs.forEachIndexed { i, k ->
            val y = size.height * (0.2f + 0.3f * i)
            drawLine(c, Offset(0f, y), Offset(w, y), s, cap = StrokeCap.Round)
            drawCircle(fill, radius = 3.6.dp.toPx(), center = Offset(w * k, y))
            drawCircle(c, radius = 3.6.dp.toPx(), center = Offset(w * k, y), style = Stroke(s))
        }
    }
}

@Composable
private fun CheckGlyph() {
    Canvas(Modifier.size(18.dp)) {
        val s = 2.dp.toPx(); val w = size.width; val h = size.height
        drawLine(Teal, Offset(w * 0.18f, h * 0.52f), Offset(w * 0.42f, h * 0.76f), s, cap = StrokeCap.Round)
        drawLine(Teal, Offset(w * 0.42f, h * 0.76f), Offset(w * 0.84f, h * 0.28f), s, cap = StrokeCap.Round)
    }
}

// ---------------------------------------------------------------------------- tarjetas

@Composable
private fun StatusCard(state: ParkRenderer.LoadState, parkName: String, noParks: Boolean, modifier: Modifier = Modifier) {
    val (title, body, progress) = when {
        noParks -> Triple(stringResource(R.string.missing_title), stringResource(R.string.no_parks), null)
        state is ParkRenderer.LoadState.Idle -> Triple(stringResource(R.string.loading_park, parkName), null, -1f)
        state is ParkRenderer.LoadState.Loading -> Triple(
            stringResource(R.string.loading_park, parkName),
            stringResource(R.string.loading_progress, (state.progress * 100).toInt()),
            state.progress,
        )
        state is ParkRenderer.LoadState.Missing -> Triple(stringResource(R.string.missing_title), stringResource(R.string.missing_body, state.key), null)
        state is ParkRenderer.LoadState.Error -> Triple(stringResource(R.string.error_title), state.message, null)
        else -> return
    }
    Card(
        modifier = modifier.padding(horizontal = 60.dp).widthIn(max = 320.dp),
        colors = CardDefaults.cardColors(containerColor = panel, contentColor = ink),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium)
            when {
                progress == null -> {}
                progress < 0f -> LinearProgressIndicator(Modifier.fillMaxWidth())
                else -> LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun PoiCard(poi: ParkData.Poi, onClose: () -> Unit, onDirections: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = surface, contentColor = ink),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = if (poi.extraCost) Sunset else Teal, contentColor = Color.White, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) { Text(poi.number.toString(), fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(poi.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (poi.category.isNotEmpty()) Text(poi.category, style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.7f))
                val costLabel = stringResource(
                    when {
                        !poi.active -> R.string.poi_inactive
                        poi.extraCost -> R.string.poi_extra_cost
                        else -> R.string.poi_included
                    }
                )
                Text(costLabel, style = MaterialTheme.typography.labelMedium, color = if (poi.extraCost) Sunset else Teal)
                poi.route?.let { Text(stringResource(R.string.poi_route, it), style = MaterialTheme.typography.labelSmall) }
            }
            Column(horizontalAlignment = Alignment.End) {
                Button(onClick = onDirections) { Text(stringResource(R.string.nav_directions)) }
                TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
            }
        }
    }
}
