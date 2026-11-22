package de.meshcore.pocketmonitor.ui

import android.database.sqlite.SQLiteException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.meshcore.pocketmonitor.ble.ChannelManagementStatus
import de.meshcore.pocketmonitor.protocol.ChannelReference
import de.meshcore.pocketmonitor.protocol.CompanionAction
import de.meshcore.pocketmonitor.protocol.NewChannel
import de.meshcore.pocketmonitor.rx.HistoryChannel
import kotlinx.coroutines.launch

internal sealed interface ChannelListAction {
    val label: String

    data class Add(val channel: HistoryChannel) : ChannelListAction {
        override val label = "Zu Companion hinzufügen"
    }

    data class Remove(val channel: ChannelReference) : ChannelListAction {
        override val label = "Vom Companion entfernen"
    }

    data class Delete(val channel: HistoryChannel) : ChannelListAction {
        override val label = "Aus dem Archiv löschen"
    }
}

@Composable
internal fun ChannelActionMenu(
    channel: HistoryChannel,
    installed: ChannelReference?,
    onDismiss: () -> Unit,
    onSelect: (ChannelListAction) -> Unit,
) {
    val actions = if (installed != null) {
        listOf(ChannelListAction.Remove(installed))
    } else {
        listOfNotNull(
            ChannelListAction.Add(channel).takeUnless { channel.identity.startsWith("slot:") },
            ChannelListAction.Delete(channel),
        )
    }
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        actions.forEach { action ->
            DropdownMenuItem(text = { Text(action.label) }, onClick = { onSelect(action) })
        }
    }
}

@Composable
internal fun ChannelActionDialog(
    action: ChannelListAction,
    status: ChannelManagementStatus?,
    model: MonitorViewModel,
    companion: String,
    onDismiss: () -> Unit,
) {
    var secret by remember(action) { mutableStateOf("") }
    var saving by remember(action) { mutableStateOf(false) }
    var error by remember(action) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val archived = (action as? ChannelListAction.Add)?.channel
    val publicChannel = remember(archived) { archived?.let { NewChannel.restore(it.name, it.identity) } }
    val unavailable = when (action) {
        is ChannelListAction.Add -> when {
            !NewChannel.validStoredName(action.channel.name) -> "Der archivierte Kanalname passt nicht in einen Companion-Slot."
            status == null -> "Kanalliste des Companion derzeit nicht verfügbar."
            status.busy -> "Der Companion verarbeitet gerade einen Befehl."
            status.channels.any { it.identity == action.channel.identity } -> "Der Kanal ist bereits auf dem Companion eingerichtet."
            !status.hasFreeSlot -> "Auf dem Companion ist kein Kanal-Slot frei."
            else -> null
        }
        is ChannelListAction.Remove -> when {
            status == null -> "Kanalliste des Companion derzeit nicht verfügbar."
            status.busy -> "Der Companion verarbeitet gerade einen Befehl."
            action.channel !in status.channels -> "Die Kanalkonfiguration wurde geändert."
            else -> null
        }
        is ChannelListAction.Delete -> {
            "Der Kanal ist inzwischen auf dem Companion eingerichtet."
                .takeIf { status?.channels?.any { it.identity == action.channel.identity } == true }
        }
    }
    val close = { if (!saving) onDismiss() }

    fun submit(edit: CompanionAction.EditChannel) {
        val session = status?.session ?: return
        saving = true
        error = null
        model.manageCompanion(session, edit) { result ->
            saving = false
            if (result.isSuccess) onDismiss()
            else error = result.exceptionOrNull()?.message ?: "Kanaländerung fehlgeschlagen."
        }
    }

    AlertDialog(
        onDismissRequest = close,
        title = { Text(action.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (action) {
                    is ChannelListAction.Add -> {
                        Text("„${action.channel.name}“ auf dem Companion einrichten?")
                        if (publicChannel == null && NewChannel.validStoredName(action.channel.name)) OutlinedTextField(
                            value = secret,
                            onValueChange = { secret = it; error = null },
                            label = { Text("Ursprünglicher Kanalschlüssel") },
                            supportingText = { Text("16 Byte als Hex oder Base64") },
                            singleLine = true,
                            enabled = !saving,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = PasswordVisualTransformation(),
                        )
                    }
                    is ChannelListAction.Remove ->
                        Text("„${action.channel.name}“ (Slot ${action.channel.slot}) vom Companion entfernen? Gespeicherte Nachrichten bleiben im Archiv.")
                    is ChannelListAction.Delete ->
                        Text("„${action.channel.name}“ und alle gespeicherten Nachrichten dieses Kanals aus dem Archiv löschen?")
                }
                if (saving) Text("Kanaländerung läuft …")
                else (error ?: unavailable)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && unavailable == null, onClick = {
                when (action) {
                    is ChannelListAction.Add -> {
                        val channel = publicChannel
                            ?: NewChannel.restore(action.channel.name, action.channel.identity, secret)
                        if (channel != null) submit(CompanionAction.EditChannel.Add(channel))
                        else error = "Der Kanalschlüssel passt nicht zum archivierten Kanal."
                    }
                    is ChannelListAction.Remove -> submit(CompanionAction.EditChannel.Remove(action.channel))
                    is ChannelListAction.Delete -> {
                        saving = true
                        error = null
                        scope.launch {
                            try {
                                if (model.deleteArchivedChannel(companion, action.channel.identity)) onDismiss()
                                else error = "Der Kanal ist nicht mehr als Archivkanal verfügbar."
                            } catch (_: SQLiteException) {
                                error = "Der Kanal konnte nicht aus dem Archiv gelöscht werden."
                            } finally {
                                saving = false
                            }
                        }
                    }
                }
            }) { Text(when (action) {
                is ChannelListAction.Add -> "Hinzufügen"
                is ChannelListAction.Remove -> "Entfernen"
                is ChannelListAction.Delete -> "Löschen"
            }) }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = close) { Text("Abbrechen") } },
    )
}
