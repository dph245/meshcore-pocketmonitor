package de.meshcore.pocketmonitor.ui

import android.database.sqlite.SQLiteException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.meshcore.pocketmonitor.protocol.TraceMeasurement
import de.meshcore.pocketmonitor.protocol.TracePhase
import de.meshcore.pocketmonitor.protocol.TraceSeries
import de.meshcore.pocketmonitor.protocol.TraceSeriesProgress
import de.meshcore.pocketmonitor.protocol.TraceSeriesStatus
import de.meshcore.pocketmonitor.protocol.TraceSummary
import de.meshcore.pocketmonitor.rx.TraceHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun TraceHistoryDialog(
    store: TraceHistory,
    companion: String,
    label: (String) -> String,
    progress: TraceSeriesProgress?,
    followSeries: Boolean,
    onCancel: () -> Unit,
    onClose: () -> Unit,
) {
    var limit by remember { mutableIntStateOf(20) }
    var series by remember { mutableStateOf(emptyList<TraceSeries>()) }
    var opened by rememberSaveable { mutableStateOf<Long?>(null) }
    var measurements by remember(opened) { mutableStateOf(emptyList<TraceMeasurement>()) }
    var error by remember { mutableStateOf<String?>(null) }
    val revision by store.seriesChanges.collectAsStateWithLifecycle()
    LaunchedEffect(progress?.seriesId) {
        if (followSeries && progress?.running == true) opened = progress.seriesId
    }
    LaunchedEffect(companion, revision, limit, opened) {
        try {
            val selectedId = opened
            val pageSize = limit
            val data = withContext(Dispatchers.IO) {
                store.series(companion, Long.MAX_VALUE, pageSize + 1) to selectedId?.let(store::measurements).orEmpty()
            }
            series = data.first
            measurements = data.second
            error = null
        } catch (_: SQLiteException) {
            error = "TRACE-Messhistorie konnte nicht geladen werden."
        }
    }
    val selected = series.firstOrNull { it.id == opened }
    AlertDialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = true),
        title = { Text(if (selected == null) "TRACE-Messhistorie" else selected.name ?: "TRACE-Messreihe") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (progress?.running == true) {
                    Text(progress.message ?: if (progress.number == 0) "Lese Funkparameter …" else "Messung ${progress.number} von ${progress.count}")
                } else progress?.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (selected == null) {
                    LazyColumn(Modifier.heightIn(max = 520.dp)) {
                        items(series.take(limit), key = { it.id }) { row ->
                            TextButton(onClick = { opened = row.id }, modifier = Modifier.fillMaxWidth()) {
                                Text("${formatTraceTime(row.startedAtMs)} · ${row.name ?: "${row.route.path?.hashes?.size ?: 0} Hops"}" +
                                    "\n${row.status.label()} · ${row.radio.sf?.let { "SF$it" } ?: "SF unbekannt"}")
                            }
                        }
                        if (series.size > limit) item { TextButton(onClick = { limit += 20 }) { Text("Ältere Messreihen laden") } }
                        if (series.isEmpty() && progress?.running != true) item { Text("Noch keine Messreihen gespeichert.") }
                    }
                } else {
                    TraceSeriesResults(selected, measurements, label)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = if (opened != null) ({ opened = null }) else onClose) {
                Text(if (opened != null) "Zur Übersicht" else "Schließen")
            }
        },
        dismissButton = if (progress?.running == true) {
            { TextButton(onClick = onCancel, enabled = progress.message == null) { Text("Messreihe abbrechen") } }
        } else null,
    )
}

