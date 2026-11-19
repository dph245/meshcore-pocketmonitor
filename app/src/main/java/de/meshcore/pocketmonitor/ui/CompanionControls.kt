package de.meshcore.pocketmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import de.meshcore.pocketmonitor.ble.ContactStatus
import de.meshcore.pocketmonitor.ble.ManagementStatus
import de.meshcore.pocketmonitor.protocol.CompanionAction
import de.meshcore.pocketmonitor.protocol.CompanionManagement

private enum class CompanionPanel { SCOPE, CONTACTS, CLI }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CompanionControls(status: ManagementStatus, contacts: ContactStatus, model: MonitorViewModel) {
    var panel by remember(status.session) { mutableStateOf<CompanionPanel?>(null) }
    var command by remember(status.session) { mutableStateOf("") }
    var reply by remember(status.session) { mutableStateOf<String?>(null) }
    var cliError by remember(status.session) { mutableStateOf<String?>(null) }
    var waitingForCli by remember(status.session) { mutableStateOf(false) }
    val ready = status.available && !status.busy

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
        CompanionPanel.CLI -> {
            val focusManager = LocalFocusManager.current
            val limit = status.cliTextLimit
            val canSend = ready && !waitingForCli && limit != null &&
                CompanionManagement.cliCommand(command, "00|", limit) != null
            AlertDialog(
                onDismissRequest = { panel = null },
                title = { Text("Companion-CLI") },
                confirmButton = {
                    TextButton(onClick = {
                        focusManager.clearFocus()
                        waitingForCli = true
                        reply = null
                        cliError = null
                        model.manageCompanion(status.session, CompanionAction.RunCli(command)) { result ->
                            waitingForCli = false
                            result.fold(
                                onSuccess = { reply = it?.ifEmpty { "Leere Antwort" } },
                                onFailure = { cliError = it.message },
                            )
                        }
                    }, enabled = canSend) { Text("Ausführen") }
                },
                dismissButton = { TextButton(onClick = { panel = null }) { Text("Schließen") } },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (limit == null) {
                            Text("Benötigt Companion-Protokollversion 14 oder höher.")
                        } else {
                            OutlinedTextField(
                                value = command,
                                onValueChange = { command = it },
                                singleLine = true,
                                label = { Text("Kommando") },
                                placeholder = { Text("get name") },
                                supportingText = { Text("${command.encodeToByteArray().size}/$limit UTF-8-Bytes") },
                                enabled = !waitingForCli,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (waitingForCli) Text("Warte auf die CLI-Antwort …")
                        if (!status.available) Text("Companion erneut verbinden.")
                        cliError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        reply?.let { output ->
                            Text(output, Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                                fontFamily = FontFamily.Monospace)
                            TextButton(onClick = { reply = null; cliError = null }) { Text("Ausgabe löschen") }
                        }
                    }
                },
            )
        }
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
