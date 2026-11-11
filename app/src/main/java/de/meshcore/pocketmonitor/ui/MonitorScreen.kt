package de.meshcore.pocketmonitor.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MonitorScreen(
    model: MonitorViewModel,
    onScan: () -> Unit,
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
) {
    val scan by model.scan.collectAsStateWithLifecycle()
    val connection by model.connectionStatus.collectAsStateWithLifecycle()
    val discover by model.discover.collectAsStateWithLifecycle()
    val contacts = connection.contacts
    val liveList = rememberLazyListState()
    val liveState = remember(liveList) { LiveViewState(liveList) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var selectedDiscoverTag by rememberSaveable { mutableStateOf<Long?>(null) }
    var showContacts by remember(contacts.journalName) { mutableStateOf(false) }
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Scaffold { insets ->
            Column(
                Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("MeshCore PocketMonitor", style = MaterialTheme.typography.titleLarge)
                Text(
                    connection.message,
                    color = if (connection.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = if (scan.scanning) model::stopScan else onScan,
                        enabled = !connection.active,
                    ) { Text(if (scan.scanning) "Scan stoppen" else "Geräte suchen") }
                    Button(
                        onClick = if (connection.active) model::disconnect else model::connect,
                        enabled = connection.active || scan.selected != null,
                    ) { Text(if (connection.active) "Trennen" else "Verbinden") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        DeviceMenu(scan, enabled = !connection.active, model = model)
                    }
                    if (contacts.journalName != null) {
                        OutlinedButton(onClick = { showContacts = true }) {
                            Text(contacts.book?.let { "Kontakte (${it.list.contacts.size})" } ?: "Kontakte")
                        }
                    }
                }
                if (contacts.journalName != null) {
                    val voltage = connection.batteryMv?.let { String.format(Locale.GERMANY, "%.3f V", it / 1000.0) } ?: "—"
                    val noiseFloor = connection.noiseFloorDbm?.let { "$it dBm" } ?: "—"
                    Text("Akku $voltage · Noise Floor $noiseFloor", style = MaterialTheme.typography.bodySmall)
                }
                scan.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Row(
                    Modifier.fillMaxWidth()
                        .toggleable(keepScreenOn, role = Role.Checkbox, onValueChange = onKeepScreenOnChange)
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = keepScreenOn, onCheckedChange = null)
                    Text("Display aktiv halten", style = MaterialTheme.typography.bodyMedium)
                }
                SecondaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Live-Funk") })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("DISCOVER") })
                }
                if (selectedTab == 0) {
                    LiveView(model, contacts, liveState, Modifier.weight(1f))
                } else {
                    DiscoverView(
                        discover, connection.discoverUnavailable, model::sendDiscover,
                        selectedTag = selectedDiscoverTag,
                        onSelect = { selectedDiscoverTag = it },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (showContacts) {
            ContactOverview(contacts.copy(
                canRefresh = contacts.canRefresh && !discover.busy && !connection.companionReadBusy,
            ),
                model::refreshContacts) { showContacts = false }
        }
    }
}

@Composable
private fun DeviceMenu(scan: ScanState, enabled: Boolean, model: MonitorViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val selected = scan.selected
    OutlinedButton(
        onClick = { expanded = true },
        enabled = enabled && selected != null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            selected?.let { "${it.name} · ${it.address}" } ?: "Kein Gerät ausgewählt",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
        for (device in scan.devices) {
            DropdownMenuItem(
                text = { Text("${device.name}\n${device.address}") },
                onClick = { model.select(device); expanded = false },
            )
        }
    }
}
