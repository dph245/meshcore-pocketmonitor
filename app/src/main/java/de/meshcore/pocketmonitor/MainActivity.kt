package de.meshcore.pocketmonitor

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import de.meshcore.pocketmonitor.ble.scanPermissions
import de.meshcore.pocketmonitor.ui.MonitorScreen
import de.meshcore.pocketmonitor.ui.MonitorViewModel

class MainActivity : ComponentActivity() {
    private lateinit var model: MonitorViewModel
    private val requestScan = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasScanPermissions()) model.startScan() else model.permissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[MonitorViewModel::class.java]
        setContent {
            MonitorScreen(model, onScan = {
                if (hasScanPermissions()) model.startScan() else requestScan.launch(scanPermissions())
            })
        }
    }

    override fun onStop() {
        model.stopScan()
        super.onStop()
    }

    private fun hasScanPermissions(): Boolean =
        scanPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
}
