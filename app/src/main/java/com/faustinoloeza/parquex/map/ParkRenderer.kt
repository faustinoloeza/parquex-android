package com.faustinoloeza.parquex.map

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Choreographer
import android.view.Display
import android.view.Surface
import android.view.SurfaceView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.Colors
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.RenderableManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.ToneMapper
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.faustinoloeza.parquex.data.Lamps
import com.faustinoloeza.parquex.data.ParkAnimations
import com.faustinoloeza.parquex.data.ParkContext
import com.faustinoloeza.parquex.data.ParkData
import com.faustinoloeza.parquex.data.ParkNight
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Dueño de todos los objetos de Filament: Engine, Renderer, Scene, View, Camera, SwapChain
 * (vía [UiHelper]) y el cargador glTF (gltfio). Todo se usa desde el hilo principal.
 *
 * **Render a demanda**: solo se programa un cuadro con Choreographer cuando algo cambia (cámara,
 * carga de texturas, animación, tamaño de la superficie). Con el mapa quieto no se dibuja nada.
 * Tope de 60 fps aunque la pantalla vaya a 90/120 Hz. Única excepción: las piezas animadas
 * ([AnimatedModels], solo en Alta y Media) piden cuadros a [ANIM_FRAME_NS] mientras alguna se vea.
 *
 * Ciclo de vida: crear en Compose con `remember`, [attach] a un SurfaceView y [destroy] en
 * `DisposableEffect.onDispose`.
 */
class ParkRenderer(private val context: Context) : UiHelper.RendererCallback, Choreographer.FrameCallback {

    /** Nivel de calidad gráfica efectivo (los presets, en [PRESETS]). */
    enum class Quality { HIGH, MEDIUM, LOW }

    /**
     * Preset de calidad, equivalente a `QUALITY_PRESETS` de la web.
     * @param idleScale escala de render con la vista quieta
     * @param movingScale escala mínima mientras se mueve (resolución dinámica)
     * @param msaa MSAA 2× (en lugar de FXAA)
     * @param fps tope de cuadros por segundo
     * @param shadows se permiten sombras en tiempo real
     * @param mapDetail multiplicador del umbral de detalle de las teselas OSM (más = más gruesas)
     */
    private class Preset(
        val idleScale: Float, val movingScale: Float, val msaa: Boolean, val fps: Int,
        val shadows: Boolean, val mapDetail: Float,
    )

    sealed interface LoadState {
        data object Idle : LoadState
        data class Loading(val progress: Float) : LoadState
        data object Ready : LoadState
        /** No existe `assets/parks/<key>/[MODEL_FILE]` (aún no se exporta). */
        data class Missing(val key: String) : LoadState
        data class Error(val message: String) : LoadState
    }

