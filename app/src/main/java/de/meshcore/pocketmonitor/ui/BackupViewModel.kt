package de.meshcore.pocketmonitor.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.meshcore.pocketmonitor.PocketMonitorApplication
import de.meshcore.pocketmonitor.backup.BackupContents
import java.io.EOFException
import java.io.IOException
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class BackupAction { EXPORT_APP, EXPORT_SETUP, IMPORT }

internal sealed interface BackupState {
    data object Idle : BackupState
    data class Password(val action: BackupAction, val uri: Uri) : BackupState
    data class Preview(val contents: BackupContents) : BackupState
    data object Working : BackupState
    data class Notice(val message: String) : BackupState
}

internal class BackupViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as PocketMonitorApplication
    private val connection get() = app.connection
    private val files get() = app.backup
    private val mutableState = MutableStateFlow<BackupState>(
        if (app.backupRecoveryFailed) BackupState.Notice("Eine unterbrochene App-Wiederherstellung konnte nicht vollständig abgeschlossen werden.")
        else BackupState.Idle,
    )
    val state = mutableState.asStateFlow()

    fun select(action: BackupAction, uri: Uri) {
        if (mutableState.value == BackupState.Working) return
        discard()
        mutableState.value = BackupState.Password(action, uri)
    }

    fun discard() {
        val previous = mutableState.value
        if (previous == BackupState.Working) return
        (previous as? BackupState.Preview)?.contents?.close()
        mutableState.value = BackupState.Idle
    }

    fun submit(password: CharArray, includeIdentity: Boolean, session: String?) {
        val input = mutableState.value as? BackupState.Password ?: run {
            password.fill('\u0000')
            return
        }
        runOperation {
            try {
                when (input.action) {
                    BackupAction.IMPORT -> BackupState.Preview(withContext(Dispatchers.IO) { files.read(input.uri, password) })
                    BackupAction.EXPORT_APP -> {
                        withContext(Dispatchers.IO) { files.writeApp(input.uri, password) }
                        BackupState.Notice("App-Sicherung gespeichert.")
                    }
                    BackupAction.EXPORT_SETUP -> {
                        val setup = connection.captureSetup(checkNotNull(session) { "Companion ist getrennt." }, includeIdentity)
                        try {
                            withContext(Dispatchers.IO) { files.writeSetup(input.uri, password, setup) }
                            val suffix = if (includeIdentity && setup.privateKey == null) {
                                " Die Firmware hat den Export der Identität deaktiviert oder unterstützt ihn nicht."
                            } else ""
                            BackupState.Notice("Companion-Sicherung gespeichert.$suffix")
                        } finally {
                            setup.privateKey?.fill(0)
                        }
                    }
                }
            } finally {
                password.fill('\u0000')
            }
        }
    }

    fun restore(session: String?, includeIdentity: Boolean, onAppRestored: suspend () -> Unit) {
        val preview = mutableState.value as? BackupState.Preview ?: return
        runOperation {
            preview.contents.use { contents ->
                when (contents) {
                    is BackupContents.App -> {
                        connection.withDisconnectedHistory {
                            withContext(Dispatchers.IO) { files.restoreApp(contents) }
                            connection.reloadRestoredPreferences()
                            onAppRestored()
                        }
                        BackupState.Notice("App-Daten und Einstellungen wurden wiederhergestellt.")
                    }
                    is BackupContents.Setup -> {
                        connection.restoreSetup(checkNotNull(session) { "Companion ist getrennt." }, contents.setup, includeIdentity)
                        BackupState.Notice("Companion-Setup wiederhergestellt. Verbinde den Companion erneut.")
                    }
                }
            }
        }
    }

    private fun runOperation(block: suspend () -> BackupState) {
        mutableState.value = BackupState.Working
        viewModelScope.launch {
            mutableState.value = try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (_: AEADBadTagException) {
                BackupState.Notice("Passwort falsch oder Sicherung beschädigt.")
            } catch (_: EOFException) {
                BackupState.Notice("Die Sicherung ist unvollständig.")
            } catch (_: IOException) {
                BackupState.Notice("Die Sicherungsdatei konnte nicht vollständig gelesen oder geschrieben werden.")
            } catch (error: IllegalStateException) {
                BackupState.Notice(error.message ?: "Der Sicherungsvorgang ist fehlgeschlagen.")
            } catch (_: Exception) {
                BackupState.Notice("Sicherungsvorgang fehlgeschlagen. Prüfe die Datei und den verfügbaren Speicher.")
            }
        }
    }

    override fun onCleared() {
        (mutableState.value as? BackupState.Preview)?.contents?.close()
    }
}
