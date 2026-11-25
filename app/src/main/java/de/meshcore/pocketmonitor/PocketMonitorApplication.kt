package de.meshcore.pocketmonitor

import android.app.Application
import de.meshcore.pocketmonitor.backup.AppBackup
import de.meshcore.pocketmonitor.ble.CompanionConnection
import de.meshcore.pocketmonitor.rx.CompanionHistory

class PocketMonitorApplication : Application() {
    internal val history by lazy { CompanionHistory.get(this) }
    internal val connection by lazy { CompanionConnection(this, history) }
    internal val backup by lazy { AppBackup(this, history) }
    internal var activityVisible = false
    internal var backupRecoveryFailed = false
        private set

    override fun onCreate() {
        super.onCreate()
        if (AppBackup.hasInterruptedRestore(this)) {
            backupRecoveryFailed = runCatching { backup.recoverInterruptedRestore() }.isFailure
        }
    }
}