    companion object {
        private const val TAG = "ParkRenderer"

        /**
         * Modelo del parque en `assets/parks/<key>/` (`npm run export:mobile` en la web): solo modelos 3D,
         * árboles fusionados por zona (gltfio no dibuja EXT_mesh_gpu_instancing) y la capa `shadows`.
         */
        const val MODEL_FILE = "park-models.glb"
        init { Gltfio.init() } // carga filament-jni y gltfio-jni

        /** Tiempo sin cambios antes de dibujar el cuadro final a resolución completa. */
        private const val SETTLE_NS = 150_000_000L
        /** Intervalo de las animaciones con la vista quieta: 30 fps. */
        private const val ANIM_FRAME_NS = 1_000_000_000L / 30
        /** Margen para el jitter del vsync al decidir si ya toca el siguiente cuadro de animación. */
        private const val ANIM_SLACK_NS = 4_000_000L

        private val PRESETS = mapOf(
            Quality.HIGH to Preset(idleScale = 1f, movingScale = 0.6f, msaa = true, fps = 60, shadows = true, mapDetail = 1f),
            Quality.MEDIUM to Preset(idleScale = 0.75f, movingScale = 0.5f, msaa = false, fps = 60, shadows = true, mapDetail = 1.25f),
            Quality.LOW to Preset(idleScale = 0.55f, movingScale = 0.45f, msaa = false, fps = 30, shadows = false, mapDetail = 1.6f),
        )
        /** Cuadros en movimiento que se promedian antes de decidir si bajar la calidad (modo auto). */
        private const val AUTO_WINDOW = 45
        /** Baja un nivel si el intervalo promedio supera el objetivo por este factor. */
        private const val AUTO_SLOW = 1.5f

        /**
         * Nivel inicial para "Auto" según el dispositivo (como `detectQuality` de la web): RAM,
         * núcleos y si el sistema lo marca como de poca memoria. Luego [setQuality] con auto = true
         * lo baja solo si los cuadros se alargan al mover.
         */
        fun detectQuality(context: Context): Quality {
            val am = context.getSystemService(ActivityManager::class.java) ?: return Quality.MEDIUM
            val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val gb = mem.totalMem / 1_000_000_000.0
            val cores = Runtime.getRuntime().availableProcessors()
            return when {
                am.isLowRamDevice || gb <= 3.0 || cores <= 4 -> Quality.LOW
                gb <= 6.0 || cores < 8 -> Quality.MEDIUM
                else -> Quality.HIGH
            }
        }

        // Colores e intensidades del proyecto web (src/app/Stage.js, shared/scene.js).
        // Luz de día por defecto (= night.json → lighting.day); de noche se interpola con night.json.
        private val DEFAULT_DAY = ParkNight.Lighting(
            sky = ParkNight.linear(0xE3EDF2), hemiSky = ParkNight.linear(0xF3F7FF), hemiGround = ParkNight.linear(0x8C9C70),
            hemi = 1.6f, sun = ParkNight.linear(0xFFF0D9), sunIntensity = 2.3f, exposure = 1.06f,
        )
        private val SUN_DIRECTION = floatArrayOf(-0.55f, 1.3f, 0.5f) // hacia el sol (la luna usa la misma)

        /**
         * Calibración global de los emisivos de noche (ventanas, mapa de luz) frente a la web (three.js suma el
         * emisivo al color lineal antes del tone mapping). Único valor de noche escrito a mano.
         */
        private const val EMISSIVE_SCALE = 1f

        /**
         * Nodos que no se dibujan: las rutas (si vinieran) las dibuja [RouteLayer]; los prototipos
         * `proto:*` solo sirven de molde a [InstancedModels] y las piezas `anim:*` a [AnimatedModels].
         */
        internal val HIDDEN_NODES = arrayOf("route:", InstancedModels.PROTO_PREFIX, AnimatedModels.PREFIX)
        /** Copias de los prototipos, junto al modelo. */
        private const val INSTANCES_FILE = "instances.bin"

        /** Suelo ilustrado del parque (con sombras horneadas): si viene, el mapa OSM solo va afuera. */
        internal const val TERRAIN_PREFIX = "terrain"
        private const val WATER_NODE = "water"
        /** Empedrado de andadores y plazas, sobre el terreno o sobre el mapa. */
        private const val PAVING_NODE = "paving"
        /** Sombras horneadas como capa aparte (variante sin terreno): plano translúcido sobre el mapa. */
        private const val SHADOWS_NODE = "shadows"

        // Canales de dibujo (Filament dibuja canal por canal, en orden):
        //   0 suelo del parque: fondo (prioridad 0) → teselas OSM opacas (1) → terreno, que escribe
        //     profundidad (2) → empedrado (3) → translúcidos: agua (2), sombras (4) y teselas OSM del
        //     borde (6) →
        //   1 contexto ([ParkBundle]): suelo de la región y de los parques vecinos, mezclado y sin
        //     profundidad (región primero, vecino más cercano al final) y su agua (7) →
        //   2 árboles (del parque y de contexto) → 3 andadores ([WalkwayLayer]) → 4 rutas
        //     ([RouteLayer]); ambos sin profundidad, encima de los árboles → 5 edificios y demás
        //     modelos (tapan andadores y rutas que quedan detrás de ellos).
        internal const val CHANNEL_CONTEXT = 1
        internal const val CHANNEL_TREES = 2
        internal const val CHANNEL_MODELS = 5
        private const val PRIORITY_TERRAIN = 2
        private const val PRIORITY_PAVING = 3
        private const val PRIORITY_SHADOWS = 4
        internal const val PRIORITY_CONTEXT_WATER = 7

        /** Canal, prioridad, sombras y profundidad de un nodo de `park-models.glb`. */
        internal fun configureParkNode(rcm: RenderableManager, ri: Int, name: String) {
            val ground = name.startsWith(TERRAIN_PREFIX) || name == WATER_NODE
            if (ground || name == PAVING_NODE || name == SHADOWS_NODE) {
                // Capas del suelo: en el canal del mapa; no proyectan sombra.
                rcm.setChannel(ri, GroundTiles.CHANNEL)
                rcm.setCastShadows(ri, false)
                rcm.setReceiveShadows(ri, name != SHADOWS_NODE)
                rcm.setPriority(ri, when (name) {
                    PAVING_NODE -> PRIORITY_PAVING
                    SHADOWS_NODE -> PRIORITY_SHADOWS
                    else -> PRIORITY_TERRAIN
                })
                // El terreno escribe profundidad (los modelos se ordenan contra él); las capas
                // planas van encima sin prueba de profundidad, así no hay z-fighting.
                if (!ground) for (p in 0 until rcm.getPrimitiveCount(ri)) {
                    rcm.getMaterialInstanceAt(ri, p).apply { setDepthWrite(false); setDepthCulling(false) }
                }
            } else {
                rcm.setCastShadows(ri, true)
                rcm.setReceiveShadows(ri, true)
                rcm.setChannel(ri, if (name.startsWith("trees")) CHANNEL_TREES else CHANNEL_MODELS)
            }
        }

        /**
         * Nodo de `context.glb`: suelo (`ctx-ground:*`) mezclado sin prueba ni escritura de
         * profundidad (las alturas de dos parques difieren hasta ~2 m), o agua (`ctx-water:*`)
         * mezclada con prueba de profundidad y sin escribirla. Ninguno proyecta ni recibe sombras.
         */
        internal fun configureContextNode(rcm: RenderableManager, ri: Int, water: Boolean, priority: Int) {
            rcm.setChannel(ri, CHANNEL_CONTEXT)
            rcm.setPriority(ri, priority)
            rcm.setCastShadows(ri, false)
            rcm.setReceiveShadows(ri, false)
            for (p in 0 until rcm.getPrimitiveCount(ri)) {
                rcm.getMaterialInstanceAt(ri, p).apply {
                    setDepthWrite(false)
                    if (!water) setDepthCulling(false)
                }
            }
        }
    }

