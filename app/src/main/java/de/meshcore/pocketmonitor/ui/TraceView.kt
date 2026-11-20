package de.meshcore.pocketmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import de.meshcore.pocketmonitor.ble.ContactBook
import de.meshcore.pocketmonitor.ble.TraceSender
import de.meshcore.pocketmonitor.protocol.CompanionContact
import de.meshcore.pocketmonitor.protocol.CompanionProtocol
import de.meshcore.pocketmonitor.protocol.HopResolver
import de.meshcore.pocketmonitor.protocol.HopStatus
import de.meshcore.pocketmonitor.protocol.TracePath
import de.meshcore.pocketmonitor.protocol.TracePhase
import de.meshcore.pocketmonitor.protocol.TraceRun
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun TraceView(
    runs: List<TraceRun>,
    sender: TraceSender?,
    contacts: ContactBook?,
    onSend: (String, TracePath) -> Unit,
    modifier: Modifier,
) {
    var width by rememberSaveable { mutableIntStateOf(1) }
    var mirror by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var manual by rememberSaveable { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var selectedTag by rememberSaveable { mutableStateOf<Long?>(null) }
    val repeaters = remember(contacts) {
        contacts?.list?.contacts.orEmpty().filter { it.type == CompanionContact.TYPE_REPEATER }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
    }
    val resolver = contacts?.resolver ?: HopResolver.EMPTY
    // Contact entries store the full key so they can be reused when changing the hash width.
    val hashes = remember(draft, width) { draft.map { if (it.length == 64) it.take(width * 2) else it } }
    val path = remember(hashes, width, mirror) { TracePath.create(width, hashes, mirror) }
    val maxHops = TracePath.maxHops(width, sender?.mtu ?: CompanionProtocol.FULL_FRAME_MTU)
    val hopCount = if (mirror && hashes.isNotEmpty()) hashes.size * 2 - 1 else hashes.size
    val nextHopCount = if (mirror) hashes.size * 2 + 1 else hashes.size + 1
    val manualHash = TracePath.hash(manual, width)
    val unavailable = sender?.unavailable ?: if (sender == null) "Zuerst einen Companion verbinden." else null
    val latest = runs.lastOrNull()
    val selected = runs.lastOrNull { it.tag.toLong() == selectedTag } ?: latest
    val canSend = unavailable == null && latest?.busy != true && path != null && hopCount <= maxHops

    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("Hash-Länge pro Hop", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TracePath.widths.forEach { bytes ->
                    FilterChip(selected = width == bytes, onClick = { width = bytes }, label = { Text("$bytes Byte") })
                }
            }
        }
        itemsIndexed(hashes) { index, hash ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val label = if (TracePath.hash(hash, width) == null) "$hash · falsche Hash-Länge" else resolver.traceLabel(hash)
                Text("${index + 1}. $label", modifier = Modifier.weight(1f))
                TextButton(onClick = { draft = ArrayList(draft).apply { removeAt(index) } }) {
                    Text("Entfernen")
                }
            }
        }
        item {
            OutlinedButton(onClick = { picking = true }, enabled = repeaters.isNotEmpty() && nextHopCount <= maxHops) {
                Text("Repeater hinzufügen")
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = manual,
                    onValueChange = { manual = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Hash (${width * 2} Hex-Zeichen)") },
                    singleLine = true,
                    isError = manual.isNotBlank() && manualHash == null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
                OutlinedButton(
                    onClick = {
                        manualHash?.let { draft = ArrayList(draft).apply { add(it) } }
                        manual = ""
                    },
                    enabled = manualHash != null && nextHopCount <= maxHops,
                ) { Text("Hinzufügen") }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().toggleable(mirror, role = Role.Switch, onValueChange = { mirror = it }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Rückweg spiegeln", modifier = Modifier.weight(1f))
                Switch(checked = mirror, onCheckedChange = null)
            }
            if (hopCount > maxHops) {
                Text("Dieser TRACE-Pfad ist zu lang (maximal $maxHops Hops).", color = MaterialTheme.colorScheme.error)
            }
            if (path != null) {
                Text("Pfad · $hopCount Hops", style = MaterialTheme.typography.titleSmall)
                Text(path.hashes.joinToString(" → "), style = MaterialTheme.typography.bodySmall)
            }
            unavailable?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = {
                if (sender != null && path != null) {
                    selectedTag = null
                    onSend(sender.session, path)
                }
            }, enabled = canSend) { Text("TRACE senden") }
        }
        if (selected != null) {
            item {
                HorizontalDivider()
                TraceRunPicker(runs, selected) { selectedTag = it.tag.toLong() }
                Text(selected.statusText(), style = MaterialTheme.typography.bodyMedium)
                selected.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            itemsIndexed(selected.path.hashes) { index, hash ->
                val snr = selected.response?.hopSnrDb?.get(index)
                TraceResultRow("${index + 1}. ${selected.resolver.traceLabel(hash)}", snr)
            }
            selected.response?.let { response ->
                item { TraceResultRow("Empfang am Companion", response.finalSnrDb) }
            }
        }
    }

    if (picking) {
        RepeaterPicker(repeaters, width, onDismiss = { picking = false }) { contact ->
            if (nextHopCount <= maxHops) draft = ArrayList(draft).apply { add(contact.publicKey) }
            picking = false
        }
    }
}

