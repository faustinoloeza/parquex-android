package com.faustinoloeza.parquex.ui

import com.faustinoloeza.parquex.data.ParkData

/**
 * Grupos de actividades para el menú de puntos: por ruta (`poi.route` = `route.name`) o, si el
 * parque no tiene rutas (Xel-Há), por categoría. Lo que no encaja va a [OTHER].
 * Los puntos de un grupo solo se muestran cuando el grupo está activo.
 */
class PoiFilters private constructor(val groups: List<Group>, private val groupOfPoi: Map<String, String>) {

    enum class Kind { ROUTE, CATEGORY, OTHER }

    class Group(
        val key: String,
        /** Texto del chip; null en [Kind.OTHER] (se toma de strings.xml). */
        val label: String?,
        /** ARGB. */
        val color: Int,
        /** Contorno ARGB de la línea (solo rutas). */
        val casing: Int,
        val kind: Kind,
        val count: Int,
        /** Rectángulo a encuadrar al activarlo. */
        val bounds: ParkData.Bounds?,
        /** Slug de la ruta (solo [Kind.ROUTE]). */
        val routeSlug: String? = null,
    )

    fun groupOf(poi: ParkData.Poi): String = groupOfPoi[poi.id] ?: OTHER

    companion object {
        const val OTHER = "other"
        private const val OTHER_COLOR = 0xFF9AA5A8.toInt()

        /** Colores de las categorías cuando no hay rutas (paleta del tema). */
        private val CATEGORY_COLORS = intArrayOf(
            0xFF0E7C86.toInt(), 0xFFF08A24.toInt(), 0xFF7A5AF8.toInt(),
            0xFF2E9E5B.toInt(), 0xFFD6457A.toInt(), 0xFF1670D6.toInt(),
        )

        fun of(park: ParkData): PoiFilters {
            val groupOfPoi = HashMap<String, String>()
            val groups = ArrayList<Group>()
            if (park.routes.isNotEmpty()) {
                val byName = park.routes.associateBy { it.nameKey.trim().lowercase() }
                for (poi in park.pois) {
                    val route = poi.routeKey?.trim()?.lowercase()?.let(byName::get) ?: continue
                    groupOfPoi[poi.id] = "route:${route.slug}"
                }
                for (route in park.routes) {
                    val key = "route:${route.slug}"
                    groups += Group(
                        key, route.label ?: route.name, route.color, route.casing, Kind.ROUTE,
                        count = park.pois.count { groupOfPoi[it.id] == key },
                        bounds = route.bounds, routeSlug = route.slug,
                    )
                }
            } else {
                // Clave en español (estable al cambiar de idioma); etiqueta en el idioma de la app.
                val categories = park.pois.map { it.categoryKey.trim() }.filter { it.isNotEmpty() }.distinct()
                categories.forEachIndexed { i, cat ->
                    val key = "cat:$cat"
                    val members = park.pois.filter { it.categoryKey.trim() == cat }
                    members.forEach { groupOfPoi[it.id] = key }
                    val color = CATEGORY_COLORS[i % CATEGORY_COLORS.size]
                    val label = members.first().category.ifBlank { cat }
                    groups += Group(key, label, color, color, Kind.CATEGORY, members.size, boundsOf(members))
                }
            }
            val others = park.pois.filter { it.id !in groupOfPoi }
            if (others.isNotEmpty()) {
                groups += Group(OTHER, null, OTHER_COLOR, OTHER_COLOR, Kind.OTHER, others.size, boundsOf(others))
            }
            return PoiFilters(groups, groupOfPoi)
        }

        private fun boundsOf(pois: List<ParkData.Poi>): ParkData.Bounds? {
            if (pois.isEmpty()) return null
            return ParkData.Bounds(pois.minOf { it.x }, pois.maxOf { it.x }, pois.minOf { it.z }, pois.maxOf { it.z })
        }
    }
}
