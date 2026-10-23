package com.faustinoloeza.parquex.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.faustinoloeza.parquex.map.CameraSnapshot
import com.faustinoloeza.parquex.ui.theme.LocationBlue

/** Posición del usuario en metros locales; [accuracy] en metros (0 = sin halo, p. ej. simulada). */
data class UserPosition(val x: Float, val z: Float, val accuracy: Float)

/**
 * Punto azul de "estás aquí" sobre la vista 3D. [position] y [snapshot] se leen **en la fase de
 * dibujo**: moverse o mover la cámara solo redibuja este lienzo (ni recomposición ni cuadros de
 * Filament). [surfaceAt] = altura de lo que se pisa (`ParkData.surfaceAt`: la cubierta de un puente).
 */
@Composable
fun LocationLayer(
    position: () -> UserPosition?,
    snapshot: () -> CameraSnapshot?,
    surfaceAt: (Float, Float) -> Float,
    modifier: Modifier = Modifier,
) {
    val out = remember { FloatArray(2) }
    Spacer(
        modifier.drawBehind {
            val snap = snapshot() ?: return@drawBehind
            val p = position() ?: return@drawBehind
            val depth = snap.project(p.x, surfaceAt(p.x, p.z) + 0.5f, p.z, out)
            if (depth <= 0f) return@drawBehind
            val c = Offset(out[0], out[1])
            val margin = 48.dp.toPx()
            if (c.x < -margin || c.y < -margin || c.x > size.width + margin || c.y > size.height + margin) return@drawBehind
            val dot = 7.dp.toPx()
            if (p.accuracy > 0f) {
                val r = (p.accuracy * snap.focalPx / depth).coerceIn(dot * 1.8f, size.maxDimension)
                drawCircle(LocationBlue.copy(alpha = 0.14f), r, c)
                drawCircle(LocationBlue.copy(alpha = 0.4f), r, c, style = Stroke(1.dp.toPx()))
            }
            drawCircle(Color.Black.copy(alpha = 0.18f), dot + 3.5.dp.toPx(), c + Offset(0f, 1.dp.toPx()))
            drawCircle(Color.White, dot + 2.5.dp.toPx(), c)
            drawCircle(LocationBlue, dot, c)
        }
    )
}
