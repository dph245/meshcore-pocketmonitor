package de.meshcore.pocketmonitor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import de.meshcore.pocketmonitor.ble.ContactStatus
import de.meshcore.pocketmonitor.protocol.CompanionProtocol
import de.meshcore.pocketmonitor.protocol.DecodeStatus
import de.meshcore.pocketmonitor.protocol.HopResolver
import de.meshcore.pocketmonitor.protocol.HopStatus
import de.meshcore.pocketmonitor.protocol.MeshPayload
import de.meshcore.pocketmonitor.protocol.PacketType
import de.meshcore.pocketmonitor.rx.RxRow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
internal fun LiveView(model: MonitorViewModel, contacts: ContactStatus, modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val list = rememberLazyListState()
    var snapshot by remember { mutableStateOf<LiveSnapshot?>(null) }
    var follow by remember { mutableStateOf(true) }
    var raw by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<SelectedRow?>(null) }

    LaunchedEffect(model, lifecycle, follow) {
        if (!follow && snapshot != null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var previous = snapshot
            while (isActive) {
                val journal = model.journalSnapshot(previous?.journal)
                if (journal != null && journal !== previous?.journal) {
                    val updated = withContext(Dispatchers.Default) { LiveSnapshot.update(journal, previous) }
                    if (follow && (updated.journal.fileName != previous?.journal?.fileName ||
                            updated.firstSequence(raw) != previous.firstSequence(raw))) {
                        // Apply the new head and the scroll position on the same remeasure.
                        list.requestScrollToItem(0)
                    }
                    previous = updated
                    snapshot = updated
                }
                if (!follow) break
                delay(200)
            }
        }
    }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) follow = false
        }
    }

    val current = snapshot
    val resolver = contacts.resolverFor(current?.journal?.fileName)
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (raw) "Roh-RX" else "Live-Funk", style = MaterialTheme.typography.titleMedium)
            Row {
                TextButton(onClick = {
                    raw = !raw
                    list.requestScrollToItem(0)
                }) { Text(if (raw) "Live" else "Rohdaten") }
                TextButton(onClick = {
                    follow = !follow
                    if (follow) list.requestScrollToItem(0)
                }) {
                    Text(if (follow) "Pause" else "Fortsetzen")
                }
            }
        }
        val count = current?.journal?.count ?: 0
        val visible = if (raw) current?.journal?.newestFirst?.size else current?.newestRadioFirst?.size
        Text(
            "${visible ?: 0} sichtbar · $count im Journal" + if (follow) "" else " · pausiert",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        current?.journal?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (current == null || current.firstSequence(raw) == null) {
            Text(
                if (raw) "Noch keine Companion-Notifications." else "Noch keine Funkpakete (0x88).",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            LazyColumn(state = list) {
                if (raw) {
                    items(current.journal.newestFirst, key = { "raw-${current.journal.fileName}-${it.sequence}" }) { row ->
                        RawRow(row) {
                            val liveRow = if (row.code == CompanionProtocol.RF_LOG)
                                current.newestRadioFirst.firstOrNull { it.original.sequence == row.sequence }
                                else LiveRow(row, null, null)
                            selected = liveRow?.let { SelectedRow(current.journal.fileName, it) }
                        }
                        HorizontalDivider()
                    }
                } else {
                    items(current.newestRadioFirst, key = { "rf-${current.journal.fileName}-${it.original.sequence}" }) { row ->
                        RadioRow(row, resolver) { selected = SelectedRow(current.journal.fileName, row) }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    selected?.let { PacketDetails(it.row, contacts.resolverFor(it.journalName)) { selected = null } }
}

private data class SelectedRow(val journalName: String, val row: LiveRow)

private fun LiveSnapshot.firstSequence(raw: Boolean): Long? =
    if (raw) journal.newestFirst.firstOrNull()?.sequence else newestRadioFirst.firstOrNull()?.original?.sequence

private val liveTime = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
private fun RadioRow(row: LiveRow, resolver: HopResolver, onClick: () -> Unit) {
    val packet = row.packet
    val type = packet?.header?.type
    val time = remember(row.original.timeMs) {
        Instant.ofEpochMilli(row.original.timeMs).atZone(ZoneId.systemDefault()).format(liveTime)
    }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp).padding(vertical = 3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(time, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
            Text(row.typeLabel, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                color = typeColor(type), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(row.event?.rssiDbm?.let { "$it dBm" } ?: "— dBm",
                style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End)
            Text(row.event?.snrDb?.let { "${it.dbText()} dB" } ?: "— dB",
                style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End)
        }
        val path = packet?.path
        val summary = buildList {
            add(row.event?.radioBytes?.size?.let { "$it B" } ?: "? B")
            if (path != null) {
                val hops = if (path.hopCount == 1) "1 Hop" else "${path.hopCount} Hops"
                add(when {
                    path.traceSnrDb != null -> "${path.hopCount}/${path.prefixes.size} TRACE-Hops"
                    packet.header?.route?.isDirect == true -> "$hops übrig"
                    else -> hops
                })
            }
            packet?.transport?.let { add("Scope ${it.scope.hex(4)}") }
            when (val payload = packet?.payload) {
                is MeshPayload.Encrypted -> if (payload.channelHash != null) {
                    add("Kanal-Hash ${payload.channelHash.hex(2)}")
                } else if (payload.recipientPrefix != null) {
                    add("${payload.senderPrefix?.hex(2) ?: payload.senderKey?.take(8) ?: "?"} → ${payload.recipientPrefix.hex(2)}")
                }
                is MeshPayload.Advert -> add(payload.name?.let { "$it (unverifiziert)" } ?: "Key ${payload.publicKey.take(8)}…")
                is MeshPayload.Discover.Response -> add("ID ${payload.publicKeyOrPrefix.take(16)}…")
                else -> Unit
            }
            when {
                row.decoderError != null || row.event?.issue != null || packet?.status == DecodeStatus.MALFORMED ->
                    add("Fehler")
                packet?.status == DecodeStatus.ENCRYPTED -> add("verschlüsselt")
                packet?.status == DecodeStatus.UNSUPPORTED -> add("unbekannt")
            }
        }.joinToString(" · ")
        Text(summary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (path != null && path.prefixes.isNotEmpty()) {
            val pathText = remember(path, resolver) {
                resolver.resolve(path).joinToString(" → ") { hop ->
                    when (hop.status) {
                        HopStatus.UNKNOWN -> hop.prefix
                        HopStatus.AMBIGUOUS -> "${hop.prefix} (${hop.matches.size}?)"
                        HopStatus.UNIQUE -> hop.matches.single().displayName.compactHopName()
                    }
                }
            }
            Text(pathText, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
        }
    }
}

private fun String.compactHopName(): String {
    val singleLine = replace('\n', ' ').replace('\r', ' ')
    val limit = 18
    return if (singleLine.codePointCount(0, singleLine.length) > limit) {
        singleLine.substring(0, singleLine.offsetByCodePoints(0, limit)) + "…"
    } else singleLine
}

@Composable
private fun RawRow(row: RxRow, onClick: () -> Unit) {
    val time = remember(row.timeMs) {
        Instant.ofEpochMilli(row.timeMs).atZone(ZoneId.systemDefault()).format(liveTime)
    }
    val hex = remember(row) { row.frame.bytes.toHexString(HexFormat.UpperCase).chunked(2).joinToString(" ") }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp).padding(vertical = 4.dp)) {
        val code = row.code?.hex(2) ?: "Leer"
        Text("#${row.sequence}  $time  ·  $code  ·  ${row.frame.bytes.size} B", style = MaterialTheme.typography.labelMedium)
        Text(hex, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun typeColor(type: PacketType?): Color {
    val dark = isSystemInDarkTheme()
    return when (type) {
        PacketType.ADVERT -> if (dark) Color(0xff83e1a3) else Color(0xff19633c)
        PacketType.GRP_TXT, PacketType.GRP_DATA -> if (dark) Color(0xff91ceff) else Color(0xff075987)
        PacketType.ACK, PacketType.MULTIPART -> if (dark) Color(0xffffce81) else Color(0xff805208)
        PacketType.TRACE -> if (dark) Color(0xffebaaff) else Color(0xff84348b)
        PacketType.CONTROL -> if (dark) Color(0xff6ce0d5) else Color(0xff006761)
        PacketType.TXT_MSG, PacketType.REQ, PacketType.RESPONSE, PacketType.ANON_REQ, PacketType.PATH ->
            if (dark) Color(0xffc5baff) else Color(0xff534298)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
