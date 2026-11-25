package de.meshcore.pocketmonitor.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.meshcore.pocketmonitor.backup.BackupContents
import de.meshcore.pocketmonitor.backup.SetupRestoreStage
import de.meshcore.pocketmonitor.ble.ConnectionStatus

@Composable
internal fun BackupDialog(
    model: BackupViewModel,
    connection: ConnectionStatus,
    historyReady: Boolean,
    onAppRestored: suspend () -> Unit,
    onClose: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val appDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        it?.let { uri -> model.select(BackupAction.EXPORT_APP, uri) }
    }
    val setupDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        it?.let { uri -> model.select(BackupAction.EXPORT_SETUP, uri) }
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let { uri -> model.select(BackupAction.IMPORT, uri) }
    }
    val management = connection.management
    val setupReady = management?.available == true && !management.busy
    val session = management?.session

    when (val current = state) {
        BackupState.Idle -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Backup und Wiederherstellung") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sicherungsdateien sind passwortgeschützt.")
                    OutlinedButton(
                        onClick = { appDocument.launch("PocketMonitor-App.pmbackup") },
                        enabled = historyReady,
                    ) { Text("App-Daten sichern") }
                    OutlinedButton(
                        onClick = { setupDocument.launch("PocketMonitor-Companion.pmbackup") },
                        enabled = setupReady,
                    ) { Text("Companion-Setup sichern") }
                    OutlinedButton(onClick = { openDocument.launch(arrayOf("*/*")) }, enabled = historyReady) {
                        Text("Sicherung öffnen")
                    }
                    if (management == null) Text("Für das Companion-Setup den Companion verbinden.")
                }
            },
            confirmButton = { TextButton(onClick = onClose) { Text("Schließen") } },
        )
        is BackupState.Password -> BackupPasswordDialog(current, setupReady, connection.tcp, model::discard) { password, identity ->
            model.submit(password, identity, session)
        }
        is BackupState.Preview -> {
            val backup = current.contents
            var includeIdentity by remember(backup, session) { mutableStateOf(false) }
            val ready = when (backup) {
                is BackupContents.App -> historyReady && !connection.active
                is BackupContents.Setup -> setupReady
            }
            AlertDialog(
                onDismissRequest = model::discard,
                title = { Text("Wiederherstellung bestätigen") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (backup) {
                            is BackupContents.App -> {
                                Text("Die Sicherung ersetzt die gesamte lokale Historie und die App-Einstellungen auf diesem Android-Gerät.")
                                if (connection.active) Text("Trenne zuerst den Companion. Öffne danach die Sicherung erneut.")
                            }
                            is BackupContents.Setup -> {
                                val setup = backup.setup
                                val target = connection.contacts.journalName
                                Text("Quelle ${setup.name} · ${setup.publicKey.take(12)}")
                                if (target != null) {
                                    Text("Ziel · ${target.take(12)}")
                                } else Text("Verbinde zuerst das Zielgerät. Öffne danach die Sicherung erneut.")
                                Text("${setup.configuredChannels} belegte Kanal-Slots und ${setup.contacts.size} Kontakte. Funk- und Advert-Einstellungen sowie gesicherte Slots werden übernommen. Weitere Kontakte auf dem Ziel bleiben erhalten. Neue Kontakte ohne freien Platz werden ausgelassen.")
                                if (setup.privateKey != null && target != null && target != setup.publicKey) {
                                    BackupCheckbox("Identität auf das Zielgerät übertragen", includeIdentity) { includeIdentity = it }
                                    if (includeIdentity) {
                                        Text("Der private Schlüssel ersetzt die aktuelle Identität des Zielgeräts. Schalte den bisherigen Companion mit dieser Identität für den Umzug aus.")
                                    }
                                }
                                if (connection.tcp && includeIdentity) Text("TCP überträgt den Identitätsschlüssel ohne Transportverschlüsselung.")
                                Text("Die Sitzung wird nach dem Wiederherstellen getrennt.")
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { model.restore(session, includeIdentity, onAppRestored) },
                        enabled = ready,
                    ) { Text("Wiederherstellen") }
                },
                dismissButton = { TextButton(onClick = model::discard) { Text("Abbrechen") } },
            )
        }
        is BackupState.Working -> AlertDialog(
            onDismissRequest = {},
            title = { Text(if (current.setup == null) "Sicherung wird verarbeitet" else "Companion-Setup wird wiederhergestellt") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator()
                    current.setup?.let { restore ->
                        val progress = restore.progress
                        Text(when (progress.stage) {
                            SetupRestoreStage.PREPARING -> "Zielgerät wird geprüft …"
                            SetupRestoreStage.SETTINGS -> "Einstellungen werden übertragen …"
                            SetupRestoreStage.CHANNELS -> "Kanal-Slots werden übertragen und geprüft …"
                            SetupRestoreStage.CONTACTS -> "Kontakte werden übertragen …"
                            SetupRestoreStage.VERIFYING_CONTACTS -> "Kontakte werden zurückgelesen …"
                            SetupRestoreStage.VERIFYING_SETTINGS -> "Einstellungen werden zurückgelesen …"
                        })
                        Text("Kanäle wiederhergestellt: ${progress.restoredChannels}/${restore.channels}")
                        Text("Kontakte wiederhergestellt: ${progress.restoredContacts}/${restore.contacts}")
                        if (progress.skippedContacts > 0) {
                            Text("Kontakte ohne freien Platz: ${progress.skippedContacts}")
                        }
                    }
                }
            },
            confirmButton = {},
        )
        is BackupState.Notice -> AlertDialog(
            onDismissRequest = model::discard,
            title = { Text("Backup und Wiederherstellung") },
            text = { Text(current.message) },
            confirmButton = { TextButton(onClick = model::discard) { Text("OK") } },
        )
    }
}

@Composable
private fun BackupPasswordDialog(
    input: BackupState.Password,
    setupReady: Boolean,
    tcp: Boolean,
    onCancel: () -> Unit,
    onSubmit: (CharArray, Boolean) -> Unit,
) {
    var password by remember(input) { mutableStateOf("") }
    var confirmation by remember(input) { mutableStateOf("") }
    var identity by remember(input) { mutableStateOf(false) }
    val exporting = input.action != BackupAction.IMPORT
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (exporting) "Sicherungspasswort wählen" else "Sicherung entsperren") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (exporting) Text("Für die Wiederherstellung brauchst du dieses Passwort. Verwende mindestens 12 Zeichen.")
                BackupPasswordField(password, "Passwort") { password = it }
                if (exporting) BackupPasswordField(confirmation, "Passwort wiederholen") { confirmation = it }
                if (input.action == BackupAction.EXPORT_SETUP) {
                    BackupCheckbox("Privaten Identitätsschlüssel mitsichern", identity) { identity = it }
                    if (tcp && identity) Text("TCP überträgt den Identitätsschlüssel ohne Transportverschlüsselung.")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = (if (exporting) password.length >= 12 && password == confirmation else password.isNotEmpty()) &&
                    (input.action != BackupAction.EXPORT_SETUP || setupReady),
                onClick = {
                    val value = password.toCharArray()
                    password = ""
                    confirmation = ""
                    onSubmit(value, identity)
                },
            ) { Text(if (exporting) "Sichern" else "Öffnen") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Abbrechen") } },
    )
}

@Composable
private fun BackupPasswordField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}

@Composable
private fun BackupCheckbox(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onChange),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null)
        Text(label)
    }
}
