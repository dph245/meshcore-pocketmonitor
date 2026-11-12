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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import de.meshcore.pocketmonitor.ble.ContactStatus
import de.meshcore.pocketmonitor.protocol.CompanionProtocol
import de.meshcore.pocketmonitor.protocol.PacketType
import de.meshcore.pocketmonitor.rx.RxRow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class LiveViewState(val list: LazyListState) {
    var snapshot by mutableStateOf<LiveSnapshot?>(null)
    var follow by mutableStateOf(true)
    var raw by mutableStateOf(false)
}

@Composable
internal fun LiveView(
    model: MonitorViewModel, contacts: ContactStatus, state: LiveViewState, modifier: Modifier = Modifier,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val list = state.list
    val snapshot = state.snapshot
    val follow = state.follow
    val raw = state.raw
    var selected by remember { mutableStateOf<SelectedRow?>(null) }

    val latestContacts by rememberUpdatedState(contacts)
    val resolver = contacts.resolverFor(snapshot?.journal?.fileName)
    LaunchedEffect(model, lifecycle, follow) {
        if (!follow && snapshot != null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var previous = state.snapshot
            while (isActive) {
                val contactStatus = latestContacts
                val updated = withContext(Dispatchers.Default) {
                    val journal = model.journalSnapshot(previous?.journal) ?: return@withContext null
                    val channels = model.channelDecoding(journal.fileName)
                    LiveSnapshot.update(journal, previous, channels, contactStatus.resolverFor(journal.fileName))
                }
                if (updated != null && updated !== previous) {
                    if (state.follow && !list.isScrollInProgress && !list.isAwayFromHead() &&
                        (updated.journal.fileName != previous?.journal?.fileName ||
                            updated.firstSequence(state.raw) != previous.firstSequence(state.raw))
                    ) {
                        list.requestScrollToItem(0)
                    }
                    previous = updated
                    state.snapshot = updated
                }
                if (!follow) break
                delay(200)
            }
        }
    }
    // A contact refresh can update names while the packet window is frozen.
    LaunchedEffect(follow, resolver) {
        val frozen = state.snapshot
        if (!follow && frozen != null && frozen.resolver !== resolver) {
            val updated = withContext(Dispatchers.Default) {
                LiveSnapshot.update(frozen.journal, frozen, frozen.channels, resolver)
            }
            if (!state.follow && state.snapshot === frozen) state.snapshot = updated
        }
    }
    LaunchedEffect(list) {
        launch {
            list.interactionSource.interactions.collect {
                if (it is DragInteraction.Start) state.follow = false
            }
        }
        snapshotFlow { list.isAwayFromHead() }.collect { away ->
            if (away) state.follow = false
        }
    }

    val current = snapshot
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (raw) "Roh-RX" else "Live-Funk", style = MaterialTheme.typography.titleMedium)
            Row {
                TextButton(onClick = {
                    state.raw = !raw
                    list.requestScrollToItem(0)
                }) { Text(if (raw) "Live" else "Rohdaten") }
                TextButton(onClick = {
                    state.follow = !follow
                    if (state.follow) list.requestScrollToItem(0)
                }) {
                    Text(if (follow) "Pause" else "Fortsetzen")
                }
            }
        }
        val count = current?.journal?.count ?: 0
        val visible = if (raw) current?.journal?.newestFirst?.size else current?.radioRows?.size
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
            val journalName = current.journal.fileName
            LiveRows(current, raw, list) { selected = SelectedRow(journalName, it) }
        }
    }
    selected?.let { selection ->
        val updatedRow = current?.takeIf { it.journal.fileName == selection.journalName }
            ?.radioRow(selection.row.original.sequence)?.row
        PacketDetails(updatedRow ?: selection.row, contacts.resolverFor(selection.journalName)) { selected = null }
    }
}

