package de.meshcore.pocketmonitor.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.meshcore.pocketmonitor.protocol.RepeaterSort
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun LiveRepeaterView(model: MonitorViewModel, companion: String?, connected: Boolean, modifier: Modifier) {
    val snapshot by model.liveRepeaters.collectAsStateWithLifecycle()
    var sort by remember(model) { mutableStateOf(model.liveRepeaterSort()) }
    var expanded by remember { mutableStateOf(false) }
    val current = snapshot?.takeIf { connected && it.companion == companion }
    val rows = remember(current, sort) { current?.rows.orEmpty().sortedWith(sort.order.thenBy { it.id }) }
    val clock = remember { DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()) }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Letzte 30 Sekunden", Modifier.weight(1f))
            Column {
                TextButton(onClick = { expanded = true }) { Text(sort.label + " ▾") }
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    RepeaterSort.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, onClick = {
                            sort = option
                            model.saveLiveRepeaterSort(option)
                            expanded = false
                        })
                    }
                }
            }
        }
        if (rows.isEmpty()) {
            Text(if (connected) "Keine Repeater im Empfangsfenster erkannt." else "Companion verbinden.")
        }
        LazyColumn {
            items(rows, key = { it.id }) { row ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(row.name, style = MaterialTheme.typography.titleSmall)
                    val time = clock.format(Instant.ofEpochMilli(row.receivedAt))
                    Text("$time · ${row.rssi} dBm RSSI · ${row.snr} dB SNR · ${row.count}×",
                        style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
}
