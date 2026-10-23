package com.faustinoloeza.parquex.ui

import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/** Idioma de la app; a diferencia de LocalContext, sí llega a popups, menús y hojas inferiores. */
val LocalAppLanguage = staticCompositionLocalOf { "es" }

/**
 * Textos de la interfaz en el idioma de la app ([tag]), sin depender del idioma del sistema ni
 * recrear la actividad (el mapa 3D no se recarga): `stringResource` y `context.getString` dentro
 * de [content] usan recursos con ese locale. El contexto sigue siendo la actividad para todo lo demás.
 *
 * Los popups y diálogos (DropdownMenu, ModalBottomSheet) vuelven a poner el contexto de su propia
 * ventana: su contenido debe envolverse en [PopupLanguage].
 */
@Composable
fun ProvideLanguage(tag: String, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val configuration = LocalConfiguration.current
    val localized = remember(base, configuration, tag) {
        val config = Configuration(configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        val resources = base.createConfigurationContext(config).resources
        config to object : ContextWrapper(base) {
            override fun getResources(): Resources = resources
        }
    }
    CompositionLocalProvider(
        LocalAppLanguage provides tag,
        LocalConfiguration provides localized.first,
        LocalContext provides localized.second,
        content = content,
    )
}

/** Reaplica el idioma de la app dentro de un popup o diálogo (ver [ProvideLanguage]). */
@Composable
fun PopupLanguage(content: @Composable () -> Unit) = ProvideLanguage(LocalAppLanguage.current, content)