@Composable
private fun RepeaterPicker(
    repeaters: List<CompanionContact>,
    width: Int,
    onDismiss: () -> Unit,
    onPick: (CompanionContact) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    val matches = remember(repeaters, search) {
        repeaters.filter { it.displayName.contains(search, true) || it.publicKey.startsWith(search.trim(), true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Repeater auswählen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(search, { search = it }, label = { Text("Name oder Hash") }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(matches) { contact ->
                        DropdownMenuItem(
                            text = { Text("${contact.displayName} · ${contact.publicKey.take(width * 2)}") },
                            onClick = { onPick(contact) },
                        )
                    }
                    if (matches.isEmpty()) item { Text("Keine passenden Repeater.") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
}

@Composable
private fun TraceRunPicker(runs: List<TraceRun>, selected: TraceRun, onSelect: (TraceRun) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { expanded = true }) { Text(selected.label()) }
        DropdownMenu(expanded, { expanded = false }) {
            for (run in runs.asReversed()) {
                DropdownMenuItem(
                    text = { Text(run.label()) },
                    onClick = {
                        expanded = false
                        onSelect(run)
                    },
                )
            }
        }
    }
}

@Composable
private fun TraceResultRow(label: String, snrDb: Double?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        snrDb?.let { Text(String.format(Locale.GERMAN, "%+.2f dB", it)) }
    }
}

private fun HopResolver.traceLabel(hash: String): String {
    val hop = resolve(hash)
    return when (hop.status) {
        HopStatus.UNIQUE -> "${hop.matches.single().displayName} · $hash"
        HopStatus.UNKNOWN -> hash
        HopStatus.AMBIGUOUS -> "$hash · mehrdeutig (${hop.matches.size})"
    }
}

private val traceTime = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

private fun TraceRun.label() =
    "${traceTime.format(Instant.ofEpochMilli(startedAtMs))} · ${path.hashes.size} Hops · ${path.hashBytes} Byte" +
        " · ${tag.toString(16).uppercase().padStart(8, '0')}"

private fun TraceRun.statusText(): String = when (phase) {
    TracePhase.CONFIRMING -> "Warte auf die Companion-Bestätigung."
    TracePhase.LISTENING -> "Zum Senden angenommen. Warte auf die Funkantwort."
    TracePhase.ANSWERED -> if (late) "Funkantwort verspätet empfangen." else "Funkantwort empfangen."
    TracePhase.TIMED_OUT -> "Keine Funkantwort im erwarteten Zeitfenster."
    TracePhase.FAILED -> "TRACE fehlgeschlagen."
    TracePhase.DISCONNECTED -> "TRACE durch Verbindungsabbruch unterbrochen."
}
