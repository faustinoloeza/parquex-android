package com.faustinoloeza.parquex.data

import android.content.res.AssetManager
import org.json.JSONObject
import kotlin.math.floor

/**
 * Paquete de contexto de un parque enfocado (`assets/parks/<key>/context.json`, junto a `context.glb`
 * y `context-instances.bin`): los parques vecinos y la región que los rodea (p. ej. Xcaret, Xplor y
 * Xenses con la "Zona Xcaret") en versión ligera, ya recortados y fundidos por el exportador
 * (`src/export/context.js` de la web). Todo viene en el marco local del parque enfocado.
 */
class ParkContext(val focus: String, val neighbors: List<Neighbor>, val fade: Pair<Float, Float>) {

    /** Desplazamiento del marco de un vecino visto desde el parque enfocado. */
    class Offset(val x: Float, val z: Float, val sx: Float)

    class Neighbor(
        val key: String,
        val name: String,
        /** La región de fondo (no un parque). */
        val region: Boolean,
        /** 0 = encima de todos. */
        val priority: Int,
        val offset: Offset,
        /** Contorno del área (en el marco del parque enfocado). */
        val clear: ParkData.DesignArea?,
        val groundNode: String,
        val waterNode: String,
        /** Rango de registros por tipo en `context-instances.bin` (first, count). */
        val treeRanges: Map<String, IntArray>,
        /** Fracción de árboles por calidad: "high", "medium", "low". */
        val treeQuality: Map<String, Float>,
    )

    val region: Neighbor? get() = neighbors.firstOrNull { it.region }

    /** Parque vecino [key], si está en el paquete. */
    fun neighbor(key: String): Neighbor? = neighbors.firstOrNull { it.key == key }

    /**
     * ¿Se conserva el registro [index] (dentro del tipo [type]) en la calidad [quality]? Dentro del
     * rango de cada vecino se toma la fracción f: registro j si ⌊(j+1)·f⌋ > ⌊j·f⌋.
     */
    fun keepTree(type: String, index: Int, quality: String): Boolean {
        for (n in neighbors) {
            val range = n.treeRanges[type] ?: continue
            val j = index - range[0]
            if (j < 0 || j >= range[1]) continue
            val f = n.treeQuality[quality] ?: 1f
            if (f >= 1f) return true
            return floor((j + 1) * f) > floor(j * f)
        }
        return true
    }

    companion object {
        const val JSON_FILE = "context.json"
        const val GLB_FILE = "context.glb"
        const val INSTANCES_FILE = "context-instances.bin"
        private val cache = java.util.Collections.synchronizedMap(HashMap<String, ParkContext?>())

        /** null si el parque no tiene paquete de contexto. */
        fun load(assets: AssetManager, key: String): ParkContext? {
            if (cache.containsKey(key)) return cache[key]
            val text = runCatching { assets.open("parks/$key/$JSON_FILE").bufferedReader().use { it.readText() } }
                .getOrNull()
            val result = text?.let { parse(JSONObject(it)) }
            cache[key] = result
            return result
        }

        fun parse(json: JSONObject): ParkContext {
            val fadeJson = json.optJSONObject("fade")
            val fade = (fadeJson?.optDouble("inner", 45.0)?.toFloat() ?: 45f) to (fadeJson?.optDouble("outer", 5.0)?.toFloat() ?: 5f)
            val list = json.getJSONArray("neighbors")
            val neighbors = List(list.length()) { i ->
                val n = list.getJSONObject(i)
                val o = n.getJSONObject("offset")
                val nodes = n.getJSONObject("nodes")
                val trees = n.optJSONObject("trees")
                val types = trees?.optJSONObject("types")
                val quality = trees?.optJSONObject("quality")
                Neighbor(
                    key = n.getString("key"),
                    name = n.optString("name", n.getString("key")),
                    region = n.optBoolean("region"),
                    priority = n.optInt("priority"),
                    offset = Offset(o.getDouble("x").toFloat(), o.getDouble("z").toFloat(), o.optDouble("sx", 1.0).toFloat()),
                    clear = n.optJSONObject("clear")?.let { c ->
                        val r = c.optJSONArray("radii") ?: return@let null
                        ParkData.DesignArea(c.getDouble("cx").toFloat(), c.getDouble("cz").toFloat(),
                            FloatArray(r.length()) { r.getDouble(it).toFloat() })
                    },
                    groundNode = nodes.optString("ground"),
                    waterNode = nodes.optString("water"),
                    treeRanges = buildMap {
                        types?.keys()?.forEach { t ->
                            val r = types.getJSONObject(t)
                            put(t, intArrayOf(r.getInt("first"), r.getInt("count")))
                        }
                    },
                    treeQuality = buildMap {
                        quality?.keys()?.forEach { q -> put(q, quality.getDouble(q).toFloat()) }
                    },
                )
            }
            return ParkContext(json.optString("focus"), neighbors, fade)
        }
    }
}
