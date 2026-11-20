package de.meshcore.pocketmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.meshcore.pocketmonitor.ble.ManagementStatus
import de.meshcore.pocketmonitor.protocol.CompanionAction
import de.meshcore.pocketmonitor.protocol.CompanionGps

@Composable
internal fun CompanionLocationSettingsDialog(
    status: ManagementStatus,
    onAction: (CompanionAction, (Result<String?>) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var initialRead by remember { mutableStateOf(true) }
    var pending by remember { mutableStateOf<CompanionAction?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val ready = status.available && !status.busy && pending == null

    fun run(action: CompanionAction) {
        if (!status.available || status.busy || pending != null) return
        if (action == CompanionAction.ReadLocationSettings) initialRead = false
        pending = action
        error = null
        onAction(action) { result ->
            pending = null
            result.onFailure { error = it.message ?: "Companion-Einstellung konnte nicht bestätigt werden." }
        }
    }

    LaunchedEffect(ready) {
        if (ready && initialRead) run(CompanionAction.ReadLocationSettings)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Standortfreigabe und GPS") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val sharing = status.advertLocationEnabled
                LocationToggle(
                    label = "Standort in Adverts teilen",
                    checked = sharing,
                    enabled = ready && status.canSetAdvertLocation,
                    description = when {
                        !status.canSetAdvertLocation -> "Auf dieser Firmware nicht einstellbar."
                        sharing == null -> "Standortfreigabe unbekannt."
                        else -> null
                    },
                    onChange = { run(CompanionAction.SetAdvertLocation(it)) },
                )
                LocationToggle(
                    label = "GPS-Modul des Companion",
                    checked = when (status.gps) {
                        CompanionGps.ENABLED -> true
                        CompanionGps.DISABLED -> false
                        else -> null
                    },
                    enabled = ready,
                    description = when (status.gps) {
                        CompanionGps.UNKNOWN -> "GPS-Status unbekannt."
                        CompanionGps.UNAVAILABLE -> "Der Companion meldet kein steuerbares GPS-Modul."
                        CompanionGps.ENABLED -> "Das GPS kann manuell gesetzte Koordinaten aktualisieren."
                        CompanionGps.DISABLED -> null
                    },
                    onChange = { run(CompanionAction.SetGpsEnabled(it)) },
                )
                if (pending == CompanionAction.ReadLocationSettings) Text("Lese Einstellungen vom Companion …")
                else if (pending != null) Text("Warte auf Bestätigung des Companion …")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
        dismissButton = {
            TextButton(onClick = { run(CompanionAction.ReadLocationSettings) }, enabled = ready) {
                Text("Neu lesen")
            }
        },
    )
}

@Composable
private fun LocationToggle(
    label: String,
    checked: Boolean?,
    enabled: Boolean,
    description: String?,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        if (checked != null) {
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = label },
            )
        }
    }
}
