package de.meshcore.pocketmonitor

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.ViewModelProvider
import de.meshcore.pocketmonitor.ble.scanPermissions
import de.meshcore.pocketmonitor.ui.MonitorScreen
import de.meshcore.pocketmonitor.ui.MonitorViewModel

class MainActivity : ComponentActivity() {
    private var keepScreenOn by mutableStateOf(false)
    private var openMessagesRequest by mutableIntStateOf(0)
    private lateinit var model: MonitorViewModel
    private val requestScan = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasScanPermissions()) model.startScan() else model.permissionDenied()
    }
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[MonitorViewModel::class.java]
        handleNotification(intent)
        val preferences = getPreferences(MODE_PRIVATE)
        keepScreenOn = preferences.getBoolean(KEEP_SCREEN_ON, false)
        updateScreenFlag()
        setContent {
            MonitorScreen(
                model,
                onScan = {
                    if (hasScanPermissions()) model.startScan() else requestScan.launch(scanPermissions())
                },
                onConnect = { if (model.connect()) requestNotificationsIfNeeded() },
                onTcpConnect = { endpoint ->
                    if (model.connectTcp(endpoint)) requestNotificationsIfNeeded()
                },
                openMessagesRequest = openMessagesRequest,
                keepScreenOn = keepScreenOn,
                onKeepScreenOnChange = { enabled ->
                    keepScreenOn = enabled
                    updateScreenFlag()
                    preferences.edit().putBoolean(KEEP_SCREEN_ON, enabled).apply()
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotification(intent)
    }

    override fun onStart() {
        super.onStart()
        (application as PocketMonitorApplication).activityVisible = true
        getSystemService(NotificationManager::class.java).cancel(CompanionReceiveService.MESSAGE_NOTIFICATION)
    }

    override fun onStop() {
        (application as PocketMonitorApplication).activityVisible = false
        model.stopScan()
        super.onStop()
    }

    private fun handleNotification(intent: Intent) {
        if (intent.action == CompanionReceiveService.OPEN_MESSAGES) {
            openMessagesRequest++
            intent.action = Intent.ACTION_MAIN
        }
    }

    private fun requestNotificationsIfNeeded() {
        val preferences = getPreferences(MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !preferences.getBoolean(ASKED_NOTIFICATIONS, false)
        ) {
            preferences.edit { putBoolean(ASKED_NOTIFICATIONS, true) }
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun updateScreenFlag() {
        if (keepScreenOn) window.addFlags(FLAG_KEEP_SCREEN_ON) else window.clearFlags(FLAG_KEEP_SCREEN_ON)
    }

    private fun hasScanPermissions(): Boolean =
        scanPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private companion object {
        const val KEEP_SCREEN_ON = "keep_screen_on"
        const val ASKED_NOTIFICATIONS = "asked_notifications"
    }
}
