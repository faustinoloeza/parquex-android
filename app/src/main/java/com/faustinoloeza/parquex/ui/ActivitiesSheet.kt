package com.faustinoloeza.parquex.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.faustinoloeza.parquex.R
import com.faustinoloeza.parquex.data.ParkData
import com.faustinoloeza.parquex.ui.theme.InactiveGray
import com.faustinoloeza.parquex.ui.theme.Ink
import com.faustinoloeza.parquex.ui.theme.Sunset
import com.faustinoloeza.parquex.ui.theme.Teal
import java.text.Normalizer

/**
 * Lista de actividades del parque en una hoja inferior: búsqueda por nombre, número o categoría
 * (sin distinguir acentos) y lista por número. Al elegir una, [onPick] (la pantalla la selecciona y
 * vuela la cámara hasta ella).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivitiesSheet(
    park: ParkData,
    onDismiss: () -> Unit,
    onPick: (ParkData.Poi) -> Unit,
    onDirections: (ParkData.Poi) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val sorted = remember(park) { park.pois.sortedBy { it.number } }
    val results = remember(sorted, query) {
        val q = fold(query.trim())
        if (q.isEmpty()) sorted else sorted.filter { poi ->
            poi.number.toString().startsWith(q) || fold(poi.name).contains(q) || fold(poi.category).contains(q)
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = Color.White,
    ) {
        // La hoja es otra ventana: reaplica el idioma de la app (ver ProvideLanguage).
        PopupLanguage {
            Column {
                Header(park, query, onQuery = { query = it })
                if (results.isEmpty()) {
                    Text(
                        stringResource(R.string.no_results),
                        style = MaterialTheme.typography.bodyMedium, color = Ink.copy(alpha = 0.6f),
                        modifier = Modifier.padding(24.dp),
                    )
                }
                LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                    items(results, key = { it.id }) { poi ->
                        PoiRow(poi, onClick = { onPick(poi) }, onDirections = { onDirections(poi) })
                        HorizontalDivider(color = Ink.copy(alpha = 0.06f), modifier = Modifier.padding(start = 64.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(park: ParkData, query: String, onQuery: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(park.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
        park.hours?.let {
            Text(
                stringResource(R.string.park_hours, it.open, it.close),
                style = MaterialTheme.typography.bodyMedium, color = Ink.copy(alpha = 0.7f),
            )
        }
        Spacer(Modifier.size(12.dp))
        val clear = stringResource(R.string.clear_search)
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            trailingIcon = if (query.isNotEmpty()) ({
                Box(
                    Modifier.size(40.dp).clickable { onQuery("") }.semantics { contentDescription = clear },
                    contentAlignment = Alignment.Center,
                ) { Text("✕", color = Ink.copy(alpha = 0.6f)) }
            }) else null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PoiRow(poi: ParkData.Poi, onClick: () -> Unit, onDirections: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        val color = when {
            !poi.active -> InactiveGray
            poi.extraCost -> Sunset
            else -> Teal
        }
        Surface(shape = CircleShape, color = color, contentColor = Color.White, modifier = Modifier.size(32.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(poi.number.toString(), fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(poi.name, style = MaterialTheme.typography.bodyLarge, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val detail = listOfNotNull(poi.category.takeIf { it.isNotBlank() }, poi.route).joinToString(" · ")
            if (detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = Ink.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        TextButton(onClick = onDirections) { Text(stringResource(R.string.nav_go)) }
    }
}

/** Minúsculas y sin acentos, para buscar "rio" y encontrar "Río". */
private fun fold(text: String): String =
    Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
