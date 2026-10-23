package com.faustinoloeza.parquex

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.faustinoloeza.parquex.data.AppSettings
import com.faustinoloeza.parquex.ui.ParkMapScreen
import com.faustinoloeza.parquex.ui.ProvideLanguage
import com.faustinoloeza.parquex.ui.theme.ParquexTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Borde a borde con iconos oscuros en barras transparentes (tema claro fijo).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        val settings = AppSettings(applicationContext)
        setContent {
            ParquexTheme {
                // Idioma de la app (ajustes), independiente del del sistema.
                ProvideLanguage(settings.language.tag) {
                    ParkMapScreen(settings)
                }
            }
        }
    }
}
