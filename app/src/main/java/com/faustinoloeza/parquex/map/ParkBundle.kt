package com.faustinoloeza.parquex.map

import android.util.Log
import com.faustinoloeza.parquex.data.Lamps
import com.faustinoloeza.parquex.data.ParkAnimations
import com.faustinoloeza.parquex.data.ParkContext
import com.faustinoloeza.parquex.data.ParkData
import com.faustinoloeza.parquex.data.ParkNight
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Engine
import com.google.android.filament.Scene
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import java.nio.ByteBuffer

/**
 * Todo lo de un parque enfocado, armado **fuera de escena**: su modelo (`park-models.glb`), las
 * copias de árboles/autobuses (`instances.bin`), las piezas animadas (`animated.json`, [AnimatedModels])
 * y, si lo tiene, el paquete de contexto (`context.glb` con el suelo y el agua de los vecinos y la región,
 * y `context-instances.bin` con sus árboles).
 *
 * Las texturas se cargan con dos [ResourceLoader] asíncronos (uno por GLB); [update] avanza la
 * carga y, cuando termina, [show] agrega todo a la escena de una vez. Así se puede cambiar de
 * parque sin que la escena quede vacía mientras carga el nuevo.
 */
internal class ParkBundle(
    val key: String,
    val park: ParkData?,
    val context: ParkContext?,
    /** Parámetros de noche (`night.json`) y faroles (`lamps.bin`, `context-lamps.bin`). */
    val night: ParkNight?,
    val lamps: Lamps,
    val contextLamps: Lamps,
    animations: ParkAnimations?,
    private val engine: Engine,
    private val scene: Scene,
    private val assetLoader: AssetLoader,
    private val parkLoader: ResourceLoader,
    private val contextLoader: ResourceLoader,
    val asset: FilamentAsset,
    parkGlb: ByteBuffer,
    instances: ByteBuffer?,
    val contextAsset: FilamentAsset?,
    contextGlb: ByteBuffer?,
    private val contextInstances: ByteBuffer?,
    quality: String,
) {
    companion object {
        private const val TAG = "ParkBundle"
        private const val CONTEXT_TREE_ZONE = 1200f
        private const val WATER_MATERIAL = "water"
        private val IDENTITY3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        private const val CONTEXT_WATER_PREFIX = "ctx-water"
    }

    private val models = InstancedModels(engine, scene)
    /** Árboles de los vecinos y la región: zonas grandes (se ven de lejos; ~3 km de región en ~10 zonas). */
    private val contextTrees = InstancedModels(engine, scene, zone = CONTEXT_TREE_ZONE)
    /** Prototipos del parque: los usan sus copias y los árboles de contexto. */
    private val prototypes = runCatching { models.readPrototypes(parkGlb, asset) }
        .onFailure { Log.e(TAG, "Prototipos ilegibles en $key", it) }
        .getOrDefault(emptyMap())
    /** Torre, voladores, banderas, aves y mariposas (Xcaret): sus nodos `anim:*` se leen con el GLB aún mapeado. */
    private val animated = animations?.let { anim ->
        runCatching { AnimatedModels(engine, scene, anim, PlainMeshes.read(engine, parkGlb, asset, AnimatedModels.PREFIX)) }
            .onFailure { Log.e(TAG, "No se pudieron armar las animaciones de $key", it) }
            .getOrNull()
    }
    val hasAnimations get() = animated != null
    private var treeQuality = ""

    private var parkLoading = false
    private var contextLoading = false
    /** GLB mapeados en memoria: se retienen hasta que gltfio termina de leerlos (releaseSourceData). */
    private var parkSource: ByteBuffer? = parkGlb
    private var contextSource: ByteBuffer? = contextGlb
    var shown = false
        private set

    /** El GLB trae suelo ilustrado (`terrain:*`): el mapa OSM va solo afuera del parque. */
    val hasTerrain = asset.entities.any { asset.getName(it)?.startsWith(ParkRenderer.TERRAIN_PREFIX) == true }

    val renderableCount get() = sceneEntities.size + models.renderableCount + contextTrees.renderableCount +
        (animated?.renderableCount ?: 0)
    private val sceneEntities = ArrayList<Int>()
    /** MaterialInstance de los GLB por nombre de material glTF (emisivos de noche, agua). */
    private val materialsByName = HashMap<String, MutableSet<MaterialInstance>>()
    private var nightLevel = -1f
    /**
     * Instancias a las que ya se les puso `emissiveUvMatrix` = identidad: gltfio 1.77 la deja en ceros cuando
     * el glTF no trae KHR_texture_transform, y la textura emisiva (ventanas, mapa de luz) se leería en (0, 0).
     */
    private val uvFixed = HashSet<MaterialInstance>()

    init {
        instances?.let { data ->
            runCatching {
                models.build(prototypes, data, { type -> if (type.startsWith("tree")) ParkRenderer.CHANNEL_TREES else ParkRenderer.CHANNEL_MODELS })
            }.onFailure { Log.e(TAG, "No se pudieron armar las instancias de $key", it) }
        }
        setTreeQuality(quality)
        parkLoading = parkLoader.asyncBeginLoad(asset)
        if (!parkLoading) Log.e(TAG, "No se pudieron cargar los recursos de $key")
        contextAsset?.let { contextLoading = contextLoader.asyncBeginLoad(it) }
    }

    /** Avanza la carga de texturas; devuelve el progreso (1 = lista). */
    fun update(): Float {
        var progress = 1f
        if (parkLoading) {
            parkLoader.asyncUpdateLoad()
            val p = parkLoader.asyncGetLoadProgress()
            if (p >= 1f) { parkLoading = false; asset.releaseSourceData(); parkSource = null } else progress = p
        }
        if (contextLoading) {
            contextLoader.asyncUpdateLoad()
            val p = contextLoader.asyncGetLoadProgress()
            if (p >= 1f) { contextLoading = false; contextAsset?.releaseSourceData(); contextSource = null } else progress = minOf(progress, p)
        }
        return progress
    }

    val loading get() = parkLoading || contextLoading

    /** Árboles de contexto según la calidad ("high", "medium", "low"): toma una fracción por vecino. */
    fun setTreeQuality(quality: String) {
        if (quality == treeQuality) return
        treeQuality = quality
        val ctx = context ?: return
        val data = contextInstances ?: return
        runCatching {
            contextTrees.build(prototypes, data, { ParkRenderer.CHANNEL_TREES }) { type, index -> ctx.keepTree(type, index, quality) }
        }.onFailure { Log.e(TAG, "No se pudieron armar los árboles de contexto de $key", it) }
    }

    /** Agrega a la escena el modelo, sus copias y el contexto, cada uno en su canal. */
    fun show() {
        if (shown) return
        shown = true
        val rcm = engine.renderableManager
        for (entity in asset.renderableEntities) {
            val name = asset.getName(entity).orEmpty()
            if (ParkRenderer.HIDDEN_NODES.any { name.startsWith(it) }) {
                // Las piezas animadas usan sus materiales (el vidrio de la cabina brilla de noche).
                if (name.startsWith(AnimatedModels.PREFIX)) collectMaterials(rcm, rcm.getInstance(entity))
                continue
            }
            val ri = rcm.getInstance(entity)
            ParkRenderer.configureParkNode(rcm, ri, name)
            collectMaterials(rcm, ri)
            scene.addEntity(entity); sceneEntities += entity
        }
        contextAsset?.let { ctxAsset ->
            val ctx = context
            val maxPriority = ctx?.neighbors?.maxOfOrNull { it.priority } ?: 0
            for (entity in ctxAsset.renderableEntities) {
                val name = ctxAsset.getName(entity).orEmpty()
                val neighbor = ctx?.neighbors?.firstOrNull { it.groundNode == name || it.waterNode == name }
                val water = neighbor?.waterNode == name
                // Suelo: de la menor a la mayor prioridad (región primero, el vecino más cercano al final).
                val priority = if (water) ParkRenderer.PRIORITY_CONTEXT_WATER
                    else (maxPriority - (neighbor?.priority ?: maxPriority)).coerceIn(0, ParkRenderer.PRIORITY_CONTEXT_WATER - 1)
                val ri = rcm.getInstance(entity)
                ParkRenderer.configureContextNode(rcm, ri, water, priority)
                collectMaterials(rcm, ri)
                scene.addEntity(entity); sceneEntities += entity
            }
        }
        models.show()
        contextTrees.show()
        animated?.show()
        val matched = night?.emissive?.keys?.count { it in materialsByName } ?: 0
        Log.i(TAG, "Materiales: ${materialsByName.size} por nombre; con emisivo de noche: $matched de ${night?.emissive?.size ?: 0}; " +
            "faroles ${lamps.count} (+${contextLamps.count} de vecinos); ej. ${materialsByName.keys.take(6)}")
    }

    private fun collectMaterials(rcm: com.google.android.filament.RenderableManager, ri: Int) {
        for (p in 0 until rcm.getPrimitiveCount(ri)) {
            val mi = rcm.getMaterialInstanceAt(ri, p)
            val name = mi.name ?: continue
            materialsByName.getOrPut(name) { HashSet() } += mi
        }
    }

    /**
     * Nivel de noche [k] (0 = día, 1 = noche) en los materiales: `emissiveStrength` = día + (noche − día)·k
     * por material (ventanas, faroles, mapa de luz del suelo y del agua), × [emissiveScale]; y el agua (del
     * parque y de contexto) multiplica su color de día por mix(1, nightMultiply, k).
     */
    fun applyNight(k: Float, emissiveScale: Float) {
        val n = night ?: return
        if (k == nightLevel) return
        nightLevel = k
        // emissiveStrength (KHR_materials_emissive_strength) por material, y también el color: gltfio 1.77 deja
        // emissiveFactor en cero cuando la fuerza del archivo es 0 (de día), así que se escribe el de night.json.
        for ((name, range) in n.emissive) {
            val strength = (range[0] + (range[1] - range[0]) * k) * emissiveScale
            val color = n.emissiveFactor[name] ?: continue
            materialsByName[name]?.forEach { mi ->
                if (uvFixed.add(mi)) mi.setParameter("emissiveUvMatrix", MaterialInstance.FloatElement.MAT3, IDENTITY3, 0, 1)
                mi.setParameter("emissiveFactor", color[0], color[1], color[2])
                mi.setParameter("emissiveStrength", strength)
            }
        }
        val m = n.waterNightMultiply
        val r = 1f + (m[0] - 1f) * k; val g = 1f + (m[1] - 1f) * k; val b = 1f + (m[2] - 1f) * k
        for ((name, set) in materialsByName) {
            if (name != WATER_MATERIAL && !name.startsWith(CONTEXT_WATER_PREFIX)) continue
            set.forEach { it.setParameter("baseColorFactor", r, g, b, 1f) }
        }
    }

    /** ¿Se ve alguna pieza animada? Marca cuáles mover en [animate]. */
    fun animationsVisible(snap: CameraSnapshot) = animated?.visible(snap) == true

    /** Pose de las piezas animadas que se ven en el instante [frameTimeNanos]; [nightK] 0 día … 1 noche. */
    fun animate(frameTimeNanos: Long, nightK: Float, snap: CameraSnapshot) {
        animated?.animate(frameTimeNanos, nightK, snap)
    }

    /** Quita todo de la escena y destruye lo creado (las texturas y materiales de los GLB incluidos). */
    fun destroy() {
        if (parkLoading) parkLoader.asyncCancelLoad()
        if (contextLoading) contextLoader.asyncCancelLoad()
        parkLoading = false; contextLoading = false
        models.clear(); contextTrees.clear(); animated?.clear() // usan MaterialInstance de los GLB: antes de destruirlos
        sceneEntities.forEach(scene::remove)
        sceneEntities.clear()
        scene.removeEntities(asset.entities)
        assetLoader.destroyAsset(asset)
        contextAsset?.let { scene.removeEntities(it.entities); assetLoader.destroyAsset(it) }
        parkLoader.evictResourceData()
        contextLoader.evictResourceData()
        parkSource = null; contextSource = null
        materialsByName.clear()
        uvFixed.clear()
        shown = false
    }
}
