package de.meshcore.pocketmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.meshcore.pocketmonitor.ble.ContactStatus
import de.meshcore.pocketmonitor.ble.ManagementStatus
import de.meshcore.pocketmonitor.protocol.CompanionAction
import de.meshcore.pocketmonitor.protocol.CompanionManagement

private enum class CompanionPanel { SCOPE, CONTACTS, CLI, POSITION, LOCATION_SETTINGS }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CompanionControls(status: ManagementStatus, contacts: ContactStatus, model: MonitorViewModel) {
    var panel by remember(status.session) { mutableStateOf<CompanionPanel?>(null) }
    var advertPending by remember(status.session) { mutableStateOf(false) }
    var feedback by remember(status.session) { mutableStateOf<Result<String>?>(null) }
    val ready = status.available && !status.busy

    fun sendAdvert(flood: Boolean) {
        if (!ready || advertPending) return
        val name = if (flood) "Flood-Advert" else "Zero-Hop-Advert"
        advertPending = true
        feedback = null
        model.manageCompanion(status.session, CompanionAction.SendAdvert(flood)) { result ->
            advertPending = false
            feedback = result.map { "$name vom Companion angenommen." }
        }
    }

    Text("Standard-Scope · ${status.scopeName?.ifEmpty { "Ohne Scope" } ?: "Nicht verfügbar"}",
        style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { panel = CompanionPanel.SCOPE }, enabled = status.available && status.scopeName != null) {
            Text("Scope ändern")
        }
        OutlinedButton(onClick = { panel = CompanionPanel.CONTACTS }) {
            Text(contacts.book?.let { "Kontakte (${it.list.contacts.size})" } ?: "Kontakte")
        }
        OutlinedButton(onClick = { panel = CompanionPanel.CLI }) { Text("Companion-CLI") }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { sendAdvert(false) }, enabled = ready && !advertPending) {
            Text("Advert Zero Hop")
        }
        OutlinedButton(onClick = { sendAdvert(true) }, enabled = ready && !advertPending) {
            Text("Advert Flood")
        }
        OutlinedButton(onClick = { panel = CompanionPanel.POSITION }, enabled = status.available) {
            Text("GPS-Position")
        }
        OutlinedButton(onClick = { panel = CompanionPanel.LOCATION_SETTINGS }, enabled = ready) {
            Text("Standortfreigabe und GPS")
        }
    }
    if (advertPending) Text("Warte auf die Bestätigung des Companion …", style = MaterialTheme.typography.bodySmall)
    feedback?.fold(
        onSuccess = { Text(it, style = MaterialTheme.typography.bodySmall) },
        onFailure = { Text(it.message ?: "Advert fehlgeschlagen.", color = MaterialTheme.colorScheme.error) },
    )
    if (!status.available) {
        Text("Companion erneut verbinden, um Befehle auszuführen.", color = MaterialTheme.colorScheme.error)
    }

    when (panel) {
        CompanionPanel.SCOPE -> ScopeDialog(
            scope = status.scopeName,
            enabled = ready,
            onSave = { scope, completed ->
                model.manageCompanion(status.session, CompanionAction.SetScope(scope), completed)
            },
            onDismiss = { panel = null },
        )
        CompanionPanel.CONTACTS -> ContactOverview(
            contacts, ready, model::refreshContacts,
            onChange = { action, completed -> model.manageCompanion(status.session, action, completed) },
            onDismiss = { panel = null },
        )
        CompanionPanel.CLI -> CompanionTerminal(
            status = status,
            terminal = model.cliTerminal(status.session),
            onRun = { command, completed ->
                model.manageCompanion(status.session, CompanionAction.RunCli(command), completed)
            },
            onDismiss = { panel = null },
        )
        CompanionPanel.POSITION -> CompanionPositionDialog(
            current = status.position,
            enabled = ready,
            onSave = { position, completed ->
                model.manageCompanion(status.session, CompanionAction.SetPosition(position)) { result ->
                    if (result.isSuccess) feedback = Result.success("Position auf dem Companion gespeichert.")
                    completed(result)
                }
            },
            onDismiss = { panel = null },
        )
        CompanionPanel.LOCATION_SETTINGS -> CompanionLocationSettingsDialog(
            status = status,
            onAction = { action, completed -> model.manageCompanion(status.session, action, completed) },
            onDismiss = { panel = null },
        )
        null -> Unit
    }
}

@Composable
private fun ScopeDialog(
    scope: String?,
    enabled: Boolean,
    onSave: (String, (Result<String?>) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(scope.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val valid = CompanionManagement.scopeCommand(text) != null
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Standard-Scope") },
        text = {
            Column {
                OutlinedTextField(
                    value = text, onValueChange = { text = it; error = null },
                    label = { Text("Scope") }, placeholder = { Text("#de-ni") },
                    supportingText = { Text(if (valid) "Leer lassen, um den Scope zu entfernen."
                        else CompanionManagement.SCOPE_INPUT_ERROR) },
                    enabled = !saving, isError = !valid,
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (scope == null) Text("Standard-Scope nicht verfügbar.")
                if (saving) Text("Scope wird gespeichert …")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                saving = true
                onSave(text) { result ->
                    saving = false
                    result.fold(onSuccess = { onDismiss() }, onFailure = { error = it.message })
                }
            }, enabled = enabled && !saving && valid && scope != null) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Abbrechen") } },
    )
}
