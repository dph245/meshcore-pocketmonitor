package de.meshcore.pocketmonitor.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal data class HistoryCleanupState(
    val running: Boolean = false,
    val message: String? = null,
    val error: Boolean = false,
)

@Composable
internal fun HistoryStorage(model: MonitorViewModel, ready: Boolean, cleanup: HistoryCleanupState) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var size by remember { mutableStateOf<Long?>(null) }
    var sizeRead by remember { mutableStateOf(false) }
    var dialogOpen by rememberSaveable { mutableStateOf(false) }
    var days by rememberSaveable { mutableIntStateOf(14) }

    LaunchedEffect(model, lifecycle, ready, cleanup.running) {
        if (!ready || cleanup.running) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                size = model.databaseSizeBytes()
                sizeRead = true
                delay(5_000)
            }
        }
    }
    val sizeLabel = size?.let { Formatter.formatShortFileSize(context, it) }
        ?: if (sizeRead) "Größe nicht verfügbar" else "Größe wird ermittelt …"
    Text("App-Datenbank · $sizeLabel", style = MaterialTheme.typography.bodyMedium)
    OutlinedButton(onClick = { dialogOpen = true }, enabled = ready && !cleanup.running) {
        Text(if (cleanup.running) "Datenbank wird bereinigt …" else "Alte Nachrichten löschen")
    }
    cleanup.message?.let {
        Text(
            it, style = MaterialTheme.typography.bodySmall,
            color = if (cleanup.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
    if (dialogOpen && !cleanup.running) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text("Alte Nachrichten löschen?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                    Text("Löscht ältere Kanal- und Privatnachrichten aus der lokalen Historie aller Companions und verdichtet die Datenbank.")
                    for (option in listOf(1, 7, 14, 21)) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                                selected = days == option, role = Role.RadioButton, onClick = { days = option },
                            ),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = days == option, onClick = null)
                            Text(if (option == 1) "Älter als 24 Stunden" else "Älter als $option Tage")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = ready, onClick = {
                    dialogOpen = false
                    model.deleteOldMessages(days)
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { dialogOpen = false }) { Text("Abbrechen") } },
        )
    }
}
