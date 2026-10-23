package com.faustinoloeza.parquex.ui.theme

import androidx.compose.ui.graphics.Color

// Paleta clara tipo "mapa caricatura": cielo/crema, turquesa caribe y naranja de acento.
val Teal = Color(0xFF0E7C86)
val TealContainer = Color(0xFFB8ECEE)
val Sunset = Color(0xFFF08A24)
val SunsetContainer = Color(0xFFFFDDBE)
val Cream = Color(0xFFFAF6EC)
val Sky = Color(0xFFDDE8EE)
val Ink = Color(0xFF1F2A2E)
val InactiveGray = Color(0xFF9AA5A8)
/** Punto azul de "estás aquí". */
val LocationBlue = Color(0xFF1A73E8)

/**
 * Colores de la interfaz que flota sobre el mapa: claros de día y azul marino de noche (night.json →
 * markers / navPanel). Se proveen con [LocalMapColors] según el nivel de noche del mapa.
 */
@androidx.compose.runtime.Immutable
data class MapColors(
    /** Fondo translúcido de píldoras, chips y avisos. */
    val panel: Color,
    /** Fondo sólido de botones, menús y tarjetas. */
    val surface: Color,
    val text: Color,
    val secondary: Color,
    val divider: Color,
    val night: Boolean,
)

val DayMapColors = MapColors(
    panel = Color.White.copy(alpha = 0.96f),
    surface = Color.White,
    text = Ink,
    secondary = Ink.copy(alpha = 0.62f),
    divider = Ink.copy(alpha = 0.08f),
    night = false,
)

val NightMapColors = MapColors(
    panel = Color(0xF51B2440),
    surface = Color(0xFF1B2440),
    text = Color(0xFFF1F3F9),
    secondary = Color(0xB3F1F3F9),
    divider = Color.White.copy(alpha = 0.12f),
    night = true,
)

val LocalMapColors = androidx.compose.runtime.staticCompositionLocalOf { DayMapColors }