@Composable
private fun TraceSeriesResults(series: TraceSeries, measurements: List<TraceMeasurement>, label: (String) -> String) {
    val hashes = remember(series.route) { series.route.path?.hashes.orEmpty() }
    val summary = remember(measurements, hashes) { TraceSummary(measurements, hashes.size) }
    val radio = series.radio
    val path = remember(hashes, label) { hashes.joinToString(" → ", transform = label) }
    val hopLabels = remember(hashes, series.route, label) {
        hashes.mapIndexed { i, hash -> "${series.route.hopLabel(i)} · ${label(hash)}" } + "Empfang am Companion"
    }
    LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("${formatTraceTime(series.startedAtMs)} · ${series.status.label()}")
            series.message?.let { Text(it) }
            Text("${summary.attempts} von ${series.options.count} Versuchen · ${summary.answered} beantwortet")
            Text("${summary.timeouts} Timeouts · ${summary.errors} Sendefehler · ${summary.aborted} abgebrochen")
            Text("Erfolgsquote ${summary.rate?.let { String.format(Locale.GERMAN, "%.1f %%", it) } ?: "—"}")
        }
        item {
            Text("Funkparameter beim Start", style = MaterialTheme.typography.titleSmall)
            Text("Frequenz ${radio.frequencyKhz?.let { "$it kHz" }.known()} · Bandbreite ${radio.bandwidthHz?.let { "$it Hz" }.known()}")
            Text("SF ${radio.sf.known()} · CR ${radio.cr?.let { "4/$it" }.known()} · TX ${radio.powerDbm?.let { "$it dBm" }.known()}")
            Text("Firmware ${radio.firmware.known()} · Protokoll ${radio.protocol.known()} · Radio-IC ${radio.radioIc.known()}")
            Text("Companion ${series.companion}", style = MaterialTheme.typography.bodySmall)
        }
        if (summary.answered > 0) {
            item { Text("SNR · Minimum / Median / Maximum", style = MaterialTheme.typography.titleSmall) }
            items(summary.snr.indices.filter { summary.snr[it] != null }) { i ->
                val snr = checkNotNull(summary.snr[i])
                Text("${hopLabels[i]}\n${snr.min.db()} / ${snr.median.db()} / ${snr.max.db()}")
            }
        }
        for (result in measurements) {
            item(key = "measurement-${result.number}") {
                HorizontalDivider()
                Text("Messung ${result.number} · ${formatTraceTime(result.startedAtMs)}", style = MaterialTheme.typography.titleSmall)
                Text(result.phase.label())
                Text(if (result.accepted) "Companion hat den Sendeauftrag angenommen." else "Companion-Annahme unbestätigt.")
                Text("Antwortzeit ${result.responseMs?.let { "$it ms" } ?: "—"}")
                result.message?.let { Text(it) }
                Text(path, style = MaterialTheme.typography.bodySmall)
            }
            itemsIndexed(result.snr, key = { index, _ -> "measurement-${result.number}-hop-$index" }) { index, value ->
                hopLabels.getOrNull(index)?.let { TraceResultRow(it, value) }
            }
        }
    }
}

private val historyTime = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault())
private fun formatTraceTime(ms: Long) = historyTime.format(Instant.ofEpochMilli(ms))
private fun Double.db() = String.format(Locale.GERMAN, "%+.2f dB", this)
private fun Any?.known() = this?.toString() ?: "unbekannt"

private fun TraceSeriesStatus.label() = when (this) {
    TraceSeriesStatus.RUNNING -> "Läuft"
    TraceSeriesStatus.COMPLETED -> "Abgeschlossen"
    TraceSeriesStatus.ABORTED -> "Abgebrochen"
    TraceSeriesStatus.DISCONNECTED -> "Verbindung getrennt"
    TraceSeriesStatus.FAILED -> "Mit Fehler beendet"
}

private fun TracePhase.label() = when (this) {
    TracePhase.CONFIRMING -> "Warte auf Companion-Bestätigung"
    TracePhase.LISTENING -> "Warte auf Funkantwort"
    TracePhase.ANSWERED -> "Beantwortet"
    TracePhase.TIMED_OUT -> "Timeout"
    TracePhase.FAILED -> "Sendefehler"
    TracePhase.CANCELLED -> "Abgebrochen"
    TracePhase.DISCONNECTED -> "Abgebrochen durch Verbindungsverlust"
}
