package de.meshcore.pocketmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.meshcore.pocketmonitor.ble.ChannelManagementStatus
import de.meshcore.pocketmonitor.protocol.ChannelReference
import de.meshcore.pocketmonitor.protocol.CompanionAction
import de.meshcore.pocketmonitor.protocol.NewChannel

@Composable
internal fun ChannelManagementDialog(
    status: ChannelManagementStatus?,
    submit: (CompanionAction.EditChannel, (Result<String?>) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<ChannelReference?>(null) }
    var name by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val target = remove
    val available = status != null && !status.busy && !saving

    fun resetForm() {
        adding = false
        remove = null
        name = ""
        secret = ""
        error = null
    }

    fun save(action: CompanionAction.EditChannel) {
        saving = true
        error = null
        submit(action) { result ->
            saving = false
            if (result.isSuccess) resetForm()
            else error = result.exceptionOrNull()?.message ?: "Kanaländerung fehlgeschlagen."
        }
    }

    val close = {
        if (!saving) {
            if (adding || target != null) resetForm() else onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(when {
            adding -> "Kanal hinzufügen"
            target != null -> "Kanal löschen"
            else -> "Kanäle verwalten"
        }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    adding -> {
                        val publicName = NewChannel.publicName(name.trim())
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it; error = null },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Kanalname") },
                            supportingText = { Text("Maximal 31 UTF-8-Bytes. Public und #Hashtags erhalten ihren Schlüssel automatisch.") },
                            singleLine = true,
                            enabled = !saving,
                        )
                        if (!publicName) OutlinedTextField(
                            value = secret,
                            onValueChange = { secret = it; error = null },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Kanalschlüssel") },
                            supportingText = { Text("16 Byte als Hex oder Base64") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = PasswordVisualTransformation(),
                            enabled = !saving,
                        )
                    }
                    target != null -> Text(
                        "„${target.name}“ (Slot ${target.slot}) vom Companion löschen? Gespeicherte Nachrichten bleiben im Archiv.",
                    )
                    status != null -> {
                        if (!status.hasFreeSlot) Text("Kein freier Kanal-Slot auf dem Companion.")
                        if (status.channels.isEmpty()) Text("Keine Kanäle eingerichtet.")
                        status.channels.forEach { channel ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(channel.name)
                                    Text("Slot ${channel.slot}", style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(enabled = available, onClick = {
                                    remove = channel
                                    error = null
                                }) { Text("Löschen") }
                            }
                        }
                    }
                }
                if (saving) Text("Kanaländerung läuft …")
                else if (status == null && error == null) Text("Kanalliste vorübergehend nicht verfügbar.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            when {
                adding -> TextButton(enabled = available && status.hasFreeSlot, onClick = {
                    val channel = NewChannel.parse(name, secret)
                    when {
                        !NewChannel.validName(name) -> error = "Bitte einen gültigen Kanalnamen eingeben."
                        channel == null -> error = "Bitte einen gültigen 16-Byte-Kanalschlüssel eingeben."
                        else -> save(CompanionAction.EditChannel.Add(channel))
                    }
                }) { Text("Hinzufügen") }
                target != null -> TextButton(enabled = available, onClick = {
                    save(CompanionAction.EditChannel.Remove(target))
                }) { Text("Löschen") }
                else -> TextButton(enabled = available && status.hasFreeSlot,
                    onClick = { adding = true }) { Text("Kanal hinzufügen") }
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = close) {
                Text(if (adding || target != null) "Abbrechen" else "Schließen")
            }
        },
    )
}
