package de.meshcore.pocketmonitor.ui

import android.app.Application
import android.content.Context
import de.meshcore.pocketmonitor.protocol.PrivateRetryOptions
import de.meshcore.pocketmonitor.protocol.TracePath
import de.meshcore.pocketmonitor.protocol.PrivateRoute
import de.meshcore.pocketmonitor.protocol.CompanionManagement
import de.meshcore.pocketmonitor.protocol.GroupSend
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.meshcore.pocketmonitor.protocol.ChannelDecoding
import de.meshcore.pocketmonitor.protocol.CompanionAction
import de.meshcore.pocketmonitor.protocol.RadioChannel
import de.meshcore.pocketmonitor.protocol.RepeaterSort
import de.meshcore.pocketmonitor.rx.CompanionHistory
import de.meshcore.pocketmonitor.rx.HistoryChannel
import de.meshcore.pocketmonitor.rx.KnownCompanion
import de.meshcore.pocketmonitor.rx.LegacyJournalImport
import de.meshcore.pocketmonitor.rx.MessageCursor
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import de.meshcore.pocketmonitor.ble.CompanionConnection
import de.meshcore.pocketmonitor.ble.CompanionDevice
import de.meshcore.pocketmonitor.ble.CompanionScanner
import de.meshcore.pocketmonitor.rx.JournalSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ScanState(
    val devices: List<CompanionDevice> = emptyList(),
    val selected: CompanionDevice? = null,
    val scanning: Boolean = false,
    val message: String? = null,
)

internal class MonitorViewModel(application: Application) : AndroidViewModel(application) {
    private val scanner = CompanionScanner(application)
    private val history = CompanionHistory.get(application)
    private val preferences = application.getSharedPreferences("conversation_preferences", Context.MODE_PRIVATE)
    private val livePreferences = application.getSharedPreferences("live_preferences", Context.MODE_PRIVATE)
    private val connection = CompanionConnection(application, history)
    private var terminal: CliTerminalState? = null
    private val mutableCompanions = MutableStateFlow<List<KnownCompanion>>(emptyList())
    val companions = mutableCompanions.asStateFlow()
    private val mutableSelectedCompanion = MutableStateFlow<String?>(null)
    val selectedCompanion = mutableSelectedCompanion.asStateFlow()
    private val mutableHistoryError = MutableStateFlow<String?>(null)
    val historyError = mutableHistoryError.asStateFlow()
    private val mutableHistoryReady = MutableStateFlow(false)
    val historyReady = mutableHistoryReady.asStateFlow()
    private val mutableScan = MutableStateFlow(ScanState())
    val scan = mutableScan.asStateFlow()
    val connectionStatus = connection.status
    val discover = connection.discover
    val traces = connection.traces
    val groupSender = connection.groupSender
    val privateSender = connection.privateSender
    val privatePath = connection.privatePath
    val liveRepeaters = connection.liveRepeaters
    private val mutablePrivateOptionsRevision = MutableStateFlow(0L)
    val privateOptionsRevision = mutablePrivateOptionsRevision.asStateFlow()

    fun liveRepeaterSort(): RepeaterSort {
        val saved = livePreferences.getString("repeater_sort", null)
        return RepeaterSort.entries.firstOrNull { it.name == saved } ?: RepeaterSort.RECENT
    }

    fun saveLiveRepeaterSort(sort: RepeaterSort) {
        livePreferences.edit { putString("repeater_sort", sort.name) }
    }

    fun sendPrivate(session: String, companion: String, key: String, text: String,
                    options: PrivateRetryOptions, completed: (String?) -> Unit) {
        connection.sendPrivate(session, companion, key, text, options) { error ->
            viewModelScope.launch { completed(error) }
        }
    }

    fun resetPrivatePath(session: String, companion: String, key: String, completed: (String?) -> Unit) {
        manageCompanion(session, CompanionAction.ResetPrivatePath(key)) { result ->
            if (result.isSuccess) {
                val saved = privateRetryOptions(companion, key)
                savePrivateRetryOptions(companion, key, saved.copy(manualRouting = false))
            }
            completed(result.exceptionOrNull()?.message)
        }
    }

    fun groupScope(companion: String, channel: String) = history.groupScope(companion, channel)

