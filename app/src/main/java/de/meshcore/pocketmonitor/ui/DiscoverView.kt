package de.meshcore.pocketmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.meshcore.pocketmonitor.protocol.DiscoverHistory
import de.meshcore.pocketmonitor.protocol.DiscoverPhase
import de.meshcore.pocketmonitor.protocol.DiscoverReception
import de.meshcore.pocketmonitor.protocol.DiscoverRun
import de.meshcore.pocketmonitor.protocol.HopStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun DiscoverView(history: DiscoverHistory, unavailable: String?, onSend: () -> Unit, modifier: Modifier) {
    val latest = history.runs.lastOrNull()
    val list = rememberLazyListState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Direkte Repeater", style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = {
                    list.requestScrollToItem(0)
                    onSend()
                },
                enabled = unavailable == null && !history.busy && !history.coolingDown,
            ) {
                Text("DISCOVER senden")
            }
        }
        Text("RX: Antwort am Companion · TX: Anfrage am Repeater", style = MaterialTheme.typography.bodySmall)
        unavailable?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (history.coolingDown && !history.busy) Text("Sendesperre aktiv.", style = MaterialTheme.typography.bodySmall)
        if (latest == null) {
            Text("Noch kein Suchlauf.")
        } else {
            RunSummary(latest)
            HorizontalDivider()
            LazyColumn(Modifier.weight(1f), state = list) {
                for (run in history.runs.asReversed()) {
                    if (run.tag != latest.tag) {
                        item(key = "run-${run.tag}") {
                            RunSummary(run)
                            HorizontalDivider()
                        }
                    }
                    if (run.phase != DiscoverPhase.AWAITING_REPLY) {
                        itemsIndexed(run.receptions, key = { index, _ -> "${run.tag}-$index" }) { _, reception ->
                            DiscoverRow(run, reception)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RunSummary(run: DiscoverRun) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text("${run.startedAtMs.asTime()} · ${run.answerCount()}", style = MaterialTheme.typography.titleSmall)
        Text("${run.statusText()} · Tag ${run.tag.toString(16).uppercase().padStart(8, '0')}",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DiscoverRow(run: DiscoverRun, reception: DiscoverReception) {
    val response = reception.response
    val identity = remember(run.resolver, response.publicKeyOrPrefix) {
        run.resolver.resolve(response.publicKeyOrPrefix)
    }
    val prefix = identity.prefix.take(16)
    val name = when (identity.status) {
        HopStatus.UNKNOWN -> "$prefix · unbekannt"
        HopStatus.AMBIGUOUS -> "$prefix · mehrdeutig (${identity.matches.size})"
        HopStatus.UNIQUE -> identity.matches.single().name.ifBlank { prefix }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("RX ${reception.rxSnrDb.asSnr()}   TX ${response.requestSnrDb.asSnr()}",
            style = MaterialTheme.typography.bodyMedium)
        val info = buildList {
            add("RSSI ${reception.rssiDbm} dBm")
            add("${reception.receivedAtMs.asTime()} · Antwort ${String.format(Locale.GERMAN, "%.2f", reception.latencyMs / 1000.0)} s")
            if (reception.late) add("verspätet")
            if (reception.duplicate) add("Duplikat")
        }
        Text(info.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
    }
}

private fun DiscoverRun.answerCount(): String {
    val count = if (phase == DiscoverPhase.AWAITING_REPLY) 0 else receptions.size
    return if (count == 1) "1 Antwort" else "$count Antworten"
}

private fun DiscoverRun.statusText() = when (phase) {
    DiscoverPhase.AWAITING_REPLY -> "Warte auf Companion-Bestätigung"
    DiscoverPhase.LISTENING -> "Zum Senden angenommen, warte auf Antworten"
    DiscoverPhase.COMPLETE -> "Antwortfenster beendet"
    DiscoverPhase.FAILED -> "Fehlgeschlagen. $message"
    DiscoverPhase.DISCONNECTED -> "Abgebrochen. $message"
}

private val discoverTime = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun Long.asTime(): String = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(discoverTime)

private fun Double.asSnr(): String = String.format(Locale.GERMAN, "%+.2f dB", this)
