package com.faustinoloeza.parquex.nav

import com.faustinoloeza.parquex.data.ParkData
import org.json.JSONObject
import java.io.File

/** Textos `nav_*` de la web (`src/i18n/es.js` / `en.js`), los mismos que van a strings.xml. */
private val ES = mapOf(
    "nav_arrived" to "Has llegado",
    "nav_head" to "Camina hacia el {dir}",
    "nav_dir_n" to "norte", "nav_dir_ne" to "noreste", "nav_dir_e" to "este", "nav_dir_se" to "sureste",
    "nav_dir_s" to "sur", "nav_dir_sw" to "suroeste", "nav_dir_w" to "oeste", "nav_dir_nw" to "noroeste",
    "nav_left" to "Gira a la izquierda",
    "nav_right" to "Gira a la derecha",
    "nav_slight_left" to "Gira ligeramente a la izquierda",
    "nav_slight_right" to "Gira ligeramente a la derecha",
    "nav_near" to "{text} cerca de {near}",
    "nav_arrive" to "Llegas a tu destino",
    "nav_arrive_at" to "Llegas a {name}",
    "nav_in" to "En {dist} {text}",
)

private val EN = mapOf(
    "nav_arrived" to "You have arrived",
    "nav_head" to "Head {dir}",
    "nav_dir_n" to "north", "nav_dir_ne" to "northeast", "nav_dir_e" to "east", "nav_dir_se" to "southeast",
    "nav_dir_s" to "south", "nav_dir_sw" to "southwest", "nav_dir_w" to "west", "nav_dir_nw" to "northwest",
    "nav_left" to "Turn left",
    "nav_right" to "Turn right",
    "nav_slight_left" to "Turn slightly left",
    "nav_slight_right" to "Turn slightly right",
    "nav_near" to "{text} near {near}",
    "nav_arrive" to "Arrive at your destination",
    "nav_arrive_at" to "Arrive at {name}",
    "nav_in" to "In {dist} {text}",
)

/** Como `translator(lang)` de la web: el idioma, luego español, luego la llave. */
val es: NavText = { k -> ES[k] ?: k }
val en: NavText = { k -> EN[k] ?: ES[k] ?: k }

/** park.json real de assets (las pruebas JVM corren con el módulo `app/` como directorio de trabajo). */
fun loadPark(key: String, lang: String = "es"): ParkData =
    ParkData.parse(JSONObject(File("src/main/assets/parks/$key/park.json").readText()), lang)

/** Polilínea [x0, z0, x1, z1, ...] a partir de pares. */
fun line(vararg xz: Pair<Number, Number>) = FloatArray(xz.size * 2) { i ->
    val p = xz[i / 2]
    (if (i % 2 == 0) p.first else p.second).toFloat()
}

fun pt(x: Number, z: Number) = NavPoint(x.toDouble(), z.toDouble())