    fun groupRepeats(id: Long) = history.groupRepeats(id)

    fun groupRepeaterNames(companion: String) = history.groupRepeaterNames(companion)

    fun sendGroup(session: String, companion: String, channel: String, text: String, scope: String,
                  completed: (String?) -> Unit) {
        connection.sendGroup(session, companion, channel, text, scope) { error ->
            viewModelScope.launch { completed(error) }
        }
    }

    init {
        viewModelScope.launch {
            connectionStatus.collect { status ->
                if (terminal?.session != status.management?.session) terminal = null
            }
        }
        viewModelScope.launch {
            connection.companion.collect { companion ->
                if (companion != null) {
                    mutableSelectedCompanion.value = companion.id
                    mutableCompanions.value = listOf(companion) + companions.value.filter { it.id != companion.id }
                }
            }
        }
        viewModelScope.launch {
            val known = withContext(Dispatchers.IO) {
                try {
                    history.prune()
                    LegacyJournalImport(history, File(application.filesDir, "rx")).run(System.currentTimeMillis())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutableHistoryError.value = "Historie konnte nicht vollständig vorbereitet werden: ${e.message}"
                }
                try {
                    history.knownCompanions()
                } catch (e: android.database.sqlite.SQLiteException) {
                    mutableHistoryError.value = "Historie konnte nicht geöffnet werden: ${e.message}"
                    emptyList()
                }
            }
            mutableCompanions.value = known
            mutableSelectedCompanion.value = known.firstOrNull()?.id
            mutableHistoryReady.value = true
            while (true) {
                delay(60_000)
                try {
                    withContext(Dispatchers.IO) { history.prune() }
                } catch (e: android.database.sqlite.SQLiteException) {
                    mutableHistoryError.value = "Historie konnte nicht bereinigt werden: ${e.message}"
                }
            }
        }
    }

    fun selectHistory(id: String) {
        if (!connectionStatus.value.active) mutableSelectedCompanion.value = id
    }

    fun startScan() {
        if (connectionStatus.value.active || scan.value.scanning) return
        mutableScan.value = ScanState(scanning = true)
        scanner.start(onDevice = { device ->
            val state = mutableScan.value
            val devices = state.devices
            if (devices.none { it.address == device.address }) {
                mutableScan.value = state.copy(devices = devices + device, selected = state.selected ?: device)
            }
        }, onDone = { error ->
            val state = mutableScan.value
            mutableScan.value = state.copy(scanning = false, message = error ?:
                if (state.devices.isEmpty()) "Keine Geräte mit Companion-Dienst gefunden." else null)
        })
    }

    fun stopScan() {
        scanner.stop()
        mutableScan.value = scan.value.copy(scanning = false)
    }

    fun permissionDenied() {
        mutableScan.value = scan.value.copy(message = "BLE-Scan-Berechtigung fehlt. Bitte in den App-Einstellungen erlauben.")
    }

    fun select(device: CompanionDevice) {
        mutableScan.value = scan.value.copy(selected = device)
    }

    fun connect() {
        if (!historyReady.value) return
        val device = scan.value.selected ?: return
        stopScan()
        connection.connect(device)
    }

    fun disconnect() = connection.disconnect()

    fun watchPrivatePath(session: String, key: String, watching: Boolean) =
        connection.watchPrivatePath(session, key, watching)

    fun refreshContacts() = connection.refreshContacts()

    fun sendDiscover() = connection.sendDiscover()

    fun sendTrace(session: String, path: TracePath) =
        connection.sendTrace(session, path)

    fun cliTerminal(session: String): CliTerminalState =
        terminal?.takeIf { it.session == session } ?: CliTerminalState(session).also { terminal = it }

    fun manageCompanion(session: String, action: CompanionAction, completed: (Result<String?>) -> Unit) {
        connection.manage(session, action) { result ->
            viewModelScope.launch { completed(result) }
        }
    }

    fun refreshChannels() = connection.refreshChannels()

    val historyRevision: Long get() = history.revision

    fun unreadConversations() = history.unreadConversations()

    suspend fun markMessagesRead(companion: String, channel: String, ids: List<Long>) =
        withContext(Dispatchers.IO) { history.markRead(companion, channel, ids) }

    fun historyChannels(companion: String, private: Boolean) = history.channels(companion, private = private)