    // ---- estado observable desde Compose
    var loadState by mutableStateOf<LoadState>(LoadState.Idle)
        private set
    /** Última vista de la cámara; cambia solo cuando la cámara se mueve (marcadores). */
    var cameraSnapshot by mutableStateOf<CameraSnapshot?>(null)
        private set
    var shadowsEnabled by mutableStateOf(false)
        private set
    var walkwaysVisible by mutableStateOf(true)
        private set
    /** Calidad gráfica en uso (en modo auto puede bajar sola). */
    var quality by mutableStateOf(Quality.HIGH)
        private set
    /** Parque que se está cargando fuera de escena mientras sigue visible el anterior (o null). */
    var pendingKey by mutableStateOf<String?>(null)
        private set
    private var preset = PRESETS.getValue(Quality.HIGH)
    private var autoQuality = false
    /** Animaciones pedidas en Ajustes (se mueven solo si además la calidad no es Baja). */
    private var animationsWanted = true

    val mapCamera = MapCamera()

    // ---- Filament
    val engine: Engine = Engine.create()
    private val renderer: Renderer = engine.createRenderer()
    private val scene: Scene = engine.createScene()
    private val view: View = engine.createView()
    private val cameraEntity = EntityManager.get().create()
    private val camera: Camera = engine.createCamera(cameraEntity)
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private var swapChain: SwapChain? = null

    private val materialProvider = UbershaderProvider(engine)
    private val assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
    /** Un cargador asíncrono por GLB (modelo del parque y paquete de contexto). */
    private val parkLoader = ResourceLoader(engine)
    private val contextLoader = ResourceLoader(engine)
    private val unlit = UnlitMaterials(materialProvider)
    private val ground = GroundTiles(context, engine, scene, unlit, ::requestRender)
    private val routes = RouteLayer(engine, scene, unlit, context.resources.displayMetrics.density)
    private val walkways = WalkwayLayer(engine, scene, unlit, context.resources.displayMetrics.density)
    private val lamps = LampLayer(engine, scene, unlit, context.resources.displayMetrics.density)
    private val navLine = NavLineLayer(engine, scene, unlit, context.resources.displayMetrics.density)
    private val shBuffer = FloatArray(12)
    private val sunColorBuffer = FloatArray(3)
    private val skyColorBuffer = FloatArray(3)
    /** Parque en pantalla y el que carga fuera de escena para reemplazarlo. */
    private var current: ParkBundle? = null
    private var pending: ParkBundle? = null
    private var pendingShown: (() -> Unit)? = null

    private val sun = EntityManager.get().create()
    private var indirectLight: IndirectLight? = null
    private val colorGrading: ColorGrading

    // ---- día / noche: nightTarget (0/1) por modo y reloj; p avanza linealmente en 1.5 s; k = smoothstep(p).
    private var nightTarget = 0f
    private var nightP = 0f
    private var nightK = -1f
    private var lighting: Pair<ParkNight.Lighting, ParkNight.Lighting>? = null
    private var transitionSeconds = 1.5f
    private var lastNightNanos = 0L
    /** Estilo nocturno de la interfaz (k > 0.5). */
    var nightStyle by mutableStateOf(false)
        private set

    // ---- bucle de render a demanda
    private val choreographer = Choreographer.getInstance()
    private var framePosted = false
    /** El cuadro pedido es uno de animación con retraso ([postFrameAt]): un cambio lo adelanta. */
    private var frameDelayed = false
    private var lastAnimNanos = 0L
    private var dirty = true
    private var destroyed = false
    private var lastFrameNanos = 0L
    private var minFrameNs = 1_000_000_000L / 60 - 2_000_000L
    private var surface: Surface? = null
    private var displayRefresh = 60f
    // Medición de cuadros en movimiento (modo auto).
    private var autoSamples = 0
    private var autoSumNs = 0L
    private var lastMovingFrameNanos = 0L
    private var lastChangeNanos = 0L
    private var renderedCameraVersion = -1L
    private var dynamicResolutionOn = false
    private val lookAt = DoubleArray(9)

