package de.meshcore.pocketmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.meshcore.pocketmonitor.ble.ManagementStatus
import de.meshcore.pocketmonitor.protocol.CompanionAction

@Composable
internal fun CompanionRadioSettingsDialog(
    status: ManagementStatus,
    onAction: (CompanionAction, (Result<String?>) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var initialRead by remember { mutableStateOf(true) }
    var pending by remember { mutableStateOf<CompanionAction?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val canRead = status.canSetMultiAcks || status.canSetPathHashWidth
    val ready = status.available && !status.busy && pending == null

    fun run(action: CompanionAction) {
        if (!ready || pending != null) return
        if (action == CompanionAction.ReadRadioSettings) initialRead = false
        pending = action
        error = null
        onAction(action) { result ->
            pending = null
            result.onFailure { error = it.message ?: "Companion-Einstellung konnte nicht bestätigt werden." }
        }
    }

    LaunchedEffect(ready, canRead) {
        if (initialRead && ready && canRead) run(CompanionAction.ReadRadioSettings)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Funkeinstellungen") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RadioChoices(
                    title = "Multi-ACKs (multi.acks)",
                    current = status.multiAcks?.let { if (it) 1 else 0 },
                    choices = 0..1,
                    supported = status.canSetMultiAcks,
                    enabled = ready && !initialRead,
                    label = { if (it == 0) "0 · Aus" else "1 · Ein" },
                    select = { run(CompanionAction.SetMultiAcks(it == 1)) },
                )
                RadioChoices(
                    title = "Pfad-Hash-Länge",
                    current = status.pathHashWidth,
                    choices = 1..3,
                    supported = status.canSetPathHashWidth,
                    enabled = ready && !initialRead,
                    label = { "$it Byte" },
                    select = { run(CompanionAction.SetPathHashWidth(it)) },
                )
                when {
                    pending == CompanionAction.ReadRadioSettings -> Text("Lese Einstellungen vom Companion …")
                    pending != null -> Text("Warte auf Bestätigung des Companion …")
                    !status.available -> Text("Companion erneut verbinden.")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
        dismissButton = {
            TextButton(onClick = { run(CompanionAction.ReadRadioSettings) }, enabled = ready && canRead) {
                Text("Neu lesen")
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RadioChoices(
    title: String,
    current: Int?,
    choices: IntRange,
    supported: Boolean,
    enabled: Boolean,
    label: (Int) -> String,
    select: (Int) -> Unit,
) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (supported) {
            if (enabled && current == null) Text("Aktueller Wert unbekannt.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEach { choice ->
                    FilterChip(
                        selected = choice == current,
                        onClick = { if (choice != current) select(choice) },
                        enabled = enabled,
                        label = { Text(label(choice)) },
                    )
                }
            }
        } else {
            Text("Für diese Companion-Verbindung nicht verfügbar.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
