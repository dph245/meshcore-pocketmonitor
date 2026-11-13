package de.meshcore.pocketmonitor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.meshcore.pocketmonitor.protocol.ChannelDecoding
import de.meshcore.pocketmonitor.protocol.RadioChannel
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
    private val connection = CompanionConnection(application, history)
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

    init {
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

    fun refreshContacts() = connection.refreshContacts()

    fun sendDiscover() = connection.sendDiscover()

    fun refreshChannels() = connection.refreshChannels()

    val historyRevision: Long get() = history.revision

    fun historyChannels(companion: String, private: Boolean) = history.channels(companion, private = private)

    fun channelMessages(companion: String, channel: String, before: MessageCursor?, limit: Int) =
        history.messages(companion, channel, before, limit)

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
