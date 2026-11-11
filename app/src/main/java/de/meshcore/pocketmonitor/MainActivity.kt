package de.meshcore.pocketmonitor

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import de.meshcore.pocketmonitor.ble.scanPermissions
import de.meshcore.pocketmonitor.ui.MonitorScreen
import de.meshcore.pocketmonitor.ui.MonitorViewModel

class MainActivity : ComponentActivity() {
    private var keepScreenOn by mutableStateOf(false)
    private lateinit var model: MonitorViewModel
    private val requestScan = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasScanPermissions()) model.startScan() else model.permissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[MonitorViewModel::class.java]
        val preferences = getPreferences(MODE_PRIVATE)
        keepScreenOn = preferences.getBoolean(KEEP_SCREEN_ON, false)
        updateScreenFlag()
        setContent {
            MonitorScreen(
                model,
                onScan = {
                    if (hasScanPermissions()) model.startScan() else requestScan.launch(scanPermissions())
                },
                keepScreenOn = keepScreenOn,
                onKeepScreenOnChange = { enabled ->
                    keepScreenOn = enabled
                    updateScreenFlag()
                    preferences.edit().putBoolean(KEEP_SCREEN_ON, enabled).apply()
                },
            )
        }
    }

    override fun onStop() {
        model.stopScan()
        super.onStop()
    }

    private fun updateScreenFlag() {
        if (keepScreenOn) window.addFlags(FLAG_KEEP_SCREEN_ON) else window.clearFlags(FLAG_KEEP_SCREEN_ON)
    }

    private fun hasScanPermissions(): Boolean =
        scanPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private companion object {
        const val KEEP_SCREEN_ON = "keep_screen_on"
    }
}
