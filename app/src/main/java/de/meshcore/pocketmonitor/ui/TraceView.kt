package de.meshcore.pocketmonitor.ui

import android.database.sqlite.SQLiteException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.meshcore.pocketmonitor.ble.ContactBook
import de.meshcore.pocketmonitor.ble.TraceSender
import de.meshcore.pocketmonitor.protocol.CompanionContact
import de.meshcore.pocketmonitor.protocol.CompanionProtocol
import de.meshcore.pocketmonitor.protocol.HopResolver
import de.meshcore.pocketmonitor.protocol.TracePath
import de.meshcore.pocketmonitor.protocol.TracePhase
import de.meshcore.pocketmonitor.protocol.TraceRun
import de.meshcore.pocketmonitor.protocol.TracePreset
import de.meshcore.pocketmonitor.protocol.TraceRoute
import de.meshcore.pocketmonitor.protocol.TraceSeriesOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TraceView(
    model: MonitorViewModel,
    companion: String?,
    sender: TraceSender?,
    contacts: ContactBook?,
    modifier: Modifier,
) {
    var width by rememberSaveable { mutableIntStateOf(1) }
    var mirror by rememberSaveable { mutableStateOf(false) }
    var turn by rememberSaveable { mutableStateOf("") }
    var draft by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var manual by rememberSaveable { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var selectedTag by rememberSaveable { mutableStateOf<Long?>(null) }
    val repeaters = remember(contacts) {
        contacts?.list?.contacts.orEmpty().filter { it.type == CompanionContact.TYPE_REPEATER }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
    }
    val manualRuns = remember(model, companion) {
        model.traces.map { runs -> runs.filter { it.responseTimeoutMs == null && it.companion == companion } }
            .distinctUntilChanged()
    }
    val runs by manualRuns.collectAsStateWithLifecycle(emptyList())
    val progress by model.traceSeries.collectAsStateWithLifecycle()
    val progressHere = progress?.takeIf { it.companion == companion }
    val seriesBusy = progress?.running == true
    val changes by model.traceHistory.presetChanges.collectAsStateWithLifecycle()
    var presets by remember { mutableStateOf(emptyList<TracePreset>()) }
    var cachedNames by remember { mutableStateOf(emptyMap<String, String>()) }
    var presetId by rememberSaveable { mutableStateOf<Long?>(null) }
    var presetMenu by remember { mutableStateOf(false) }
    var hopMenu by remember { mutableStateOf<Int?>(null) }
    var prompt by remember { mutableStateOf<PresetAction?>(null) }
    var presetName by remember { mutableStateOf("") }
    var storeError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var seriesOptions by rememberSaveable { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var followSeries by rememberSaveable { mutableStateOf(false) }
    var count by rememberSaveable { mutableIntStateOf(1) }
    var interval by rememberSaveable { mutableStateOf("30") }
    var responseTimeout by rememberSaveable { mutableStateOf("30") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(companion, contacts) {
        if (companion != null && contacts == null) {
            try {
                cachedNames = withContext(Dispatchers.IO) { model.groupRepeaterNames(companion) }
            } catch (_: SQLiteException) {
                storeError = "Repeater-Namen konnten nicht geladen werden."
            }
        }
    }
    LaunchedEffect(companion, changes) {
        if (companion != null) {
            try {
                presets = withContext(Dispatchers.IO) { model.traceHistory.presets(companion) }
            } catch (_: SQLiteException) {
                storeError = "TRACE-Presets konnten nicht geladen werden."
            }
        }
    }
    val preset = presets.firstOrNull { it.id == presetId }
    val label: (String) -> String = remember(contacts, cachedNames) {
        val live = contacts?.resolver
        val cachedPrefixes = listOf(2, 4, 8).flatMap { length ->
            cachedNames.entries.groupBy({ it.key.take(length) }, { it.value }).entries
        }.associate { it.key to it.value }
        val resolve: (String) -> String = { hash ->
            if (live != null) live.traceLabel(hash) else traceLabel(hash, cachedPrefixes[hash].orEmpty())
        }
        resolve
    }
    val turnIndex = turn.toIntOrNull()?.takeIf { it in 1..draft.size }
    val turnValid = mirror || turn.isEmpty() || turnIndex != null
    val route = remember(draft, width, mirror, turnIndex) { TraceRoute(width, draft.toList(), mirror, turnIndex.takeUnless { mirror }) }
    val measurementOptions = interval.toIntOrNull()?.takeIf { it in 1..3600 }?.let { gap ->
        responseTimeout.toIntOrNull()?.takeIf { it in 1..3600 }?.let { TraceSeriesOptions(count, gap, it) }
    }

    fun loadPreset(value: TracePreset) {
        presetId = value.id
        width = value.route.width
        mirror = value.route.mirror
        turn = value.route.turn?.toString().orEmpty()
        draft = ArrayList(value.route.entries)
        manual = ""
    }

    fun editPreset(action: PresetAction) {
        presetName = when (action) {
            PresetAction.DUPLICATE -> preset?.name?.take(72).orEmpty() + " (Kopie)"
            else -> preset?.name.orEmpty().takeIf { action != PresetAction.NEW }.orEmpty()
        }
        prompt = action
    }

    fun savePreset(action: PresetAction) {
        val identity = companion ?: return
        val name = presetName.trim()
        scope.launch {
            saving = true
            storeError = null
            try {
                val id = withContext(Dispatchers.IO) {
                    when (action) {
                        PresetAction.NEW -> model.traceHistory.savePreset(identity, null, name, route)
                        PresetAction.UPDATE -> model.traceHistory.savePreset(identity, checkNotNull(preset).id, preset.name, route)
                        PresetAction.RENAME -> model.traceHistory.savePreset(identity, checkNotNull(preset).id, name, preset.route)
                        PresetAction.DUPLICATE -> model.traceHistory.savePreset(identity, null, name, checkNotNull(preset).route)
                        PresetAction.DELETE -> {
                            model.traceHistory.deletePreset(identity, checkNotNull(preset).id)
                            null
                        }
                    }
                }
                if (action == PresetAction.DUPLICATE && preset != null && id != null) {
                    loadPreset(preset.copy(id = id, name = name))
                } else {
                    presetId = id
                }
                prompt = null
            } catch (_: SQLiteException) {
                storeError = "TRACE-Preset konnte nicht gespeichert werden."
                prompt = null
            } catch (_: IllegalStateException) {
                storeError = "TRACE-Preset ist nicht mehr vorhanden."
                prompt = null
            } finally {
                saving = false
            }
        }
    }
    // Contact entries store the full key so they can be reused when changing the hash width.
    val hashes = remember(route) { route.prefixes }
    val path = remember(route) { route.path }
    val maxHops = TracePath.maxHops(width, sender?.mtu ?: CompanionProtocol.FULL_FRAME_MTU)
    val hopCount = if (mirror && hashes.isNotEmpty()) hashes.size * 2 - 1 else hashes.size
    val nextHopCount = if (mirror) hashes.size * 2 + 1 else hashes.size + 1
    val manualHash = TracePath.hash(manual, width)
    val unavailable = sender?.unavailable ?: if (sender == null) "Zuerst einen Companion verbinden." else null
    val latest = runs.lastOrNull()
    val selected = runs.lastOrNull { it.tag.toLong() == selectedTag } ?: latest
    val canSend = unavailable == null && !seriesBusy && latest?.busy != true && path != null && hopCount <= maxHops

    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column {
                    OutlinedButton(onClick = { presetMenu = true }, enabled = companion != null && !saving) {
                        Text(preset?.name ?: "Strecken-Presets")
                    }
                    DropdownMenu(presetMenu, { presetMenu = false }) {
                        presets.forEach { value ->
                            DropdownMenuItem(text = { Text(value.name) }, onClick = {
                                loadPreset(value)
                                presetMenu = false
                            })
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Als neues Preset speichern") },
                            onClick = { presetMenu = false; editPreset(PresetAction.NEW) },
                            enabled = path != null && turnValid,
                        )
                        if (preset != null) PresetAction.entries.drop(1).forEach { action ->
                            DropdownMenuItem(text = { Text(action.title) }, onClick = {
                                presetMenu = false
                                if (action == PresetAction.UPDATE) savePreset(action) else editPreset(action)
                            }, enabled = action != PresetAction.UPDATE || (path != null && turnValid && route != preset.route))
                        }
                    }
                }
                OutlinedButton(onClick = { followSeries = false; historyOpen = true }, enabled = companion != null) { Text("Messhistorie") }
            }
            storeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (progressHere != null) {
                if (progressHere.running) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(progressHere.message ?: if (progressHere.number == 0) "Lese Funkparameter …"
                            else "Messung ${progressHere.number} von ${progressHere.count}", Modifier.weight(1f))
                        TextButton(onClick = model::cancelTraceSeries, enabled = progressHere.message == null) { Text("Abbrechen") }
                    }
                } else progressHere.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Text("Hash-Länge pro Hop", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TracePath.widths.forEach { bytes ->
                    FilterChip(selected = width == bytes, onClick = { width = bytes }, label = { Text("$bytes Byte") })
                }
            }
        }
        itemsIndexed(hashes) { index, hash ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val label = if (TracePath.hash(hash, width) == null) "$hash · falsche Hash-Länge" else label(hash)
                Text("${index + 1}. $label", modifier = Modifier.weight(1f))
                Column {
                    TextButton(onClick = { hopMenu = index }) { Text("Hop bearbeiten") }
                    DropdownMenu(hopMenu == index, { hopMenu = null }) {
                        DropdownMenuItem(text = { Text("Nach oben") }, enabled = index > 0, onClick = {
                            draft = ArrayList(draft).apply { add(index - 1, removeAt(index)) }
                            hopMenu = null
                        })
                        DropdownMenuItem(text = { Text("Nach unten") }, enabled = index < draft.lastIndex, onClick = {
                            draft = ArrayList(draft).apply { add(index + 1, removeAt(index)) }
                            hopMenu = null
                        })
                        DropdownMenuItem(text = { Text("Entfernen") }, onClick = {
                            draft = ArrayList(draft).apply { removeAt(index) }
                            if (turn.toIntOrNull()?.let { it > draft.size } == true) turn = ""
                            hopMenu = null
                        })
                    }
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
            if (!mirror && draft.isNotEmpty()) {
                OutlinedTextField(
                    turn, { turn = it }, label = { Text("Wendepunkt (Hop, optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !turnValid, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            if (hopCount > maxHops) {
                Text("Dieser TRACE-Pfad ist zu lang (maximal $maxHops Hops).", color = MaterialTheme.colorScheme.error)
            }
            if (path != null) {
                Text("Pfad · $hopCount Hops", style = MaterialTheme.typography.titleSmall)
                Text(path.hashes.joinToString(" → ") { label(it) }, style = MaterialTheme.typography.bodySmall)
            }
            unavailable?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (sender != null && path != null) {
                        selectedTag = null
                        model.sendTrace(sender.session, path)
                    }
                }, enabled = canSend) { Text("TRACE senden") }
                OutlinedButton(onClick = { seriesOptions = !seriesOptions }) {
                    Text(if (seriesOptions) "Messreihe ausblenden" else "Messreihe")
                }
            }
            if (seriesOptions) {
                Text("Anzahl der Messungen", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TraceSeriesOptions.counts.forEach { value ->
                        FilterChip(selected = count == value, onClick = { count = value }, label = { Text("$value×") })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TraceSecondsField(interval, { interval = it }, "Mindestabstand (s)", Modifier.weight(1f))
                    TraceSecondsField(responseTimeout, { responseTimeout = it }, "Antwort-Timeout (s)", Modifier.weight(1f))
                }
                Button(onClick = {
                    if (sender != null && measurementOptions != null) {
                        model.startTraceSeries(sender.session, route, preset?.name?.takeIf { route == preset.route }, measurementOptions)
                        followSeries = true
                        historyOpen = true
                    }
                }, enabled = canSend && turnValid && measurementOptions != null) { Text("Messreihe starten") }
            }
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

    if (historyOpen && companion != null) {
        TraceHistoryDialog(model.traceHistory, companion, label, progressHere, followSeries, model::cancelTraceSeries) {
            historyOpen = false
        }
    }
    prompt?.let { action ->
        AlertDialog(
            onDismissRequest = { if (!saving) prompt = null },
            title = { Text(action.title) },
            text = {
                if (action == PresetAction.DELETE) Text("„${preset?.name}“ löschen?")
                else OutlinedTextField(presetName, { presetName = it.take(80) }, label = { Text("Preset-Name") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = { savePreset(action) },
                    enabled = !saving && (action == PresetAction.DELETE || presetName.isNotBlank())) {
                    Text(if (action == PresetAction.DELETE) "Löschen" else "Speichern")
                }
            },
            dismissButton = { TextButton(onClick = { prompt = null }, enabled = !saving) { Text("Abbrechen") } },
        )
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
internal fun TraceResultRow(label: String, snrDb: Double?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        snrDb?.let { Text(String.format(Locale.GERMAN, "%+.2f dB", it)) }
    }
}

private fun HopResolver.traceLabel(hash: String): String = traceLabel(hash, resolve(hash).matches.map { it.displayName })

private fun traceLabel(hash: String, names: List<String>): String = when (names.size) {
    0 -> hash
    1 -> "${names.single()} · $hash"
    else -> "$hash · mehrdeutig (${names.size})"
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
    TracePhase.CANCELLED -> "TRACE abgebrochen."
    TracePhase.DISCONNECTED -> "TRACE durch Verbindungsabbruch unterbrochen."
}


private enum class PresetAction(val title: String) {
    NEW("Neues Preset"), UPDATE("Pfadänderungen speichern"), RENAME("Preset umbenennen"),
    DUPLICATE("Preset duplizieren"), DELETE("Preset löschen"),
}

@Composable
private fun TraceSecondsField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier) {
    OutlinedTextField(value, { if (it.length <= 4 && it.all(Char::isDigit)) onChange(it) }, modifier = modifier,
        label = { Text(label) }, singleLine = true, isError = value.toIntOrNull() !in 1..3600,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}
