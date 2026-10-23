package com.faustinoloeza.parquex.data

import android.content.res.AssetManager
import com.google.android.filament.Colors
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * Parámetros de día y noche de un parque (`assets/parks/<key>/night.json`, del exportador web; formato en
 * `docs/app-movil-noche-navegacion.md` de la web). Colores ya convertidos a RGB **lineal**.
 */
class ParkNight(
    val day: Lighting,
    val night: Lighting,
    /** Fuerza emisiva por nombre de material: (día, noche). */
    val emissive: Map<String, FloatArray>,
    /** `emissiveFactor` lineal por nombre de material (el color; la fuerza va aparte). */
    val emissiveFactor: Map<String, FloatArray>,
    /** El agua del GLB trae su color de día: de noche se multiplica por mix(1, esto, k). */
    val waterNightMultiply: FloatArray,
    /** Teselas OSM de noche: c · tint + lift (lineal). */
    val mapTint: FloatArray,
    val mapLift: FloatArray,
    val lampStyle: LampStyle,
    /** Tope de faroles por calidad ("high", "medium", "low"). */
    val lampCaps: Map<String, Int>,
    /** Noche de [nightFrom] a [nightTo] (horas) en UTC[utcOffsetHours]. */
    val nightFrom: Int,
    val nightTo: Int,
    val utcOffsetHours: Int,
    val transitionSeconds: Float,
) {
    class Lighting(
        val sky: FloatArray,
        val hemiSky: FloatArray,
        val hemiGround: FloatArray,
        val hemi: Float,
        val sun: FloatArray,
        val sunIntensity: Float,
        val exposure: Float,
    )

    class LampStyle(
        val postHeight: Float,
        val postColor: Int,
        val poleRadiusBottom: Float,
        val poleRadiusTop: Float,
        val lanternRadiusBottom: Float,
        val lanternRadiusTop: Float,
        val lanternHeight: Float,
        val lanternAbovePost: Float,
        val sides: Int,
        val postSink: Float,
        val headRadius: Float,
        val headMinPx: Float,
        val headAboveGround: Float,
        val postDistance: Float,
        /** sRGB 0xRRGGBB. */
        val headColor: Int,
        val headCore: Int,
    )

    /** ¿Es de noche a la hora [epochMillis] (en la zona del parque, sin horario de verano)? */
    fun isNightAt(epochMillis: Long): Boolean {
        val hours = Math.floorMod(epochMillis / 3_600_000L + utcOffsetHours, 24L).toInt()
        return if (nightFrom > nightTo) hours >= nightFrom || hours < nightTo else hours in nightFrom until nightTo
    }

    companion object {
        const val FILE = "night.json"
        private val cache = java.util.Collections.synchronizedMap(HashMap<String, ParkNight?>())

        /** Noche de 19:00 a 06:00 en UTC−5 fijo (Cancún, sin horario de verano), como `night.json → when`. */
        fun isNightNow(epochMillis: Long = System.currentTimeMillis(), from: Int = 19, to: Int = 6, utcOffset: Int = -5): Boolean {
            val hours = Math.floorMod(epochMillis / 3_600_000L + utcOffset, 24L).toInt()
            return if (from > to) hours >= from || hours < to else hours in from until to
        }

        /** null si el parque no tiene `night.json`. */
        fun load(assets: AssetManager, key: String): ParkNight? {
            if (cache.containsKey(key)) return cache[key]
            val text = runCatching { assets.open("parks/$key/$FILE").bufferedReader().use { it.readText() } }
                .getOrNull()
            val result = text?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }
            cache[key] = result
            return result
        }

        fun parse(json: JSONObject): ParkNight {
            val lighting = json.getJSONObject("lighting")
            val list = json.getJSONObject("materials").getJSONArray("list")
            val emissive = buildMap {
                for (i in 0 until list.length()) {
                    val m = list.getJSONObject(i)
                    put(m.getString("name"), floatArrayOf(m.getDouble("day").toFloat(), m.getDouble("night").toFloat()))
                }
            }
            val water = json.getJSONObject("water")
            val map = json.getJSONObject("map")
            val lamps = json.getJSONObject("lamps")
            val style = lamps.getJSONObject("style")
            val pole = style.getJSONObject("pole")
            val lantern = style.getJSONObject("lantern")
            val head = style.getJSONObject("head")
            val caps = lamps.optJSONObject("caps")
            val whenJson = json.getJSONObject("when")
            val hours = whenJson.getJSONObject("nightHours")
            return ParkNight(
                day = lighting(lighting.getJSONObject("day")),
                night = lighting(lighting.getJSONObject("night")),
                emissive = emissive,
                emissiveFactor = buildMap {
                    for (i in 0 until list.length()) {
                        val m = list.getJSONObject(i)
                        m.optJSONArray("emissiveFactor")?.let { put(m.getString("name"), it.floats()) }
                    }
                },
                waterNightMultiply = water.getJSONArray("nightMultiply").floats(),
                mapTint = map.getJSONArray("nightTint").floats(),
                mapLift = map.getJSONArray("nightLift").floats(),
                lampStyle = LampStyle(
                    postHeight = style.getDouble("postHeight").toFloat(),
                    postColor = hex(style.getString("postColor")),
                    poleRadiusBottom = pole.getDouble("radiusBottom").toFloat(),
                    poleRadiusTop = pole.getDouble("radiusTop").toFloat(),
                    lanternRadiusBottom = lantern.getDouble("radiusBottom").toFloat(),
                    lanternRadiusTop = lantern.getDouble("radiusTop").toFloat(),
                    lanternHeight = lantern.getDouble("height").toFloat(),
                    lanternAbovePost = lantern.getDouble("centerAbovePost").toFloat(),
                    sides = pole.optInt("sides", 5),
                    postSink = style.getDouble("postSink").toFloat(),
                    headRadius = head.getDouble("radius").toFloat(),
                    headMinPx = head.getDouble("minPx").toFloat(),
                    headAboveGround = head.getDouble("aboveGround").toFloat(),
                    postDistance = style.getDouble("postDistance").toFloat(),
                    headColor = hex(style.getString("headColor")),
                    headCore = hex(style.getString("headCore")),
                ),
                lampCaps = buildMap { caps?.keys()?.forEach { put(it, caps.getInt(it)) } },
                nightFrom = hours.getInt("from"),
                nightTo = hours.getInt("to"),
                utcOffsetHours = whenJson.optInt("utcOffsetHours", -5),
                transitionSeconds = whenJson.optDouble("transitionSeconds", 1.5).toFloat(),
            )
        }

        private fun lighting(o: JSONObject) = Lighting(
            sky = linear(o.getString("sky")),
            hemiSky = linear(o.getString("hemiSky")),
            hemiGround = linear(o.getString("hemiGround")),
            hemi = o.getDouble("hemi").toFloat(),
            sun = linear(o.getString("sun")),
            sunIntensity = o.getDouble("sunIntensity").toFloat(),
            exposure = o.getDouble("exposure").toFloat(),
        )

        fun hex(s: String): Int = s.removePrefix("#").take(6).toInt(16)

        fun linear(s: String): FloatArray = linear(hex(s))

        fun linear(rgb: Int): FloatArray = Colors.toLinear(
            Colors.RgbType.SRGB,
            ((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f,
            null,
        )

        private fun JSONArray.floats() = FloatArray(length()) { getDouble(it).toFloat() }
    }
}

/** Faroles de `lamps.bin` / `context-lamps.bin` ("PQL1"): pie del poste y si va en un muelle. */
class Lamps(val x: FloatArray, val y: FloatArray, val z: FloatArray, val pier: BooleanArray) {
    val count get() = x.size

    /** Tope por calidad: registro j si ⌊(j+1)·f⌋ > ⌊j·f⌋, f = cap / count (solo si count > cap). */
    fun capped(cap: Int): Lamps {
        if (cap <= 0 || count <= cap) return this
        val f = cap.toDouble() / count
        val keep = (0 until count).filter { j -> floor((j + 1) * f) > floor(j * f) }
        return Lamps(
            FloatArray(keep.size) { x[keep[it]] }, FloatArray(keep.size) { y[keep[it]] },
            FloatArray(keep.size) { z[keep[it]] }, BooleanArray(keep.size) { pier[keep[it]] },
        )
    }

    companion object {
        val EMPTY = Lamps(FloatArray(0), FloatArray(0), FloatArray(0), BooleanArray(0))

        fun load(assets: AssetManager, path: String): Lamps? = runCatching {
            val bytes = assets.open(path).use { it.readBytes() }
            parse(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))
        }.getOrNull()

        fun parse(b: ByteBuffer): Lamps {
            val magic = ByteArray(4).also { b.get(it) }
            require(String(magic, Charsets.US_ASCII) == "PQL1") { "lamps: formato desconocido" }
            val count = b.getInt()
            val record = b.getInt()
            val start = b.position()
            val x = FloatArray(count); val y = FloatArray(count); val z = FloatArray(count); val pier = BooleanArray(count)
            for (i in 0 until count) {
                val o = start + i * record
                x[i] = b.getFloat(o); y[i] = b.getFloat(o + 4); z[i] = b.getFloat(o + 8)
                pier[i] = b.get(o + 12).toInt() == 1
            }
            return Lamps(x, y, z, pier)
        }
    }
}
