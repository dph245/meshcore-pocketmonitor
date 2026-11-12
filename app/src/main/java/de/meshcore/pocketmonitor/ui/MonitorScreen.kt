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
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val tabState = rememberSaveableStateHolder()
    val liveList = rememberLazyListState()
    val liveState = remember(liveList) { LiveViewState(liveList) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var selectedDiscoverTag by rememberSaveable { mutableStateOf<Long?>(null) }
    var showContacts by remember(contacts.journalName) { mutableStateOf(false) }
    var showConnectionControls by rememberSaveable { mutableStateOf(false) }
    val channelTabConnected = selectedTab == 2 && contacts.journalName != null
    val history = remember { mutableStateOf<ChannelHistory?>(null) }
    val channelContent by remember { derivedStateOf { history.value?.content } }
    var historyError by remember(contacts.journalName) { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle, selectedTab, contacts.journalName) {
        val journalName = contacts.journalName
        if (history.value?.catalog?.journalName != journalName) history.value = null
        if (selectedTab != 2 || journalName == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                while (isActive) {
                    model.refreshChannels()
                    delay(30_000)
                }
            }
            while (isActive) {
                val previous = history.value
                try {
                    history.value = withContext(Dispatchers.IO) { model.channelHistory(previous) }
                        ?.takeIf { it.catalog.journalName == journalName }
                    historyError = null
                } catch (_: IOException) {
                    historyError = "Kanalverlauf konnte nicht aus dem RX-Journal gelesen werden."
                }
                delay(350)
            }
        }
    }
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Scaffold { insets ->
            Column(
                Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (channelTabConnected) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(connection.message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { showConnectionControls = !showConnectionControls }) {
                            Text(if (showConnectionControls) "Einklappen" else "Verbindung")
                        }
                    }
                }
                if (!channelTabConnected || showConnectionControls) {
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
                }
                SecondaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Live-Funk") })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("DISCOVER") })
                    Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Kanäle") })
                }
                if (selectedTab == 0) {
                    LiveView(model, contacts, liveState, Modifier.weight(1f))
                } else if (selectedTab == 2) {
                    tabState.SaveableStateProvider("channels") {
                        ChannelView(
                            contacts.journalName, channelContent?.takeIf { it.catalog.journalName == contacts.journalName },
                            historyError ?: connection.channelError, Modifier.weight(1f),
                        )
                    }
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
