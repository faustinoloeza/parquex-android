package com.faustinoloeza.parquex.data

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Contenido de `assets/parks/<key>/park.json`, generado por el pipeline del proyecto web
 * (etapa `parkData`).
 *
 * Coordenadas locales en **metros**: x = este, z = sur, y = altura; origen en el centro del mapa
 * del parque ([center]). Es el mismo sistema que usa el modelo (`ParkRenderer.MODEL_FILE`).
 */
data class ParkData(
    val key: String,
    /** Idioma con el que se leyeron los textos (`es` / `en`, con respaldo `es`). */
    val lang: String,
    val name: String,
    val center: LatLng,
    val hours: Hours?,
    val bounds: Bounds,
    val extent: Extent,
    val pois: List<Poi>,
    val routes: List<Route>,
    val services: List<Service>,
    /** Andadores de OSM (footway, path, steps, pedestrian) como polilíneas [x0, z0, x1, z1, ...]; los cruces comparten coordenadas exactas. */
    val walkways: List<FloatArray>,
    /** Contorno del área diseñada (`park.json → clear`); null si no viene. */
    val designArea: DesignArea?,
    /** Entrada del parque (`park.json → entrance`): inicio de la navegación simulada; null si no viene. */
    val entrance: Entrance? = null,
    /** Huecos del suelo con un puente encima (`park.json → groundHoles`): ahí se pisa la cubierta. */
    val groundHoles: List<GroundHole> = emptyList(),
) {
    data class Entrance(val x: Float, val z: Float)

    /**
     * Hueco del suelo con un puente encima (el foso de los felinos de Xcaret), como
     * `shared/ground-holes.js` de la web: anillos cerrados [x0, z0, x1, z1, ...] rellenos con la regla
     * par-impar (contorno + islas) y [y], la altura absoluta de la cubierta.
     */
    class GroundHole(val rings: List<FloatArray>, val y: Float) {
        private val x0 = rings.minOf { r -> (r.indices step 2).minOf { r[it] } }
        private val x1 = rings.maxOf { r -> (r.indices step 2).maxOf { r[it] } }
        private val z0 = rings.minOf { r -> (1 until r.size step 2).minOf { r[it] } }
        private val z1 = rings.maxOf { r -> (1 until r.size step 2).maxOf { r[it] } }

        fun contains(x: Float, z: Float): Boolean {
            if (x < x0 || x > x1 || z < z0 || z > z1) return false
            var inside = false
            for (r in rings) {
                var j = r.size - 2
                for (i in r.indices step 2) {
                    val ax = r[j]; val az = r[j + 1]; val bx = r[i]; val bz = r[i + 1]
                    if ((az > z) != (bz > z) && x < (bx - ax) * (z - az) / (bz - az) + ax) inside = !inside
                    j = i
                }
            }
            return inside
        }
    }

    data class LatLng(val lat: Double, val lng: Double)
    data class Hours(val open: String, val close: String)

    /** Rectángulo (en metros locales) que cubre el área diseñada del parque. */
    data class Bounds(val x0: Float, val x1: Float, val z0: Float, val z1: Float) {
        val centerX get() = (x0 + x1) / 2f
        val centerZ get() = (z0 + z1) / 2f
        val width get() = x1 - x0
        val depth get() = z1 - z0
    }

    /**
     * Área diseñada del parque (la parte de la ilustración oficial sin nubes), como perfil polar:
     * [radii] a ángulos parejos, en sentido de atan2(z, x). Igual que `shared/design-area.js` de la web:
     * dentro va el parque 3D y fuera el mapa real, mezclados en una franja alrededor del contorno.
     */
    class DesignArea(val cx: Float, val cz: Float, val radii: FloatArray) {
        val minRadius = radii.min()
        val maxRadius = radii.max()

        /** Radio del contorno en el ángulo atan2(z, x), interpolado. */
        fun radiusAt(angle: Float): Float {
            val n = radii.size
            val f = (((angle / (2 * PI.toFloat())) * n) % n + n) % n
            val i = f.toInt().coerceAtMost(n - 1)
            val t = f - i
            return radii[i] * (1 - t) + radii[(i + 1) % n] * t
        }

        /** Distancia con signo al contorno (m, aproximada en radial): < 0 dentro. */
        fun signedDistance(x: Float, z: Float): Float {
            val dx = x - cx; val dz = z - cz
            return sqrt(dx * dx + dz * dz) - radiusAt(atan2(dz, dx))
        }

        /** 0 dentro del parque … 1 donde solo se ve el mapa real (smoothstep −[FADE_INNER] … +[FADE_OUTER]). */
        fun fade(x: Float, z: Float): Float {
            val t = ((signedDistance(x, z) + FADE_INNER) / (FADE_INNER + FADE_OUTER)).coerceIn(0f, 1f)
            return t * t * (3 - 2 * t)
        }

        /** ¿Todo el rectángulo queda dentro del parque (fade = 0)? Conservador. */
        fun coversRect(x0: Float, z0: Float, x1: Float, z1: Float): Boolean {
            val fx = max(abs(x0 - cx), abs(x1 - cx)); val fz = max(abs(z0 - cz), abs(z1 - cz))
            return sqrt(fx * fx + fz * fz) < minRadius - FADE_INNER
        }

        /** ¿Todo el rectángulo queda fuera de la franja (fade = 1)? Conservador. */
        fun outsideRect(x0: Float, z0: Float, x1: Float, z1: Float): Boolean {
            val nx = cx.coerceIn(x0, x1) - cx; val nz = cz.coerceIn(z0, z1) - cz
            return sqrt(nx * nx + nz * nz) > maxRadius + FADE_OUTER
        }

        companion object {
            /** Ancho (m) de la franja de mezcla dentro y fuera del contorno (`FADE` de la web). */
            const val FADE_INNER = 45f
            const val FADE_OUTER = 5f
        }
    }

    /** Cuadrado que cubre la ilustración oficial (centro y lado, en metros). */
    data class Extent(val cx: Float, val cz: Float, val size: Float)

    /** Actividad / punto de interés numerado del mapa oficial. */
    data class Poi(
        val id: String,
        val number: Int,
        val x: Float,
        val y: Float,
        val z: Float,
        val name: String,
        val category: String,
        /** Categoría en español (clave estable de grupo en cualquier idioma). */
        val categoryKey: String,
        val route: String?,
        /** Nombre de su ruta en español: clave para relacionarla con [Route.nameKey] en cualquier idioma. */
        val routeKey: String?,
        val extraCost: Boolean,
        val active: Boolean,
    )

    data class Route(
        val slug: String,
        val name: String,
        /** Nombre en español (clave estable para relacionar actividades con la ruta). */
        val nameKey: String,
        /** Color ARGB. */
        val color: Int,
        val casing: Int,
        val label: String?,
        /** Polilíneas: cada una es [x0, z0, x1, z1, ...] en metros. */
        val lines: List<FloatArray>,
    ) {
        /** Rectángulo que envuelve todas sus líneas. */
        val bounds: Bounds by lazy {
            var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
            for (line in lines) for (i in line.indices step 2) {
                x0 = minOf(x0, line[i]); x1 = maxOf(x1, line[i])
                z0 = minOf(z0, line[i + 1]); z1 = maxOf(z1, line[i + 1])
            }
            Bounds(x0, x1, z0, z1)
        }
    }

    /** Servicio (baños, enfermería, lockers...). [type] es el código del pipeline (p. ej. `wc`). */
    data class Service(val type: String, val x: Float, val y: Float, val z: Float, val note: String?)

    // Proyección equirectangular alrededor de [center] — idéntica a `shared/geo.js` (makeProjection).
    private val metersPerDegree = PI * EARTH_RADIUS / 180.0
    private val metersPerDegreeLng = metersPerDegree * cos(center.lat * PI / 180.0)

    /** Latitud/longitud (WGS84) → metros locales [x, z]. Útil para el "estás aquí" por GPS. */
    fun toLocal(lat: Double, lng: Double): FloatArray = floatArrayOf(localX(lng), localZ(lat))

    /** Metros locales → latitud/longitud. */
    fun toLatLng(x: Float, z: Float): LatLng = LatLng(latAt(z), lngAt(x))

    /** Longitud → x local (m, este). */
    fun localX(lng: Double) = ((lng - center.lng) * metersPerDegreeLng).toFloat()

    /** Latitud → z local (m, sur). */
    fun localZ(lat: Double) = (-(lat - center.lat) * metersPerDegree).toFloat()

    fun lngAt(x: Float) = center.lng + x / metersPerDegreeLng
    fun latAt(z: Float) = center.lat - z / metersPerDegree

    /** true si (x, z) está dentro de [bounds] ampliado [margin] (fracción del lado mayor). */
    fun contains(x: Float, z: Float, margin: Float = 0f): Boolean {
        val m = maxOf(bounds.width, bounds.depth) * margin
        return x in bounds.x0 - m..bounds.x1 + m && z in bounds.z0 - m..bounds.z1 + m
    }

    /** Altura absoluta de la cubierta del puente sobre un hueco en (x, z), o null si ahí se pisa el suelo. */
    fun deckAt(x: Float, z: Float): Float? = groundHoles.firstOrNull { it.contains(x, z) }?.y

    /**
     * Altura de lo que se pisa en (x, z) para lo que la app dibuja sobre el suelo (andadores, ruta a
     * pie, punto azul): la cubierta del puente sobre un hueco y 0 en el resto (esas capas van en un
     * plano, sin el relieve). Como `surfaceSampler` de `shared/ground-holes.js`.
     */
    fun surfaceAt(x: Float, z: Float): Float = deckAt(x, z) ?: 0f

    companion object {
        private const val EARTH_RADIUS = 6378137.0
        private val cache = ConcurrentHashMap<Pair<String, String>, ParkData>()

        fun load(assets: AssetManager, key: String, lang: String = "es"): ParkData {
            return cache.getOrPut(key to lang) {
                val text = assets.open("parks/$key/park.json").bufferedReader().use { it.readText() }
                parse(JSONObject(text), lang)
            }
        }

        fun parse(json: JSONObject, lang: String = "es"): ParkData {
            val center = json.getJSONObject("center")
            val b = json.getJSONObject("bounds")
            val e = json.getJSONObject("extent")
            val hours = json.optJSONObject("hours")?.let { Hours(it.optString("open"), it.optString("close")) }
            return ParkData(
                key = json.getString("key"),
                lang = lang,
                name = json.optString("name", json.getString("key")),
                center = LatLng(center.getDouble("lat"), center.getDouble("lng")),
                hours = hours,
                bounds = Bounds(b.f("x0"), b.f("x1"), b.f("z0"), b.f("z1")),
                extent = Extent(e.f("cx"), e.f("cz"), e.f("size")),
                pois = json.optJSONArray("pois").objects().map { p ->
                    Poi(
                        id = p.getString("id"),
                        number = p.optInt("number"),
                        x = p.f("x"), y = p.f("y"), z = p.f("z"),
                        name = p.localized("name", lang) ?: "",
                        category = p.localized("category", lang) ?: "",
                        categoryKey = p.localized("category", "es") ?: "",
                        route = p.localized("route", lang)?.takeIf { it.isNotBlank() },
                        routeKey = p.localized("route", "es")?.takeIf { it.isNotBlank() },
                        extraCost = p.optBoolean("extraCost"),
                        active = p.optBoolean("active", true),
                    )
                },
                routes = json.optJSONArray("routes").objects().map { r ->
                    Route(
                        slug = r.optString("slug"),
                        name = r.localized("name", lang) ?: r.optString("slug"),
                        nameKey = r.localized("name", "es") ?: r.optString("slug"),
                        color = parseColor(r.optString("color"), 0xFF232323.toInt()),
                        casing = parseColor(r.optString("casing"), -1),
                        label = r.localized("label", lang),
                        lines = r.optJSONArray("lines").arrays().map(::polyline),
                    )
                },
                services = json.optJSONArray("services").objects().map { s ->
                    Service(s.optString("type"), s.f("x"), s.optDouble("y", 0.0).toFloat(), s.f("z"),
                        s.optString("note").takeIf { it.isNotBlank() })
                },
                walkways = json.optJSONArray("walkways").arrays().map(::polyline),
                designArea = json.optJSONObject("clear")?.let { c ->
                    val r = c.optJSONArray("radii") ?: return@let null
                    if (r.length() < 3) return@let null
                    DesignArea(c.f("cx"), c.f("cz"), FloatArray(r.length()) { r.getDouble(it).toFloat() })
                },
                entrance = json.optJSONObject("entrance")?.let { Entrance(it.f("x"), it.f("z")) },
                // Solo los que tienen puente (`y`); los demás no cambian lo que se pisa.
                groundHoles = json.optJSONArray("groundHoles").objects().mapNotNull { h ->
                    if (h.isNull("y")) return@mapNotNull null
                    val rings = h.optJSONArray("rings").arrays().map(::polyline).filter { it.size >= 6 }
                    if (rings.isEmpty()) null else GroundHole(rings, h.f("y"))
                },
            )
        }

        /** [[x, z], ...] → [x0, z0, x1, z1, ...]. */
        private fun polyline(line: JSONArray) = FloatArray(line.length() * 2).also { out ->
            for (i in 0 until line.length()) {
                val pt = line.getJSONArray(i)
                out[i * 2] = pt.getDouble(0).toFloat()
                out[i * 2 + 1] = pt.getDouble(1).toFloat()
            }
        }

        private fun JSONObject.f(name: String) = getDouble(name).toFloat()

        private fun JSONObject.localized(name: String, lang: String): String? {
            val o = optJSONObject(name) ?: return optString(name).takeIf { has(name) && it != "null" }
            val v = if (o.isNull(lang)) null else o.optString(lang)
            return v?.takeIf { it.isNotEmpty() } ?: if (o.isNull("es")) null else o.optString("es")
        }

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else List(length()) { getJSONObject(it) }

        private fun JSONArray?.arrays(): List<JSONArray> =
            if (this == null) emptyList() else List(length()) { getJSONArray(it) }

        private fun parseColor(hex: String, fallback: Int): Int = runCatching {
            val h = hex.removePrefix("#")
            when (h.length) {
                6 -> (0xFF000000 or h.toLong(16)).toInt()
                8 -> h.toLong(16).toInt()
                else -> fallback
            }
        }.getOrDefault(fallback)
    }
}