    fun conversationOrder(privateMessages: Boolean): ConversationOrder {
        val saved = preferences.getString(conversationOrderKey(privateMessages), null)
        return ConversationOrder.entries.firstOrNull { it.name == saved } ?: ConversationOrder.ALPHABETICAL
    }

    fun saveConversationOrder(privateMessages: Boolean, order: ConversationOrder) {
        preferences.edit { putString(conversationOrderKey(privateMessages), order.name) }
    }

    private fun conversationOrderKey(privateMessages: Boolean): String =
        if (privateMessages) "private_order" else "channel_order"

    fun privateRetryOptions(companion: String, contact: String): PrivateRetryOptions {
        val key = privateRetryKey(companion, contact)
        val defaults = PrivateRetryOptions()
        val route = PrivateRoute.create(
            preferences.getInt("$key/route_width", 1),
            preferences.getString("$key/route_hops", null)?.split(',').orEmpty(),
        )
        val savedScope = preferences.getString("$key/flood_scope", null)
        val scope = when (savedScope) {
            GroupSend.UNSCOPED -> savedScope
            GroupSend.DEFAULT, null -> GroupSend.DEFAULT
            else -> CompanionManagement.namedScope(savedScope) ?: GroupSend.DEFAULT
        }
        return PrivateRetryOptions(
            pathAttempts = preferences.getInt("$key/path", defaults.pathAttempts)
                .takeIf { it in 1..PrivateRetryOptions.MAX_ATTEMPTS } ?: defaults.pathAttempts,
            floodAttempts = preferences.getInt("$key/flood", defaults.floodAttempts)
                .takeIf { it in 1..PrivateRetryOptions.MAX_ATTEMPTS } ?: defaults.floodAttempts,
            resetPathForFlood = preferences.getBoolean("$key/reset_path", defaults.resetPathForFlood),
            floodScope = scope,
            manualRouting = route != null && preferences.getBoolean("$key/manual", false),
            manualRoute = route,
        )
    }

    fun savePrivateRetryOptions(companion: String, contact: String, options: PrivateRetryOptions) {
        val key = privateRetryKey(companion, contact)
        preferences.edit {
            putInt("$key/path", options.pathAttempts)
            putInt("$key/flood", options.floodAttempts)
            putBoolean("$key/reset_path", options.resetPathForFlood)
            putString("$key/flood_scope", options.floodScope)
            putBoolean("$key/manual", options.manualRouting)
            putInt("$key/route_width", options.manualRoute?.width ?: 1)
            putString("$key/route_hops", options.manualRoute?.hops?.joinToString(","))
        }
        mutablePrivateOptionsRevision.value++
    }

    private fun privateRetryKey(companion: String, contact: String): String =
        "private_retry/$companion/$contact"

    suspend fun markConversationReadThrough(companion: String, channel: String, through: Long) =
        withContext(Dispatchers.IO) { history.markReadThrough(companion, channel, through) }

    fun channelMessages(
        companion: String, channel: String, before: MessageCursor?, limit: Int, includeUnreadThrough: Boolean = false,
    ) = history.messages(companion, channel, before, limit, includeUnreadThrough)

    // Called by the live view's single IO reader.
    private var publicChannels: Pair<String, List<HistoryChannel>>? = null
    private var publicDecoding = ChannelDecoding.default
    private var publicRevision = -1L

    fun channelDecoding(companion: String): ChannelDecoding {
        connection.channelDecoding(companion)?.let { return it }
        val revision = history.configRevision
        if (publicRevision == revision && publicChannels?.first == companion) return publicDecoding
        val channels = history.channels(companion, currentOnly = true)
        val config = companion to channels
        if (config != publicChannels) {
            val restored = channels.mapNotNull { RadioChannel.restorePublic(it.name, it.identity) }
            publicDecoding = if (restored.isEmpty()) ChannelDecoding.default else ChannelDecoding(restored)
            publicChannels = config
        }
        publicRevision = revision
        return publicDecoding
    }

    fun journalSnapshot(companion: String, previous: JournalSnapshot?, before: Long?): JournalSnapshot =
        history.journal(companion, previous, before, connection.historyError)

    override fun onCleared() {
        scanner.stop()
        connection.close()
    }
}
