package com.faustinoloeza.parquex.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.faustinoloeza.parquex.data.ParkData
import com.faustinoloeza.parquex.map.CameraSnapshot
import com.faustinoloeza.parquex.ui.theme.InactiveGray
import com.faustinoloeza.parquex.ui.theme.Sunset
import com.faustinoloeza.parquex.ui.theme.Teal

/**
 * Marcadores de actividades como Compose sobre la vista 3D.
 *
 * Eficiencia: los hijos se miden una sola vez; [snapshot] se lee **solo en la fase de colocación**,
 * así que un movimiento de cámara provoca únicamente re-colocación (sin recomposición ni medición).
 * Se ocultan los marcadores detrás de la cámara, fuera de pantalla o que se enciman con otro ya
 * colocado (de menor número); el seleccionado siempre se muestra.
 */
@Composable
fun MarkerLayer(
    pois: List<ParkData.Poi>,
    snapshot: () -> CameraSnapshot?,
    selectedId: String?,
    onSelect: (ParkData.Poi) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Orden de prioridad: primero el seleccionado, luego por número.
    val order = remember(pois, selectedId) {
        pois.indices.sortedWith(compareBy({ pois[it].id != selectedId }, { pois[it].number })).toIntArray()
    }
    Layout(
        modifier = modifier,
        content = {
            pois.forEach { poi ->
                key(poi.id) { PoiMarker(poi, selected = poi.id == selectedId, onClick = { onSelect(poi) }) }
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            val snap = snapshot() ?: return@layout
            val out = FloatArray(2)
            val placed = ArrayList<FloatArray>(placeables.size)
            val w = constraints.maxWidth; val h = constraints.maxHeight
            for (i in order) {
                val poi = pois[i]
                val p = placeables[i]
                if (snap.project(poi.x, poi.y, poi.z, out) <= 0f) continue
                val x = out[0]; val y = out[1]
                if (x < -p.width || y < -p.height || x > w + p.width || y > h + p.height) continue
                val minGap = p.width * 0.9f
                val overlaps = poi.id != selectedId && placed.any {
                    kotlin.math.abs(it[0] - x) < minGap && kotlin.math.abs(it[1] - y) < minGap
                }
                if (overlaps) continue
                placed += floatArrayOf(x, y)
                p.place((x - p.width / 2f).toInt(), (y - p.height / 2f).toInt(), zIndex = if (poi.id == selectedId) 1f else 0f)
            }
        }
    }
}

@Composable
private fun PoiMarker(poi: ParkData.Poi, selected: Boolean, onClick: () -> Unit) {
    val color = when {
        !poi.active -> InactiveGray
        poi.extraCost -> Sunset
        else -> Teal
    }
    val size = if (selected) 34.dp else 26.dp
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = color,
        contentColor = Color.White,
        border = BorderStroke(if (selected) 3.dp else 2.dp, Color.White),
        shadowElevation = 3.dp,
        modifier = Modifier
            .size(size)
            .semantics { contentDescription = poi.name },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = poi.number.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontSize = if (selected) 13.sp else 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}
