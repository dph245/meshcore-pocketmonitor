package de.meshcore.pocketmonitor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
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
    private val connection = CompanionConnection(application)
    private val mutableScan = MutableStateFlow(ScanState())
    val scan = mutableScan.asStateFlow()
    val connectionStatus = connection.status
    val discover = connection.discover

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
        val device = scan.value.selected ?: return
        stopScan()
        connection.connect(device)
    }

    fun disconnect() = connection.disconnect()

    fun refreshContacts() = connection.refreshContacts()

    fun sendDiscover() = connection.sendDiscover()

    fun channelDecoding(journalName: String) = connection.channelDecoding(journalName)

    fun journalSnapshot(previous: JournalSnapshot?): JournalSnapshot? = connection.latestJournal?.snapshot(previous)

    override fun onCleared() {
        scanner.stop()
        connection.close()
    }
}
