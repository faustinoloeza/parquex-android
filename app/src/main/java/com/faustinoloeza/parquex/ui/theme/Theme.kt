package com.faustinoloeza.parquex.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = TealContainer,
    onPrimaryContainer = Ink,
    secondary = Sunset,
    onSecondary = Color.White,
    secondaryContainer = SunsetContainer,
    onSecondaryContainer = Ink,
    background = Cream,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Sky,
    onSurfaceVariant = Ink,
)

/** Tema claro fijo (el mapa está diseñado para fondo claro; sin colores dinámicos). */
@Composable
fun ParquexTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, typography = Typography, content = content)
}