    init {
        view.scene = scene
        view.camera = camera

        // --- Rendimiento móvil: sin sombras (están horneadas en el suelo), sin MSAA, sin bloom,
        //     SSAO ni niebla; FXAA (barato) y búfer HDR de 32 bits.
        view.setShadowingEnabled(false)
        // MSAA/FXAA, escala de render y tope de fps dependen de la calidad: ver applyQuality().
        view.setBloomOptions(View.BloomOptions().apply { enabled = false })
        view.setAmbientOcclusionOptions(View.AmbientOcclusionOptions().apply { enabled = false })
        view.setFogOptions(View.FogOptions().apply { enabled = false })
        view.setScreenSpaceReflectionsOptions(View.ScreenSpaceReflectionsOptions().apply { enabled = false })
        view.setRenderQuality(View.RenderQuality().apply { hdrColorBuffer = View.QualityLevel.MEDIUM })
        view.setFrustumCullingEnabled(true)
        view.setBlendMode(View.BlendMode.OPAQUE)
        applyQuality(Quality.HIGH)

        // Tonemapping neutro (PBR Neutral = THREE.NeutralToneMapping de la web).
        colorGrading = ColorGrading.Builder().toneMapper(ToneMapper.PBRNeutral()).build(engine)
        view.setColorGrading(colorGrading)

        // --- Luces: sol (o luna) direccional + luz ambiental de hemisferio (cielo/suelo) como SH de 2 bandas.
        val toSun = SUN_DIRECTION
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .direction(-toSun[0], -toSun[1], -toSun[2])
            .castShadows(true) // solo cuesta si view.setShadowingEnabled(true)
            .shadowOptions(LightManager.ShadowOptions().apply {
                mapSize = 1024
                shadowCascades = 1
                stable = true
            })
            .build(engine, sun)
        scene.addEntity(sun)
        applyNight(0f) // luces de día

        uiHelper.renderCallback = this
        uiHelper.isOpaque = true
    }

    // ---------------------------------------------------------------------------- superficie

    fun attach(surfaceView: SurfaceView) {
        uiHelper.attachTo(surfaceView)
    }

    override fun onNativeWindowChanged(surface: Surface) {
        swapChain?.let { engine.destroySwapChain(it) }
        swapChain = engine.createSwapChain(surface, uiHelper.swapChainFlags)
        this.surface = surface
        displayRefresh = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: 60f
        renderer.setDisplayInfo(Renderer.DisplayInfo().apply { refreshRate = displayRefresh })
        applyFrameRate()
        requestRender()
    }

