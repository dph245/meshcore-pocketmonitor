package de.meshcore.pocketmonitor

import android.app.Application
import de.meshcore.pocketmonitor.ble.CompanionConnection
import de.meshcore.pocketmonitor.rx.CompanionHistory

class PocketMonitorApplication : Application() {
    internal val history by lazy { CompanionHistory.get(this) }
    internal val connection by lazy { CompanionConnection(this, history) }
    internal var activityVisible = false
}
