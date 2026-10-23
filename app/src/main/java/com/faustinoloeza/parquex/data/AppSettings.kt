package com.faustinoloeza.parquex.data

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Preferencias del usuario que sobreviven entre sesiones (SharedPreferences): idioma, calidad
 * gráfica, día/noche y animaciones. Son estado de Compose: cambiar una recompone quien la lee.
 */
class AppSettings(context: Context) {

    enum class Language(val tag: String) { ES("es"), EN("en") }

    /** Calidad elegida; [AUTO] la decide el dispositivo (y baja sola si los cuadros se alargan). */
    enum class QualityMode { AUTO, HIGH, MEDIUM, LOW }

    /** Día o noche; [AUTO] = noche de 19:00 a 06:00 hora del parque. */
    enum class DayNight { AUTO, DAY, NIGHT }

    private val prefs = context.getSharedPreferences("parquex", Context.MODE_PRIVATE)

    var language by mutableStateOf(
        prefs.getString(KEY_LANGUAGE, null)?.let { tag -> Language.entries.firstOrNull { it.tag == tag } } ?: systemLanguage()
    )
        private set

    var quality by mutableStateOf(
        prefs.getString(KEY_QUALITY, null)?.let { name -> QualityMode.entries.firstOrNull { it.name == name } } ?: QualityMode.AUTO
    )
        private set

    var dayNight by mutableStateOf(
        prefs.getString(KEY_DAY_NIGHT, null)?.let { name -> DayNight.entries.firstOrNull { it.name == name } } ?: DayNight.AUTO
    )
        private set

    /** Torre, voladores, banderas, aves y mariposas en movimiento (solo en calidad Alta y Media). */
    var animations by mutableStateOf(prefs.getBoolean(KEY_ANIMATIONS, true))
        private set

    fun updateAnimations(value: Boolean) {
        animations = value
        prefs.edit().putBoolean(KEY_ANIMATIONS, value).apply()
    }

    fun updateDayNight(value: DayNight) {
        dayNight = value
        prefs.edit().putString(KEY_DAY_NIGHT, value.name).apply()
    }

    fun updateLanguage(value: Language) {
        language = value
        prefs.edit().putString(KEY_LANGUAGE, value.tag).apply()
    }

    fun updateQuality(value: QualityMode) {
        quality = value
        prefs.edit().putString(KEY_QUALITY, value.name).apply()
    }

    private companion object {
        const val KEY_LANGUAGE = "language"
        const val KEY_QUALITY = "quality"
        const val KEY_DAY_NIGHT = "dayNight"
        const val KEY_ANIMATIONS = "animations"

        /** El del sistema si es español o inglés; si no, español. */
        fun systemLanguage(): Language {
            val tag = Resources.getSystem().configuration.locales[0]?.language
            return Language.entries.firstOrNull { it.tag == tag } ?: Language.ES
        }
    }
}