    /** Tope de fps del preset: salto de cuadros + intervalo de Filament + frecuencia pedida a la superficie. */
    private fun applyFrameRate() {
        val fps = preset.fps
        minFrameNs = 1_000_000_000L / fps - 2_000_000L // margen para el jitter del vsync
        renderer.setFrameRateOptions(Renderer.FrameRateOptions().apply {
            interval = max(1, (displayRefresh / fps).roundToInt())
        })
        // En pantallas de 90/120 Hz pedir menos ahorra batería.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            surface?.let { s -> runCatching { s.setFrameRate(fps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) } }
        }
    }

    override fun onDetachedFromSurface() {
        swapChain?.let {
            engine.destroySwapChain(it)
            engine.flushAndWait() // la superficie debe quedar libre antes de volver
        }
        swapChain = null
    }

    override fun onResized(width: Int, height: Int) {
        view.viewport = Viewport(0, 0, width, height)
        mapCamera.setViewport(width, height)
        requestRender()
    }

    // ---------------------------------------------------------------------------- carga

    /** Claves de parque con modelo exportado (`assets/parks/<key>/[MODEL_FILE]`). */
    fun availableParks(): List<String> = runCatching {
        context.assets.list("parks")?.filter { key ->
            context.assets.list("parks/$key")?.contains(MODEL_FILE) == true
        }?.sorted()
    }.getOrNull() ?: emptyList()

    /**
     * Carga el parque [key] (`assets/parks/<key>/[MODEL_FILE]`, sus copias y, si lo tiene, su paquete
     * de contexto) **fuera de escena**: mientras sus texturas se decodifican en segundo plano sigue
     * visible el parque anterior. Cuando todo está listo se cambia en un solo cuadro y se llama
     * [onShown]. Si el parque nuevo es vecino del anterior (paquete de contexto), la cámara pasa al
     * marco nuevo sin saltar y vuela a encuadrarlo. [park] da los límites de la cámara.
     */
    fun loadPark(key: String, park: ParkData?, onShown: () -> Unit = {}) {
        cancelPending()
        val path = "parks/$key/$MODEL_FILE"
        val exists = runCatching { context.assets.list("parks/$key")?.contains(MODEL_FILE) == true }.getOrDefault(false)
        if (!exists) {
            showNothing(park)
            loadState = LoadState.Missing(key)
            onShown()
            return
        }
        val buffer = try {
            readAsset(path)
        } catch (e: IOException) {
            Log.e(TAG, "No se pudo leer $path", e)
            showNothing(park); loadState = LoadState.Error(e.message ?: path); onShown(); return
        }
        val a = assetLoader.createAsset(buffer)
        if (a == null) {
            showNothing(park); loadState = LoadState.Error("GLB inválido: $path"); onShown(); return
        }
        // Paquete de contexto: vecinos y región en versión ligera (solo Xcaret, Xplor y Xenses).
        val ctx = ParkContext.load(context.assets, key)
        val ctxGlb = ctx?.let { runCatching { readAsset("parks/$key/${ParkContext.GLB_FILE}") }.getOrNull() }
        val ctxAsset = ctxGlb?.let(assetLoader::createAsset)
        val night = ParkNight.load(context.assets, key)
        val animations = ParkAnimations.load(context.assets, key)
        val lampsData = Lamps.load(context.assets, "parks/$key/lamps.bin") ?: Lamps.EMPTY
        val ctxLamps = ctx?.let { Lamps.load(context.assets, "parks/$key/context-lamps.bin") } ?: Lamps.EMPTY
        pending = ParkBundle(
            key, park, ctx, night, lampsData, ctxLamps, animations, engine, scene, assetLoader, parkLoader, contextLoader,
            a, buffer, readBinary("parks/$key/$INSTANCES_FILE"),
            ctxAsset, ctxGlb, ctxAsset?.let { readBinary("parks/$key/${ParkContext.INSTANCES_FILE}") },
            treeQuality(),
        )
        pendingShown = onShown
        if (current == null) loadState = LoadState.Loading(0f) else pendingKey = key
        requestRender()
    }

    /** Pone en pantalla el parque que terminó de cargar, en un solo cuadro. */
    private fun swap(next: ParkBundle) {
        val previous = current
        // Marco nuevo visto desde el anterior: x' = (x − offset.x) / offset.sx, z' = z − offset.z.
        val offset = previous?.takeIf { it.shown }?.context?.neighbor(next.key)?.offset
        previous?.destroy()
        assetLoader.gc()
        current = next
        pending = null
        pendingKey = null

        val park = next.park
        ground.setPark(park)
        ground.setDesignArea(if (next.hasTerrain) park?.designArea else null)
        ground.setRegion(next.context?.region?.clear)
        routes.setRoutes(park?.routes.orEmpty())
        walkways.setWalkways(park)
        next.show()
        // Noche: parámetros del parque nuevo y su estado actual (k) en materiales, mapa y faroles.
        next.night?.let { n -> lighting = n.day to n.night; transitionSeconds = n.transitionSeconds }
        val cap = next.night?.lampCaps?.get(treeQuality()) ?: Int.MAX_VALUE
        lamps.setLamps(next.lamps.capped(cap), next.contextLamps, next.night?.lampStyle)
        val k = nightK.coerceAtLeast(0f)
        nightK = -1f
        applyNight(k)

        if (offset != null) {
            mapCamera.reframe((mapCamera.targetX - offset.x) / offset.sx, mapCamera.targetZ - offset.z)
            fitCamera(park, next.asset, keepView = true)
            mapCamera.goHome()
        } else {
            fitCamera(park, next.asset)
        }
        loadState = LoadState.Ready
        Log.i(TAG, "Parque listo: ${next.key}, ${next.renderableCount} renderables del modelo y contexto " +
            "(${scene.renderableCount} en escena con mapa y rutas); suelo ilustrado=${next.hasTerrain}, " +
            "vecinos=${next.context?.neighbors?.joinToString { it.key } ?: "-"}")
        pendingShown?.invoke()
        pendingShown = null
        requestRender()
    }

    /** Sin modelo (falta o error): quita el parque anterior y deja solo el mapa. */
    private fun showNothing(park: ParkData?) {
        current?.destroy(); current = null
        ground.setPark(park); ground.setDesignArea(null); ground.setRegion(null)
        routes.setRoutes(park?.routes.orEmpty())
        walkways.setWalkways(park)
        fitCamera(park, null)
        requestRender()
    }

    private fun cancelPending() {
        pending?.destroy()
        pending = null
        pendingShown = null
        pendingKey = null
    }

    /** Fracción de árboles de contexto según la calidad (`context.json → trees.quality`). */
    private fun treeQuality() = when (quality) {
        Quality.HIGH -> "high"
        Quality.MEDIUM -> "medium"
        Quality.LOW -> "low"
    }

    /** Archivo binario de assets completo (little endian), o null si no hay. */
    private fun readBinary(path: String): ByteBuffer? = runCatching {
        context.assets.open(path).use { input ->
            val bytes = input.readBytes()
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN).put(bytes).also { it.flip() }
        }
    }.getOrNull()

    private fun fitCamera(park: ParkData?, a: FilamentAsset?, keepView: Boolean = false) {
        when {
            park != null -> with(park.bounds) { mapCamera.fitPark(x0, x1, z0, z1, keepView = keepView) }
            a != null -> {
                val box = a.boundingBox
                val c = box.center; val h = box.halfExtent
                mapCamera.fitPark(c[0] - h[0], c[0] + h[0], c[2] - h[2], c[2] + h[2], keepView = keepView)
            }
        }
    }

    /**
     * Mapea el GLB sin copiarlo (requiere `noCompress "glb"` en Gradle). Si el archivo viniera
     * comprimido, cae a leerlo a un ByteBuffer directo.
     */
    private fun readAsset(path: String): ByteBuffer = try {
        context.assets.openFd(path).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { ch ->
                ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    } catch (_: IOException) {
        context.assets.open(path).use { input ->
            val bytes = input.readBytes()
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.flip() }
        }
    }

    // ---------------------------------------------------------------------------- opciones

    /** Rutas resaltadas (sus puntos están visibles): más gruesas y encima de las demás. */
    fun setActiveRoutes(slugs: Set<String>) {
        routes.setActive(slugs)
        if (routes.dirty) requestRender()
    }

    /** Muestra u oculta las líneas punteadas de los andadores. */
    fun showWalkways(on: Boolean) {
        walkwaysVisible = on
        walkways.setVisible(on)
        requestRender()
    }

    fun setShadows(enabled: Boolean) {
        shadowsEnabled = enabled && preset.shadows
        view.setShadowingEnabled(shadowsEnabled)
        requestRender()
    }

    /** ¿Permite la calidad actual sombras en tiempo real? */
    val shadowsAvailable get() = preset.shadows

    /** Mueve (o congela) la torre, los voladores, las banderas, las aves y las mariposas. */
    fun setAnimations(on: Boolean) {
        animationsWanted = on
        requestRender()
    }

    /** Las animaciones se apagan en calidad Baja (como la fauna de la web). */
    val animationsAvailable get() = quality != Quality.LOW

    /**
     * Aplica la calidad [level]. Con [auto] (modo "Auto") la baja un nivel por su cuenta si, al
     * mover el mapa, los cuadros tardan bastante más que el objetivo.
     */
    fun setQuality(level: Quality, auto: Boolean) {
        autoQuality = auto
        resetAutoSamples()
        applyQuality(level)
    }

    private fun applyQuality(level: Quality) {
        quality = level
        preset = PRESETS.getValue(level)
        view.setMultiSampleAntiAliasingOptions(View.MultiSampleAntiAliasingOptions().apply {
            enabled = preset.msaa
            sampleCount = 2
        })
        view.setAntiAliasing(if (preset.msaa) View.AntiAliasing.NONE else View.AntiAliasing.FXAA)
        view.setDynamicResolutionOptions(resolution(moving = false))
        dynamicResolutionOn = false
        if (!preset.shadows && shadowsEnabled) setShadows(false)
        current?.setTreeQuality(treeQuality())
        ground.setDetailScale(preset.mapDetail)
        applyFrameRate()
        requestRender()
    }

    private fun resetAutoSamples() {
        autoSamples = 0; autoSumNs = 0L; lastMovingFrameNanos = 0L
    }

    /** Modo auto: promedia el intervalo entre cuadros en movimiento y baja un nivel si es lento. */
    private fun trackFrame(frameTimeNanos: Long, moving: Boolean) {
        if (!autoQuality || quality == Quality.LOW) return
        if (!moving) { lastMovingFrameNanos = 0L; return }
        if (lastMovingFrameNanos != 0L) {
            autoSumNs += frameTimeNanos - lastMovingFrameNanos
            autoSamples++
        }
        lastMovingFrameNanos = frameTimeNanos
        if (autoSamples < AUTO_WINDOW) return
        val average = autoSumNs / autoSamples
        val target = 1_000_000_000L / preset.fps
        resetAutoSamples()
        if (average > target * AUTO_SLOW) {
            val lower = if (quality == Quality.HIGH) Quality.MEDIUM else Quality.LOW
            Log.i(TAG, "Calidad auto: ${average / 1_000_000} ms por cuadro al mover → $lower")
            applyQuality(lower)
        }
    }

    /**
     * Ruta a pie a dibujar ([points] = x0, z0, x1, z1, … en metros) o null para quitarla. Con ruta, las
     * rutas oficiales se atenúan (más delgadas) para que destaque.
     */
    fun setNavRoute(points: FloatArray?) {
        val park = current?.park
        navLine.setRoute(points) { x, z -> park?.surfaceAt(x, z) ?: 0f }
        routes.setDimmed(points != null)
        requestRender()
    }

    /** Metros ya caminados de la ruta a pie (ese tramo se pinta en gris). */
    fun setNavWalked(meters: Float) {
        navLine.setWalked(meters)
        if (navLine.dirty) requestRender()
    }

    /** Día (false) o noche (true): transición de [ParkNight.transitionSeconds] desde el estado actual. */
    fun setNight(night: Boolean) {
        val t = if (night) 1f else 0f
        if (t == nightTarget) return
        nightTarget = t
        lastNightNanos = 0L
        requestRender()
    }

    /** Avanza la transición día ↔ noche; true si sigue en curso. */
    private fun stepNight(frameTimeNanos: Long): Boolean {
        if (nightP == nightTarget) return false
        // Hasta 0.5 s por cuadro: en un equipo lento la transición dura lo mismo, con menos pasos.
        val dt = if (lastNightNanos == 0L) 0.016f else ((frameTimeNanos - lastNightNanos) / 1e9f).coerceIn(0f, 0.5f)
        lastNightNanos = frameTimeNanos
        val step = dt / transitionSeconds.coerceAtLeast(0.01f)
        nightP = if (nightTarget > nightP) minOf(nightTarget, nightP + step) else maxOf(nightTarget, nightP - step)
        applyNight(nightP * nightP * (3 - 2 * nightP))
        if (nightP == nightTarget) lastNightNanos = 0L
        return true
    }

    /**
     * Nivel de noche [k] en todo: luces (sol/luna e hemisferio), cielo, exposición, emisivos y agua del parque,
     * teselas OSM y faroles. Colores interpolados en lineal (night.json → lighting.day/night).
     */
    private fun applyNight(k: Float) {
        if (k == nightK) return
        nightK = k
        val (day, night) = lighting ?: (DEFAULT_DAY to DEFAULT_DAY)
        fun mixVal(a: Float, b: Float) = a + (b - a) * k

        val lm = engine.lightManager
        val li = lm.getInstance(sun)
        sunColorBuffer[0] = mixVal(day.sun[0], night.sun[0])
        sunColorBuffer[1] = mixVal(day.sun[1], night.sun[1])
        sunColorBuffer[2] = mixVal(day.sun[2], night.sun[2])
        lm.setColor(li, sunColorBuffer[0], sunColorBuffer[1], sunColorBuffer[2])
        lm.setIntensity(li, mixVal(day.sunIntensity, night.sunIntensity)) // lux, con exposición ~1 (como three.js)

        // Hemisferio de three.js: E(n) = mix(suelo, cielo, n.y*0.5+0.5) * I; el BRDF Lambert divide
        // entre π. En Filament el SH ya va pre-escalado por 1/π: sh0 = media, sh1 (eje y) = semidiferencia.
        val i = mixVal(day.hemi, night.hemi) / PI.toFloat()
        for (c in 0..2) {
            val skyL = mixVal(day.hemiSky[c], night.hemiSky[c])
            val groundL = mixVal(day.hemiGround[c], night.hemiGround[c])
            shBuffer[c] = (skyL + groundL) * 0.5f * i
            shBuffer[3 + c] = (skyL - groundL) * 0.5f * i
        }
        val ibl = IndirectLight.Builder().irradiance(2, shBuffer).intensity(1f).build(engine)
        scene.indirectLight = ibl
        indirectLight?.let(engine::destroyIndirectLight)
        indirectLight = ibl

        // Cielo como color de limpieza y no Skybox: el skybox se pinta al final donde no hay
        // profundidad y taparía el mapa y las rutas, que no la escriben.
        skyColorBuffer[0] = mixVal(day.sky[0], night.sky[0])
        skyColorBuffer[1] = mixVal(day.sky[1], night.sky[1])
        skyColorBuffer[2] = mixVal(day.sky[2], night.sky[2])
        renderer.clearOptions = Renderer.ClearOptions().apply {
            setClearColor(skyColorBuffer[0].toDouble(), skyColorBuffer[1].toDouble(), skyColorBuffer[2].toDouble(), 1.0)
            clear = true
        }
        camera.setExposure(mixVal(day.exposure, night.exposure))

        current?.applyNight(k, EMISSIVE_SCALE)
        current?.night?.let { ground.setNight(k, it.mapTint) }
        lamps.setLevel(k)
        val style = k > 0.5f
        if (style != nightStyle) nightStyle = style
        navLine.setNight(style)
        dirty = true
    }

    /** Proyecta un punto del mundo (metros) a píxeles de la superficie; null si queda detrás. */
    fun projectToScreen(x: Float, y: Float, z: Float): FloatArray? {
        val out = FloatArray(2)
        return if ((cameraSnapshot ?: mapCamera.snapshot()).project(x, y, z, out) > 0f) out else null
    }

    // ---------------------------------------------------------------------------- bucle

    /** Pide un cuadro. Llamar tras cualquier cambio visible (gestos, opciones, carga). */
    fun requestRender() {
        dirty = true
        if (frameDelayed) { // no esperar al siguiente cuadro de animación
            choreographer.removeFrameCallback(this)
            framePosted = false
            frameDelayed = false
        }
        postFrame()
    }

    private fun postFrame() {
        if (framePosted || destroyed) return
        framePosted = true
        choreographer.postFrameCallback(this)
    }

    /** Pide el cuadro del vsync más cercano a [targetNanos] sin despertar en los anteriores (animaciones). */
    private fun postFrameAt(targetNanos: Long) {
        if (framePosted || destroyed) return
        framePosted = true
        frameDelayed = true
        val halfVsyncNs = (500_000_000f / displayRefresh).toLong()
        val delayMs = ((targetNanos - halfVsyncNs - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
        choreographer.postFrameCallbackDelayed(this, delayMs)
    }

    override fun doFrame(frameTimeNanos: Long) {
        framePosted = false
        frameDelayed = false
        if (destroyed) return
        var keepGoing = false

        // Parque cargando fuera de escena: texturas que terminaron de decodificarse → GPU; al
        // terminar, cambia en este cuadro.
        pending?.let { next ->
            val progress = next.update()
            if (next.loading) {
                if (current == null) loadState = LoadState.Loading(progress)
                keepGoing = true
            } else {
                swap(next)
                dirty = true
            }
        }

        if (mapCamera.update(frameTimeNanos)) keepGoing = true
        if (stepNight(frameTimeNanos)) keepGoing = true

        val sc = swapChain
        if (sc == null || !uiHelper.isReadyToRender) {
            // Sin superficie: se reanuda en onNativeWindowChanged. Seguimos solo si hay carga.
            if (pending != null) postFrame()
            return
        }

        // Tope de fps del preset.
        if (lastFrameNanos != 0L && frameTimeNanos - lastFrameNanos < minFrameNs) {
            postFrame(); return
        }

        val cameraChanged = mapCamera.version != renderedCameraVersion
        if (cameraChanged) {
            applyCamera()
            renderedCameraVersion = mapCamera.version
            cameraSnapshot = mapCamera.snapshot()
            lastChangeNanos = frameTimeNanos
            dirty = true
        }
        cameraSnapshot?.let { snap ->
            if (cameraChanged || ground.dirty) { ground.update(snap); dirty = true }
            if (cameraChanged || routes.dirty) { routes.update(snap); dirty = true }
            if (cameraChanged || walkways.dirty) { walkways.update(snap); dirty = true }
            if (cameraChanged || lamps.dirty) { lamps.update(snap); dirty = true }
            if (cameraChanged || navLine.dirty) { navLine.update(snap); dirty = true }
        }

        // Piezas animadas: solo si alguna se ve; con la vista quieta, a 30 fps (sin contar como movimiento:
        // no bajan la resolución ni la calidad automática).
        var animating = false
        val bundle = current
        val snapshot = cameraSnapshot
        if (animationsWanted && animationsAvailable && bundle != null && snapshot != null && bundle.animationsVisible(snapshot)) {
            animating = true
            if (dirty || keepGoing || frameTimeNanos - lastAnimNanos >= ANIM_FRAME_NS - ANIM_SLACK_NS) {
                bundle.animate(frameTimeNanos, nightK.coerceAtLeast(0f), snapshot)
                lastAnimNanos = frameTimeNanos
                dirty = true
            }
        }

        // Resolución dinámica solo mientras la vista se mueve; al quedarse quieta se dibuja un
        // último cuadro a la escala del preset.
        val moving = keepGoing || cameraChanged || frameTimeNanos - lastChangeNanos < SETTLE_NS
        if (moving != dynamicResolutionOn) {
            dynamicResolutionOn = moving
            view.setDynamicResolutionOptions(resolution(moving))
            dirty = true
        }

        if (dirty) {
            if (renderer.beginFrame(sc, frameTimeNanos)) {
                renderer.render(view)
                renderer.endFrame()
                lastFrameNanos = frameTimeNanos
                dirty = false
                trackFrame(frameTimeNanos, moving)
            } else {
                keepGoing = true // Filament pidió saltar este cuadro
            }
        }

        if (keepGoing || dirty || moving) postFrame()
        else if (animating) postFrameAt(lastAnimNanos + ANIM_FRAME_NS)
    }

    private fun applyCamera() {
        val c = mapCamera
        val aspect = c.viewportWidth.toDouble() / c.viewportHeight
        camera.setProjection(c.fovDegrees.toDouble(), aspect, c.nearPlane.toDouble(), c.farPlane.toDouble(), Camera.Fov.VERTICAL)
        c.lookAt(lookAt)
        camera.lookAt(lookAt[0], lookAt[1], lookAt[2], lookAt[3], lookAt[4], lookAt[5], lookAt[6], lookAt[7], lookAt[8])
    }

    /** Escala de render: fija (la del preset) quieta; dinámica hacia abajo mientras se mueve. */
    private fun resolution(moving: Boolean) = View.DynamicResolutionOptions().apply {
        enabled = moving || preset.idleScale < 1f
        minScale = if (moving) preset.movingScale else preset.idleScale
        maxScale = preset.idleScale
        quality = if (moving) View.QualityLevel.LOW else View.QualityLevel.MEDIUM
    }

    // ---------------------------------------------------------------------------- limpieza

    /** Libera todos los objetos de Filament. No usar la instancia después. */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        choreographer.removeFrameCallback(this)
        uiHelper.detach() // → onDetachedFromSurface destruye el SwapChain

        cancelPending()
        current?.destroy(); current = null
        routes.destroy()
        walkways.destroy()
        lamps.destroy()
        navLine.destroy()
        ground.destroy() // sus MaterialInstance antes que los materiales del ubershader
        assetLoader.destroy()
        materialProvider.destroyMaterials()
        materialProvider.destroy()
        parkLoader.destroy()
        contextLoader.destroy()

        engine.destroyEntity(sun)
        indirectLight?.let(engine::destroyIndirectLight)
        engine.destroyColorGrading(colorGrading)
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
        EntityManager.get().destroy(sun)
        engine.destroy()
    }

    private fun srgb(rgb: Int): FloatArray = Colors.toLinear(
        Colors.RgbType.SRGB,
        ((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f,
        null,
    )
}
