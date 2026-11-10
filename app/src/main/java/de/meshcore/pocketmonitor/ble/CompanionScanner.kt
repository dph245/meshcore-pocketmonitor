package de.meshcore.pocketmonitor.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import de.meshcore.pocketmonitor.protocol.CompanionProtocol

internal fun scanPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

internal data class CompanionDevice(val address: String, val name: String, val device: BluetoothDevice)

@SuppressLint("MissingPermission") // The activity requests scan permissions before starting.
internal class CompanionScanner(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var active: ScanCallback? = null
    private var timeout: Runnable? = null

    fun start(onDevice: (CompanionDevice) -> Unit, onDone: (String?) -> Unit) {
        stop()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = deliver(result)

            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::deliver)

            override fun onScanFailed(errorCode: Int) {
                main.post {
                    if (active !== this) return@post
                    stop()
                    onDone("BLE-Scan fehlgeschlagen (Code $errorCode).")
                }
            }

            private fun deliver(result: ScanResult) {
                main.post {
                    if (active !== this || !result.isConnectable) return@post
                    val device = result.device
                    val name = result.scanRecord?.deviceName ?: "BLE-Companion"
                    onDevice(CompanionDevice(device.address, name, device))
                }
            }
        }
        try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            val ble = adapter?.bluetoothLeScanner
            if (ble == null) {
                onDone("Bluetooth ist ausgeschaltet oder nicht verfügbar.")
                return
            }
            if (Build.VERSION.SDK_INT <= 30 && !legacyLocationEnabled()) {
                onDone("Bitte den Systemstandort für den BLE-Scan aktivieren.")
                return
            }

            val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(CompanionProtocol.service)).build())
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner = ble
            active = callback
            ble.startScan(filters, settings, callback)
            timeout = Runnable {
                stop()
                onDone(null)
            }.also { main.postDelayed(it, 12_000) }
        } catch (_: SecurityException) {
            stop()
            onDone("Bluetooth- oder Scan-Berechtigung fehlt.")
        } catch (_: IllegalStateException) {
            stop()
            onDone("Bluetooth ist für den Scan nicht bereit.")
        }
    }

    fun stop() {
        timeout?.let(main::removeCallbacks)
        timeout = null
        val callback = active
        active = null
        if (callback != null) runCatching { scanner?.stopScan(callback) }
        scanner = null
    }

    private fun legacyLocationEnabled(): Boolean {
        val location = context.getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= 28) location.isLocationEnabled else {
            location.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }
}