@Composable
internal fun LiveRows(
    current: LiveSnapshot, raw: Boolean, list: LazyListState, onSelect: (LiveRow) -> Unit,
) {
    if (raw) {
        LazyColumn(state = list) {
            items(current.journal.newestFirst, key = { "raw-${current.journal.fileName}-${it.sequence}" }) { row ->
                RawRow(row) {
                    val liveRow = if (row.code == CompanionProtocol.RF_LOG) {
                        current.radioRow(row.sequence)?.row
                    } else LiveRow(row, null, null)
                    liveRow?.let(onSelect)
                }
                HorizontalDivider()
            }
        }
    } else {
        RadioRows(current.journal.fileName, current.radioRows, list, onSelect)
    }
}

@Composable
private fun RadioRows(
    journalName: String, rows: List<LiveRadioRow>, list: LazyListState, onSelect: (LiveRow) -> Unit,
) {
    val dark = isSystemInDarkTheme()
    LazyColumn(state = list) {
        items(
            rows,
            key = { "rf-$journalName-${it.row.original.sequence}" },
            contentType = { it.layout },
        ) { item ->
            RadioRow(item.display, item.row.packet?.header?.type, dark) { onSelect(item.row) }
        }
    }
}

private fun LazyListState.isAwayFromHead(): Boolean =
    firstVisibleItemIndex != 0 || firstVisibleItemScrollOffset != 0

private data class SelectedRow(val journalName: String, val row: LiveRow)

private fun LiveSnapshot.firstSequence(raw: Boolean): Long? =
    if (raw) journal.newestFirst.firstOrNull()?.sequence else radioRows.firstOrNull()?.row?.original?.sequence

private val spacedHex = HexFormat {
    upperCase = true
    bytes.byteSeparator = " "
}
private val liveTime = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
private fun RadioRow(display: RadioDisplay, type: PacketType?, dark: Boolean, onClick: () -> Unit) {
    val typography = MaterialTheme.typography
    val divider = MaterialTheme.colorScheme.outlineVariant
    val announcement = remember(display) {
        AnnotatedString(listOfNotNull(
            display.time, display.type, display.rssi, display.snr, display.messageByline,
            display.messageText, display.summary, display.scope, display.hops,
        ).joinToString("\n"))
    }
    Column(
        Modifier.fillMaxWidth()
            .drawBehind {
                val thickness = 1.dp.roundToPx().toFloat()
                val y = size.height - thickness / 2
                drawLine(divider, Offset(0f, y), Offset(size.width, y), thickness)
            }
            .padding(bottom = 1.dp)
            .clickable(onClick = onClick)
            .clearAndSetSemantics { text = announcement }
            .heightIn(min = 48.dp).padding(vertical = 3.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            ListRowText(display.time, typography.labelMedium.copy(fontFamily = FontFamily.Monospace))
            ListRowText(display.type, typography.labelLarge.copy(color = typeColor(type, dark)),
                Modifier.weight(1f), maxLines = 1)
            val signalStyle = typography.labelMedium.copy(fontFamily = FontFamily.Monospace, textAlign = TextAlign.End)
            ListRowText(display.signal, signalStyle)
        }
        display.messageByline?.let { ListRowText(it, typography.labelMedium) }
        display.messageText?.let { ListRowText(it, typography.bodyMedium) }
        ListRowText(display.summary, typography.bodySmall, maxLines = 1)
        display.scope?.let { ListRowText(it, typography.bodySmall) }
        display.hops?.let {
            ListRowText(it, typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace,
            ))
        }
    }
}

@Composable
private fun RawRow(row: RxRow, onClick: () -> Unit) {
    val time = remember(row.timeMs) {
        Instant.ofEpochMilli(row.timeMs).atZone(ZoneId.systemDefault()).format(liveTime)
    }
    val hex = remember(row) { row.frame.bytes.toHexString(spacedHex) }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp).padding(vertical = 4.dp)) {
        val code = row.code?.hex(2) ?: "Leer"
        Text("#${row.sequence}  $time  ·  $code  ·  ${row.frame.bytes.size} B", style = MaterialTheme.typography.labelMedium)
        Text(hex, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun typeColor(type: PacketType?, dark: Boolean): Color {
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
