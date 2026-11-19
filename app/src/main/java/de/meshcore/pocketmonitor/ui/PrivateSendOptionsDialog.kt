package de.meshcore.pocketmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.meshcore.pocketmonitor.ble.PrivateSender
import de.meshcore.pocketmonitor.protocol.CompanionContact
import de.meshcore.pocketmonitor.protocol.CompanionManagement
import de.meshcore.pocketmonitor.protocol.GroupSend
import de.meshcore.pocketmonitor.protocol.HopResolver
import de.meshcore.pocketmonitor.protocol.HopStatus
import de.meshcore.pocketmonitor.protocol.PrivateRetryOptions
import de.meshcore.pocketmonitor.protocol.PrivateRoute

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PrivateSendOptionsDialog(
    options: PrivateRetryOptions,
    sender: PrivateSender?,
    onSave: (PrivateRetryOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    var direct by remember { mutableIntStateOf(options.pathAttempts - 1) }
    var flood by remember { mutableIntStateOf(options.floodAttempts - 1) }
    var reset by remember { mutableStateOf(options.resetPathForFlood) }
    var scope by remember { mutableStateOf(options.floodScope) }
    var scopeMenu by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(scope !in GroupSend.scopeOptions(Int.MAX_VALUE)) }
    var manual by remember { mutableStateOf(options.manualRouting) }
    var route by remember { mutableStateOf(options.manualRoute) }
    var editRoute by remember { mutableStateOf(false) }
    val firmware = sender?.firmwareVersion
    val scopes = GroupSend.scopeOptions(firmware ?: Int.MAX_VALUE)
    val saveScope = if (custom) CompanionManagement.namedScope(scope) else scope
    val valid = (!manual || route != null) && saveScope != null

    if (editRoute) {
        PrivateRouteEditor(route, sender?.repeaters.orEmpty(), firmware, sender?.pathHashWidth ?: 1, onSave = {
            route = it
            manual = true
            editRoute = false
        }, onDismiss = { editRoute = false })
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Private Nachrichten · Versand") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RetryOptions("Direct-Retries", direct) { direct = it }
                RetryOptions("Flood-Retries", flood) { flood = it }
                Text("Zusätzliche Versuche nach dem ersten Versuch der jeweiligen Phase.",
                    style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = reset, onCheckedChange = { reset = it })
                    Text("Flood-Fallback nach den Direct-Versuchen")
                }
                if (reset) Text("Für den Flood-Fallback wird der Kontaktpfad am Companion zurückgesetzt.",
                    style = MaterialTheme.typography.bodySmall)
                Box {
                    TextButton(onClick = { scopeMenu = true }) {
                        Text("Flood-Scope · ${if (custom) "Eigener Scope" else scope} ▾")
                    }
                    DropdownMenu(scopeMenu, onDismissRequest = { scopeMenu = false }) {
                        scopes.forEach { option ->
                            DropdownMenuItem(text = { Text(option) }, onClick = {
                                scope = option
                                custom = false
                                scopeMenu = false
                            })
                        }
                        if ((firmware ?: Int.MAX_VALUE) >= GroupSend.SCOPE_MIN_VERSION) {
                            DropdownMenuItem(text = { Text("Eigener Scope") }, onClick = {
                                if (!custom) scope = ""
                                custom = true
                                scopeMenu = false
                            })
                        }
                    }
                }
                if (custom) {
                    OutlinedTextField(scope, { scope = it }, label = { Text("Eigener Flood-Scope") },
                        placeholder = { Text("#de-ni") }, singleLine = true,
                        isError = scope.isNotEmpty() && saveScope == null, modifier = Modifier.fillMaxWidth())
                }
                HorizontalDivider()
                Text("Routing", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !manual, onClick = { manual = false }, label = { Text("AUTO") })
                    FilterChip(selected = manual, onClick = {
                        if (route == null) editRoute = true else manual = true
                    }, label = { Text("MANUELL") })
                }
                if (manual) route?.let { Text(it.hops.joinToString(" → ")) }
                if (!manual && route != null) {
                    Text("AUTO übernimmt den gelernten Pfad des Companions. Ein zuvor von PocketMonitor gesetzter Pfad wird beim nächsten Versand zurückgesetzt.",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (manual || route != null) {
                    TextButton(onClick = { editRoute = true }) { Text("Pfad bearbeiten") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(PrivateRetryOptions(direct + 1, flood + 1, reset, checkNotNull(saveScope), manual, route))
            }, enabled = valid) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RetryOptions(label: String, selected: Int, select: (Int) -> Unit) {
    Text(label, style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(PrivateRetryOptions.MAX_ATTEMPTS) { count ->
            FilterChip(selected = count == selected, onClick = { select(count) }, label = { Text(count.toString()) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrivateRouteEditor(
    route: PrivateRoute?,
    repeaters: List<CompanionContact>,
    firmware: Int?,
    defaultWidth: Int,
    onSave: (PrivateRoute) -> Unit,
    onDismiss: () -> Unit,
) {
    var width by remember { mutableIntStateOf(route?.width ?: defaultWidth) }
    var hops by remember { mutableStateOf(route?.hops.orEmpty()) }
    var query by remember { mutableStateOf("") }
    val resolver = remember(repeaters) { HopResolver(repeaters) }
    val maxWidth = if (firmware != null && firmware < PrivateRoute.MULTIBYTE_MIN_VERSION) 1 else 3
    val limit = PrivateRoute.maxHops(width)
    val hash = PrivateRoute.normalizeHop(query, width)
    val found = remember(repeaters, query) {
        val term = query.trim()
        repeaters.asSequence()
            .filter { it.displayName.contains(term, ignoreCase = true) || it.publicKey.startsWith(term, ignoreCase = true) }
            .sortedBy { it.displayName.lowercase() }.take(8).toList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manueller Direct-Pfad") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Hash-Länge · ${hops.size}/$limit Hops", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (bytes in 1..maxWidth) {
                        FilterChip(selected = bytes == width, enabled = hops.isEmpty() || bytes == width,
                            onClick = { width = bytes }, label = { Text("$bytes B") })
                    }
                }
                if (width > maxWidth) Text("Dieser Companion unterstützt nur 1-Byte-Pfade.")
                hops.forEachIndexed { index, hop ->
                    val resolved = resolver.resolve(hop)
                    val label = when (resolved.status) {
                        HopStatus.UNIQUE -> " · ${resolved.matches.single().displayName}"
                        HopStatus.AMBIGUOUS -> " · mehrdeutig"
                        HopStatus.UNKNOWN -> ""
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}. $hop$label", Modifier.weight(1f))
                        TextButton(onClick = {
                            hops = hops.toMutableList().apply { add(index - 1, removeAt(index)) }
                        }, enabled = index > 0) { Text("↑") }
                        TextButton(onClick = { hops = hops.filterIndexed { i, _ -> i != index } }) { Text("Entfernen") }
                    }
                }
                OutlinedTextField(
                    query, { query = it }, label = { Text("Repeater suchen oder Hash eingeben") },
                    supportingText = { Text("${width * 2} Hex-Zeichen pro Hop") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                if (hash != null) {
                    TextButton(enabled = hops.size < limit, onClick = {
                        hops = hops + hash
                        query = ""
                    }) { Text("$hash hinzufügen") }
                }
                found.forEach { repeater ->
                    val prefix = repeater.publicKey.take(width * 2)
                    TextButton(enabled = hops.size < limit, onClick = {
                        hops = hops + prefix
                        query = ""
                    }) { Text("${repeater.displayName} · $prefix") }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = hops.isNotEmpty() && width <= maxWidth, onClick = {
                PrivateRoute.create(width, hops)?.let(onSave)
            }) { Text("Pfad übernehmen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
