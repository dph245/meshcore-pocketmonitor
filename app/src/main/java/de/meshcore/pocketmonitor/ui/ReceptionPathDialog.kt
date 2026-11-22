package de.meshcore.pocketmonitor.ui

import android.database.sqlite.SQLiteException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReceptionPathDialog(
    model: MonitorViewModel,
    companion: String,
    message: ChannelMessage,
    onDismiss: () -> Unit,
) {
    var paths by remember(companion, message.sequence) {
        mutableStateOf<Result<List<ReceptionPathLabel>>?>(null)
    }
    LaunchedEffect(model, companion, message.sequence, message.receptionCount) {
        paths = try {
            Result.success(withContext(Dispatchers.IO) {
                val receptions = model.groupReceptionPaths(companion, message.sequence)
                val repeaters = model.groupRepeaterNames(companion)
                val hopLabels = receptions.keys.flatten().toSet().associateWith { prefix ->
                    val matches = repeaters.filterKeys { it.startsWith(prefix, ignoreCase = true) }.values
                    when (matches.size) {
                        0 -> "$prefix (unbekannt)"
                        1 -> "${matches.single()} ($prefix)"
                        else -> "$prefix (mehrdeutig, ${matches.size} Treffer)"
                    }
                }
                receptions.map { (prefixes, count) ->
                    val label = if (prefixes.isEmpty()) "Ohne Zwischenstationen (0 Hops)"
                    else prefixes.joinToString(" → ") { hopLabels.getValue(it) }
                    ReceptionPathLabel(label, count)
                }
            })
        } catch (error: SQLiteException) {
            Result.failure(error)
        }
    }

    val result = paths
    val labels = result?.getOrNull().orEmpty()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("Empfangspfad", style = MaterialTheme.typography.titleLarge) }
            if (labels.isEmpty()) {
                item {
                    val status = when {
                        result == null -> "Empfangspfade werden geladen …"
                        result.isFailure -> "Empfangspfade konnten nicht gelesen werden."
                        else -> "Kein aufgezeichneter Empfangspfad verfügbar."
                    }
                    Text(status)
                }
            }
            items(labels) { path ->
                Text(path.label, style = MaterialTheme.typography.bodyLarge)
                Text(if (path.count == 1) "1 Empfang" else "${path.count} Empfänge",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private data class ReceptionPathLabel(val label: String, val count: Int)
