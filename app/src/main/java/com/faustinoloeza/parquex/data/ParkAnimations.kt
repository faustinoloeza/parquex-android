package com.faustinoloeza.parquex.data

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Piezas que se mueven en un parque (`assets/parks/<key>/animated.json`, del exportador web; contrato en
 * `docs/app-movil-noche-navegacion.md` → Animaciones): en Xcaret, la cabina y la bandera de la Torre Escénica,
 * los voladores, la bandera monumental, las mariposas y aves de los domos y las bandadas. Cada pieza nombra su
 * driver (`map/AnimationDrivers.kt`, port de su `update()` en la web), sus nodos `anim:*` del GLB y la
 * transformación de su landmark. Coordenadas locales del parque, en metros.
 */
class ParkAnimations(
    /** Actividad de noche (1 de día … esto con k = 1): ritmo del vuelo y fracción de aves y mariposas despiertas. */
    val nightPace: Float,
    val nightBirds: Float,
    val pieces: List<Piece>,
) {
    /** Aleteo (flap.js): los vértices con |p.eje| > [threshold] giran en el plano (eje, y). Eje x o z. */
    class Flap(val axisX: Boolean, val speed: Float, val amp: Float, val threshold: Float)

    /** Mariposa o ave de un domo; [color] sRGB 0xRRGGBB, tiñe al prototipo blanco. */
    class Fly(val a: Float, val s: Float, val h: Float, val rr: Float, val color: Int)

    /** Ave de una bandada. */
    class Bird(val phase: Float, val speed: Float, val rr: Float, val hh: Float)

    /** Parámetros de cada driver (los de su `update()` en la web). */
    sealed interface Driver
    class TowerCabin(val height: Float) : Driver
    /** [at]: dónde cuelga la tela (posición de la malla en el landmark). */
    class Flag(val at: FloatArray, val width: Float, val height: Float, val speed: Float) : Driver
    /** [ropeColor] sRGB 0xRRGGBB. Partes: caporal y los 4 voladores. */
    class Voladores(val height: Float, val ropeColor: Int) : Driver
    class NetDome(val rx: Float, val rz: Float, val scale: Float, val flap: Flap, val flies: List<Fly>) : Driver
    class Flock(val x: Float, val z: Float, val scale: Float, val flap: Flap, val birds: List<Bird>) : Driver

    class Piece(
        val id: String,
        val driver: Driver,
        /** Transformación del landmark: posición y giro alrededor de y. */
        val x: Float,
        val y: Float,
        val z: Float,
        val rotY: Float,
        /** Caja en el marco del parque que contiene todo el movimiento: x0, y0, z0, x1, y1, z1. */
        val box: FloatArray,
        /** Metros de su figura móvil más chica: solo se anima si se ve de unos pocos píxeles o más. */
        val size: Float,
        /** Nodos del GLB, sin el prefijo `anim:`. */
        val parts: List<String>,
    ) {
        /** Copias de cada parte (aves y mariposas); 1 en las demás piezas. */
        val copies get() = when (driver) {
            is NetDome -> driver.flies.size
            is Flock -> driver.birds.size
            else -> 1
        }
    }

    companion object {
        const val FILE = "animated.json"
        private const val NODE_PREFIX = "anim:"
        private val cache = java.util.Collections.synchronizedMap(HashMap<String, ParkAnimations?>())

        /** null si el parque no tiene `animated.json` o ninguna pieza que la app sepa mover. */
        fun load(assets: AssetManager, key: String): ParkAnimations? {
            if (cache.containsKey(key)) return cache[key]
            val text = runCatching { assets.open("parks/$key/$FILE").bufferedReader().use { it.readText() } }
                .getOrNull()
            val result = text?.let { runCatching { parse(JSONObject(it)) }.getOrNull()?.takeIf { a -> a.pieces.isNotEmpty() } }
            cache[key] = result
            return result
        }

        /** Las piezas con un driver desconocido (un exportador más nuevo) se omiten. */
        fun parse(json: JSONObject): ParkAnimations {
            val night = json.optJSONObject("nightActivity")
            val list = json.getJSONArray("pieces")
            val pieces = (0 until list.length()).mapNotNull { i ->
                val p = list.getJSONObject(i)
                val driver = driver(p.getString("driver"), p.getJSONObject("params")) ?: return@mapNotNull null
                val position = p.getJSONArray("position").floats()
                Piece(
                    id = p.getString("id"),
                    driver = driver,
                    x = position[0], y = position[1], z = position[2],
                    rotY = p.optDouble("rotY", 0.0).toFloat(),
                    box = p.getJSONArray("box").floats(),
                    size = p.getDouble("size").toFloat(),
                    parts = p.getJSONArray("parts").strings().map { it.removePrefix(NODE_PREFIX) },
                )
            }
            return ParkAnimations(
                nightPace = night?.optDouble("pace", 1.0)?.toFloat() ?: 1f,
                nightBirds = night?.optDouble("birds", 1.0)?.toFloat() ?: 1f,
                pieces = pieces,
            )
        }

        private fun driver(name: String, p: JSONObject): Driver? = when (name) {
            "towerCabin" -> TowerCabin(p.float("height"))
            "flag" -> Flag(p.getJSONArray("at").floats(), p.float("width"), p.float("height"), p.float("speed"))
            "voladores" -> Voladores(p.float("height"), ParkNight.hex(p.getString("ropeColor")))
            "netDome" -> NetDome(
                p.float("rx"), p.float("rz"), p.float("scale"), flap(p.getJSONObject("flap")),
                p.getJSONArray("flies").objects().map {
                    Fly(it.float("a"), it.float("s"), it.float("h"), it.float("rr"), ParkNight.hex(it.getString("color")))
                },
            )
            "flock" -> Flock(
                p.float("x"), p.float("z"), p.float("scale"), flap(p.getJSONObject("flap")),
                p.getJSONArray("birds").objects().map { Bird(it.float("phase"), it.float("speed"), it.float("rr"), it.float("hh")) },
            )
            else -> null
        }

        private fun flap(o: JSONObject) = Flap(o.getString("axis") == "x", o.float("speed"), o.float("amp"), o.float("threshold"))

        private fun JSONObject.float(name: String) = getDouble(name).toFloat()
        private fun JSONArray.floats() = FloatArray(length()) { getDouble(it).toFloat() }
        private fun JSONArray.strings() = List(length()) { getString(it) }
        private fun JSONArray.objects() = List(length()) { getJSONObject(it) }
    }
}
