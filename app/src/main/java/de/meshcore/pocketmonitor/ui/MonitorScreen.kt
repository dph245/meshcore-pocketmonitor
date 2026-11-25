package de.meshcore.pocketmonitor.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import de.meshcore.pocketmonitor.R
import de.meshcore.pocketmonitor.rx.PRIVATE_ID_PREFIX
import de.meshcore.pocketmonitor.tcp.TcpEndpoint
import android.database.sqlite.SQLiteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun MonitorScreen(
    model: MonitorViewModel,
    onScan: () -> Unit,
    onConnect: () -> Unit,
    onTcpConnect: (TcpEndpoint) -> Unit,
    openMessagesRequest: Int,
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
) {
    val scan by model.scan.collectAsStateWithLifecycle()
    val connection by model.connectionStatus.collectAsStateWithLifecycle()
    val discover by model.discover.collectAsStateWithLifecycle()
    val traces by model.traces.collectAsStateWithLifecycle()
    val contacts = connection.contacts
    val companions by model.companions.collectAsStateWithLifecycle()
    val companion by model.selectedCompanion.collectAsStateWithLifecycle()
    val historyReady by model.historyReady.collectAsStateWithLifecycle()
    val historyError by model.historyError.collectAsStateWithLifecycle()
    val tabState = rememberSaveableStateHolder()
    val liveList = rememberLazyListState()
    val liveState = remember(liveList) { LiveViewState(liveList) }
    var selectedTab by rememberSaveable { mutableIntStateOf(3) }
    var handledMessagesRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(openMessagesRequest) {
        if (openMessagesRequest != handledMessagesRequest) {
            selectedTab = 2
            handledMessagesRequest = openMessagesRequest
        }
    }
    var useTcp by rememberSaveable { mutableStateOf(connection.tcp) }
    // Follow the transport of a session that survived activity recreation.
    val showTcp = if (connection.active) connection.tcp else useTcp
    LaunchedEffect(connection.active, connection.tcp) {
        if (connection.active) useTcp = connection.tcp
    }
    var liveTab by rememberSaveable { mutableIntStateOf(0) }
    var messageTab by rememberSaveable { mutableIntStateOf(0) }
    var discoverTab by rememberSaveable { mutableIntStateOf(0) }
    var selectedDiscoverTag by rememberSaveable { mutableStateOf<Long?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var unread by remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    LaunchedEffect(model, lifecycle, historyReady) {
        if (!historyReady) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var revision = -1L
            while (isActive) {
                val current = model.historyRevision
                if (revision != current) {
                    try {
                        unread = withContext(Dispatchers.IO) { model.unreadConversations() }
                        revision = current
                    } catch (_: SQLiteException) {
                        // Keep the last snapshot and retry on the next poll.
                    }
                }
                delay(350)
            }
        }
    }
    val unreadHere = unread[companion].orEmpty()
    val editingMessage = selectedTab == 2 && WindowInsets.isImeVisible
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Scaffold { insets ->
            Column(
                Modifier.fillMaxSize()
                    .padding(insets)
                    .consumeWindowInsets(insets)
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!editingMessage) {
                    IconTabRow(
                        selectedTab, { selectedTab = it },
                        listOf(
                            MonitorTab("Live-Funk", R.drawable.ic_tab_radio),
                            MonitorTab("DISCOVER", R.drawable.ic_tab_discover),
                            MonitorTab("Nachrichten", R.drawable.ic_tab_messages, unreadHere.isNotEmpty()),
                            MonitorTab("Verbindung", R.drawable.ic_tab_bluetooth),
                        ),
                        Modifier.align(Alignment.CenterHorizontally),
                    )
                }
                if (selectedTab == 3) {
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("MeshCore PocketMonitor", style = MaterialTheme.typography.titleLarge)
                        Text(
                            connection.message,
                            color = if (connection.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                        SecondaryTabRow(selectedTabIndex = if (showTcp) 1 else 0) {
                            Tab(
                                selected = !showTcp,
                                onClick = { useTcp = false },
                                enabled = !connection.active,
                                text = { Text("Bluetooth") },
                                icon = { Icon(painterResource(R.drawable.ic_tab_bluetooth), null) },
                            )
                            Tab(
                                selected = showTcp,
                                onClick = { useTcp = true },
                                enabled = !connection.active,
                                text = { Text("TCP") },
                                icon = { Icon(painterResource(R.drawable.ic_tab_network), null) },
                            )
                        }
                        if (showTcp) {
                            val autoReconnect by model.tcpAutoReconnect.collectAsStateWithLifecycle()
                            val tcpError by model.tcpError.collectAsStateWithLifecycle()
                            tabState.SaveableStateProvider("tcpConnection") {
                                TcpConnectionForm(
                                    savedHost = model.tcpHost,
                                    savedPort = model.tcpPort,
                                    active = connection.active,
                                    ready = historyReady,
                                    autoReconnect = autoReconnect,
                                    onAutoReconnect = model::setTcpAutoReconnect,
                                    onConnect = onTcpConnect,
                                    onDisconnect = model::disconnect,
                                )
                            }
                            tcpError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = if (scan.scanning) model::stopScan else onScan,
                                    enabled = !connection.active,
                                ) { Text(if (scan.scanning) "Scan stoppen" else "Geräte suchen") }
                                Button(
                                    onClick = if (connection.active) model::disconnect else onConnect,
                                    enabled = connection.active || (historyReady && scan.selected != null),
                                ) { Text(if (connection.active) "Trennen" else "Verbinden") }
                            }
                            DeviceMenu(scan, enabled = !connection.active, model = model)
                            scan.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        if (contacts.journalName != null) {
                            val voltage = connection.batteryMv?.let { String.format(Locale.GERMANY, "%.3f V", it / 1000.0) } ?: "—"
                            val noiseFloor = connection.noiseFloorDbm?.let { "$it dBm" } ?: "—"
                            Text("Akku $voltage · Noise Floor $noiseFloor", style = MaterialTheme.typography.bodySmall)
                            val uptime = connection.uptimeSeconds?.let(::formatUptime) ?: "—"
                            Text("Uptime $uptime", style = MaterialTheme.typography.bodySmall)
                        }
                        connection.management?.let { management ->
                            CompanionControls(management, contacts, model)
                        }
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
                }
                if (!editingMessage && !connection.active && companions.isNotEmpty()) {
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { expanded = true }) {
                            val selected = companions.firstOrNull { it.id == companion }
                            UnreadLabel(selected?.let { "Historie: ${it.name} · ${it.id.take(8)}" } ?: "Historie auswählen",
                                unread.isNotEmpty())
                        }
                        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                            for (known in companions) {
                                DropdownMenuItem(
                                    text = { UnreadLabel("${known.name} · ${known.id.take(8)}", known.id in unread) },
                                    onClick = { model.selectHistory(known.id); expanded = false },
                                )
                            }
                        }
                    }
                }
                historyError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (selectedTab == 0) {
                    IconTabRow(
                        liveTab, { liveTab = it },
                        listOf(
                            MonitorTab("Empfang", R.drawable.ic_tab_radio),
                            MonitorTab("Repeater", R.drawable.ic_tab_repeater),
                        ),
                        Modifier.align(Alignment.CenterHorizontally),
                    )
                    if (liveTab == 0) {
                        tabState.SaveableStateProvider("live-rx") {
                            LiveView(model, companion, contacts, liveState, Modifier.weight(1f))
                        }
                    } else {
                        LiveRepeaterView(model, companion, connection.active && contacts.journalName == companion,
                            Modifier.weight(1f))
                    }
                } else if (selectedTab == 2) {
                    val syncStatus = "Nachrichten werden abgeglichen …".takeIf { connection.syncingMessages }
                    IconTabRow(
                        messageTab, { messageTab = it },
                        listOf(
                            MonitorTab("Kanäle", R.drawable.ic_tab_channels,
                                unreadHere.any { !it.startsWith(PRIVATE_ID_PREFIX) },
                                progressDescription = syncStatus.takeIf { messageTab == 0 }),
                            MonitorTab("Privat", R.drawable.ic_tab_private,
                                unreadHere.any { it.startsWith(PRIVATE_ID_PREFIX) },
                                progressDescription = syncStatus.takeIf { messageTab == 1 }),
                        ),
                        Modifier.align(Alignment.CenterHorizontally),
                    )
                    connection.messageSyncError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    tabState.SaveableStateProvider(if (messageTab == 0) "channels" else "private") {
                        ChannelView(model, companion, connection.channelError.takeIf { messageTab == 0 },
                            Modifier.weight(1f), privateMessages = messageTab == 1, unread = unreadHere,
                            channelManagement = connection.channelManagement)
                    }
                } else if (selectedTab == 1) {
                    SecondaryTabRow(selectedTabIndex = discoverTab) {
                        listOf("DISCOVER", "TRACE").forEachIndexed { index, label ->
                            Tab(selected = discoverTab == index, onClick = { discoverTab = index }, text = { Text(label) })
                        }
                    }
                    if (discoverTab == 0) {
                        DiscoverView(
                            discover, connection.discoverUnavailable, model::sendDiscover,
                            selectedTag = selectedDiscoverTag,
                            onSelect = { selectedDiscoverTag = it },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        tabState.SaveableStateProvider("trace") {
                            TraceView(traces, connection.traceSender, contacts.book, model::sendTrace, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

private data class MonitorTab(
    val label: String,
    @param:DrawableRes val icon: Int,
    val unread: Boolean = false,
    val progressDescription: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IconTabRow(
    selected: Int,
    onSelect: (Int) -> Unit,
    tabs: List<MonitorTab>,
    modifier: Modifier = Modifier,
) {
    SecondaryTabRow(
        selectedTabIndex = selected,
        modifier = modifier.widthIn(max = (tabs.size * 56).dp).fillMaxWidth(),
    ) {
        tabs.forEachIndexed { index, tab ->
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = {
                    PlainTooltip { Text(tab.progressDescription?.let { "${tab.label}\n$it" } ?: tab.label) }
                },
                state = rememberTooltipState(),
            ) {
                Tab(
                    selected = selected == index,
                    onClick = { onSelect(index) },
                    modifier = Modifier.semantics {
                        tab.progressDescription?.let { stateDescription = it }
                    },
                    icon = {
                        Box {
                            BadgedBox(badge = { if (tab.unread) UnreadDot() }) {
                                Icon(painterResource(tab.icon), contentDescription = tab.label)
                            }
                            if (tab.progressDescription != null) {
                                Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterStart) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.offset(x = (-14).dp).size(12.dp)
                                            .clearAndSetSemantics { },
                                        strokeWidth = 2.dp,
                                    )
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun UnreadDot() {
    Badge(
        modifier = Modifier.semantics { contentDescription = "Ungelesene Nachrichten" },
        containerColor = if (isSystemInDarkTheme()) Color(0xFFFF6B6B) else Color(0xFFC62828),
    )
}

@Composable
private fun UnreadLabel(label: String, unread: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        if (unread) UnreadDot()
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

internal fun formatUptime(seconds: Long): String {
    val minutes = seconds / 60
    val days = minutes / (24 * 60)
    val hours = minutes / 60 % 24
    return buildString {
        if (days > 0) append("${days}d ")
        if (days > 0 || hours > 0) append("${hours}h ")
        append("${minutes % 60}min")
    }
}
