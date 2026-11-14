package de.meshcore.pocketmonitor.ui

import android.database.sqlite.SQLiteException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import de.meshcore.pocketmonitor.ble.GroupSender
import de.meshcore.pocketmonitor.protocol.CompanionEvent
import de.meshcore.pocketmonitor.protocol.CompanionEventDecoder
import de.meshcore.pocketmonitor.protocol.GroupSend
import de.meshcore.pocketmonitor.rx.GroupRepeat
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun GroupComposer(
    draft: String, edit: (String) -> Unit, scope: String?, selectScope: (String) -> Unit,
    sender: GroupSender?, submitting: Boolean, error: String?, send: (String, String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val bytes = remember(draft) { GroupSend.textBytes(draft)?.size }
    val validScope = scope != null && sender?.scopes?.contains(scope) == true
    val tooLong = bytes != null && sender != null && bytes > sender.maxBytes
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { expanded = true }, enabled = sender != null && !submitting) {
                Text("Scope · ${scope ?: GroupSend.DEFAULT} ▾")
            }
            DropdownMenu(expanded = expanded && sender != null, onDismissRequest = { expanded = false }) {
                sender?.scopes?.forEach { option ->
                    DropdownMenuItem(text = { Text(option) }, onClick = {
                        selectScope(option)
                        expanded = false
                    })
                }
            }
            if (sender != null && bytes != null) {
                Text("$bytes/${sender.maxBytes} B", style = MaterialTheme.typography.labelSmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = draft, onValueChange = edit, modifier = Modifier.weight(1f),
                placeholder = { Text("Nachricht") }, minLines = 1, maxLines = 3, isError = tooLong,
                enabled = !submitting)
            Button(onClick = { scope?.let { send(draft, it) } },
                enabled = sender != null && !sender.busy && !submitting && bytes != null && !tooLong && validScope) {
                Text("Senden")
            }
        }
        val hint = error ?: when {
            submitting -> "Übergabe an den Companion …"
            sender == null -> "Kanal offline oder auf dem verbundenen Companion nicht sendebereit."
            scope != null && !validScope -> "Dieser Scope wird vom Companion nicht unterstützt."
            draft.isNotBlank() && bytes == null -> "Nachricht enthält ungültige Zeichen."
            else -> null
        }
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupDetails(
    model: MonitorViewModel, companion: String, message: ChannelMessage,
    onDismiss: () -> Unit, onReuse: () -> Unit,
) {
    val sent = message.outgoing ?: return
    var repeats by remember(message.sequence) { mutableStateOf<List<GroupRepeat>?>(null) }
    var names by remember(companion) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var error by remember(message.sequence) { mutableStateOf(false) }
    LaunchedEffect(model, message.sequence, sent.repeats) {
        try {
            val (events, contacts) = withContext(Dispatchers.IO) {
                model.groupRepeats(message.sequence) to model.groupRepeaterNames(companion)
            }
            repeats = events
            names = contacts
            error = false
        } catch (_: SQLiteException) {
            error = true
        }
    }
    val events = repeats
    val groups = remember(events) { events.orEmpty().groupBy { it.prefix } }
    val candidates = remember(groups, names) {
        groups.keys.associateWith { prefix -> names.filterKeys { it.startsWith(prefix, ignoreCase = true) }.values.toList() }
    }
    val clock = remember { channelClock() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Button(onClick = onReuse, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Nochmal senden") }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(message.text, style = MaterialTheme.typography.bodyLarge)
                Text("${clock.format(Instant.ofEpochMilli(sent.createdAt))} · ${sent.channelName}")
                Text(sent.scope)
                Text(sent.status.label)
                Text(events?.let { "${it.size} Repeats · ${groups.size} beobachtete letzte Hop-Präfixe" }
                    ?: "${sent.repeats} Repeats")
                Text("Funkbeobachtung am eigenen Companion. Empfängerzustellung unbekannt.",
                    style = MaterialTheme.typography.bodySmall)
                if (error) Text("Empfangsdetails konnten nicht gelesen werden.")
            }
            for ((prefix, rows) in groups) {
                item(key = prefix) {
                    val matches = candidates[prefix].orEmpty()
                    val label = when (matches.size) {
                        0 -> "$prefix · unbekannt"
                        1 -> "${matches.single()} · $prefix"
                        else -> "$prefix · ${matches.size} passende Kontakte"
                    }
                    Text("$label · ${rows.size} RX", style = MaterialTheme.typography.titleSmall)
                }
                items(rows, key = { it.row.sequence }) { repeat ->
                    val event = remember(repeat) {
                        CompanionEventDecoder.decode(repeat.row.frame.bytes) as? CompanionEvent.RadioRx
                    }
                    val time = clock.format(Instant.ofEpochMilli(repeat.row.frame.timeMs))
                    Text("$time · RSSI ${event?.rssiDbm ?: "—"} dBm · SNR ${event?.snrDb ?: "—"} dB",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
