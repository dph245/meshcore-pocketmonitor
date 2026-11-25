package de.meshcore.pocketmonitor

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.sqlite.SQLiteException
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import de.meshcore.pocketmonitor.ble.CompanionDevice
import de.meshcore.pocketmonitor.tcp.TcpEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CompanionReceiveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as PocketMonitorApplication
    private val notifications get() = getSystemService(NotificationManager::class.java)
    private var receiving = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != CONNECT_BLE && intent?.action != CONNECT_TCP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (receiving) return START_NOT_STICKY

        val endpoint: TcpEndpoint?
        val device: CompanionDevice?
        if (intent.action == CONNECT_TCP) {
            endpoint = TcpEndpoint.parse(intent.getStringExtra(ADDRESS).orEmpty(), intent.getIntExtra(PORT, 0))
            device = null
        } else {
            endpoint = null
            device = bleDevice(intent)
        }
        val name = endpoint?.toString() ?: device?.name
        if (name == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        notifications.createNotificationChannels(listOf(
            NotificationChannel(CONNECTION_CHANNEL, "Companion-Verbindung", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
            NotificationChannel(MESSAGE_CHANNEL, "Neue MeshCore-Nachrichten", NotificationManager.IMPORTANCE_DEFAULT),
        ))
        ServiceCompat.startForeground(
            this, CONNECTION_NOTIFICATION, connectionNotification("Verbinde mit $name …", endpoint != null),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
        receiving = true
        scope.launch {
            // Take the baseline before the companion can deliver messages. Existing unread history stays silent.
            val baseline = withContext(Dispatchers.IO) {
                try { app.history.newestMessageId() } catch (_: SQLiteException) { null }
            }
            val stopped: () -> Unit = { scope.launch { stopSelf() } }
            if (endpoint != null) app.connection.connect(endpoint, stopped)
            else if (device != null) app.connection.connect(device, stopped)
            if (baseline != null) watchMessages(baseline)
        }
        scope.launch {
            app.connection.status.filter { it.active }.map { it.message }.distinctUntilChanged().collect {
                notifications.notify(CONNECTION_NOTIFICATION, connectionNotification(it, endpoint != null))
            }
        }
        return START_NOT_STICKY
    }

    private fun bleDevice(intent: Intent): CompanionDevice? {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        val device = IntentCompat.getParcelableExtra(intent, DEVICE, BluetoothDevice::class.java) ?: return null
        val address = intent.getStringExtra(ADDRESS) ?: return null
        val name = intent.getStringExtra(NAME) ?: return null
        return CompanionDevice(address, name, device)
    }

    private suspend fun watchMessages(baseline: Long) {
        var seen = baseline
        var revision = -1L
        while (true) {
            val current = app.history.revision
            if (current != revision) {
                val newest = withContext(Dispatchers.IO) {
                    try { app.history.newestUnreadMessageAfter(seen) } catch (_: SQLiteException) { null }
                }
                if (newest != null) {
                    revision = current
                    if (newest > seen) {
                        seen = newest
                        if (!app.activityVisible && notifications.areNotificationsEnabled()) {
                            notifications.notify(MESSAGE_NOTIFICATION, Notification.Builder(this, MESSAGE_CHANNEL)
                                .setSmallIcon(R.drawable.ic_tab_messages)
                                .setContentTitle("Neue MeshCore-Nachrichten")
                                .setContentText("PocketMonitor öffnen, um sie zu lesen.")
                                .setVisibility(Notification.VISIBILITY_PRIVATE)
                                .setContentIntent(openApp(messages = true))
                                .setAutoCancel(true)
                                .build())
                        }
                    }
                }
            }
            delay(2_000)
        }
    }

    private fun connectionNotification(message: String, tcp: Boolean): Notification = Notification.Builder(this, CONNECTION_CHANNEL)
        .setSmallIcon(if (tcp) R.drawable.ic_tab_network else R.drawable.ic_tab_bluetooth)
        .setContentTitle("PocketMonitor · Hintergrundempfang")
        .setContentText(message)
        .setContentIntent(openApp())
        .setVisibility(Notification.VISIBILITY_PRIVATE)
        .setOngoing(true)
        .addAction(Notification.Action.Builder(null, "Trennen", PendingIntent.getService(
            this, 0, Intent(this, CompanionReceiveService::class.java).setAction(DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE,
        )).build())
        .build()

    private fun openApp(messages: Boolean = false): PendingIntent = PendingIntent.getActivity(
        this, if (messages) 1 else 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setAction(if (messages) OPEN_MESSAGES else Intent.ACTION_MAIN),
        PendingIntent.FLAG_IMMUTABLE,
    )

    override fun onDestroy() {
        scope.cancel()
        if (receiving) {
            app.connection.disconnect()
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        super.onDestroy()
    }

    internal companion object {
        const val OPEN_MESSAGES = "de.meshcore.pocketmonitor.OPEN_MESSAGES"
        const val MESSAGE_NOTIFICATION = 2
        private const val CONNECTION_NOTIFICATION = 1
        private const val CONNECTION_CHANNEL = "companion_connection"
        private const val MESSAGE_CHANNEL = "mesh_messages"
        private const val CONNECT_BLE = "connect"
        private const val CONNECT_TCP = "connect_tcp"
        private const val DISCONNECT = "disconnect"
        private const val DEVICE = "device"
        private const val ADDRESS = "address"
        private const val NAME = "name"
        private const val PORT = "port"

        fun start(context: Context, device: CompanionDevice) {
            ContextCompat.startForegroundService(context, Intent(context, CompanionReceiveService::class.java)
                .setAction(CONNECT_BLE)
                .putExtra(DEVICE, device.device)
                .putExtra(ADDRESS, device.address)
                .putExtra(NAME, device.name))
        }

        fun start(context: Context, endpoint: TcpEndpoint) {
            ContextCompat.startForegroundService(context, Intent(context, CompanionReceiveService::class.java)
                .setAction(CONNECT_TCP)
                .putExtra(ADDRESS, endpoint.host)
                .putExtra(PORT, endpoint.port))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CompanionReceiveService::class.java).setAction(DISCONNECT))
        }
    }
}
