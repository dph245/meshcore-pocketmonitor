package de.meshcore.pocketmonitor.backup

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import de.meshcore.pocketmonitor.rx.CompanionHistory
import de.meshcore.pocketmonitor.rx.RADIO_RETENTION_DAYS_KEY
import de.meshcore.pocketmonitor.rx.radioRetentionRange
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.io.SequenceInputStream
import org.json.JSONArray
import org.json.JSONObject

internal sealed interface BackupContents : Closeable {
    class App(
        val database: File,
        val preferences: Map<String, Map<String, Any>>,
    ) : BackupContents {
        override fun close() { database.delete() }
    }

    class Setup(val setup: CompanionSetup) : BackupContents {
        override fun close() { setup.privateKey?.fill(0) }
    }
}

internal class AppBackup(private val context: Context, private val history: CompanionHistory) {
    private val scratch = File(context.cacheDir, "manual-backup").apply {
        // A stopped process can leave its private working copy behind.
        deleteRecursively()
        check(mkdirs() || isDirectory)
    }

    private val recovery = AtomicFile(File(context.noBackupFilesDir, RECOVERY_FILE))

    @Synchronized
    fun writeApp(uri: Uri, password: CharArray) {
        recoverInterruptedRestore()
        val snapshot = newDatabaseFile()
        try {
            history.exportDatabase(context, snapshot)
            val header = ByteArrayOutputStream()
            DataOutputStream(header).use { output ->
                output.writeByte(APP)
                val json = JSONObject(readPreferences()).toString().encodeToByteArray()
                check(json.size <= MAX_PREFERENCES)
                output.writeInt(json.size)
                output.write(json)
            }
            SequenceInputStream(header.toByteArray().inputStream(), snapshot.inputStream()).use {
                write(uri, password, it)
            }
        } finally {
            snapshot.delete()
        }
    }

    fun writeSetup(uri: Uri, password: CharArray, setup: CompanionSetup) {
        val output = object : ByteArrayOutputStream() {
            fun input() = buf.inputStream(0, count)
            fun wipe() = buf.fill(0)
        }
        try {
            DataOutputStream(output).use {
                it.writeByte(SETUP)
                setup.write(it)
            }
            write(uri, password, output.input())
        } finally {
            output.wipe()
        }
    }

    private fun write(uri: Uri, password: CharArray, input: InputStream) {
        checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "Datei konnte nicht geöffnet werden." }
            .buffered().use { BackupEncryption.encrypt(password, input, it) }
    }

    fun read(uri: Uri, password: CharArray): BackupContents {
        val source = checkNotNull(context.contentResolver.openInputStream(uri)) { "Datei konnte nicht geöffnet werden." }
        source.buffered().use { encrypted ->
            return DataInputStream(BackupEncryption.decrypt(password, encrypted)).use { input ->
                when (input.readUnsignedByte()) {
                    APP -> readApp(input)
                    SETUP -> {
                        val setup = CompanionSetup.read(input)
                        try {
                            check(input.read() == -1) { "Zusätzliche Daten in der Companion-Sicherung." }
                            BackupContents.Setup(setup)
                        } catch (error: Exception) {
                            setup.privateKey?.fill(0)
                            throw error
                        }
                    }
                    else -> error("Unbekannter Sicherungstyp.")
                }
            }
        }
    }

    private fun readApp(input: DataInputStream): BackupContents.App {
        val size = input.readInt()
        check(size in 1..MAX_PREFERENCES) { "Ungültige Größe der App-Einstellungen." }
        val bytes = ByteArray(size).also(input::readFully)
        val preferences = decodePreferences(JSONObject(bytes.decodeToString(throwOnInvalidSequence = true)))
        val database = newDatabaseFile()
        try {
            database.outputStream().buffered().use { input.copyTo(it) }
            history.validateDatabase(database)
            return BackupContents.App(database, preferences)
        } catch (error: Exception) {
            database.delete()
            throw error
        }
    }

    @Synchronized
    fun restoreApp(backup: BackupContents.App) {
        recoverInterruptedRestore()
        val id = UUID.randomUUID().toString()
        val pending = JSONObject()
            .put("id", id)
            .put("old", JSONObject(readPreferences()))
            .put("new", JSONObject(backup.preferences))
        val stream = recovery.startWrite()
        try {
            stream.write(pending.toString().encodeToByteArray())
            recovery.finishWrite(stream)
        } catch (error: Exception) {
            recovery.failWrite(stream)
            throw error
        }

        try {
            history.restoreDatabase(backup.database, id) { writePreferences(backup.preferences) }
        } catch (error: Exception) {
            recoverInterruptedRestore()
            throw error
        }
        recovery.delete()
    }

    @Synchronized
    fun recoverInterruptedRestore() {
        if (!recovery.baseFile.exists() && !File(recovery.baseFile.path + ".bak").exists()) return
        val pending = JSONObject(recovery.readFully().decodeToString(throwOnInvalidSequence = true))
        // If the SQLite transaction rolled back, undo any SharedPreferences writes that survived it.
        val state = if (history.restoreCommitted(pending.getString("id"))) "new" else "old"
        writePreferences(decodePreferences(pending.getJSONObject(state)))
        recovery.delete()
    }

    private fun decodePreferences(json: JSONObject): Map<String, Map<String, Any>> {
        check(json.keys().asSequence().toSet() == preferenceFiles.toSet()) { "Unbekannte App-Einstellungen." }
        return preferenceFiles.associateWith { file ->
            val entries = json.getJSONObject(file)
            entries.keys().asSequence().associateWith { key ->
                val raw = entries.get(key)
                val value = if (raw is JSONArray) {
                    (0 until raw.length()).map { raw.get(it) }.map {
                        check(it is String) { "Ungültiger Eintrag in App-Einstellungen." }
                        it
                    }.toSet()
                } else raw
                check(validPreference(file, key, value)) { "Ungültiger Einstellungswert." }
                value
            }
        }
    }

    private fun validPreference(file: String, key: String, value: Any): Boolean = when (file) {
        "MainActivity" -> key == "keep_screen_on" && value is Boolean
        "live_preferences" -> if (key == RADIO_RETENTION_DAYS_KEY) value is Int && value in radioRetentionRange
            else value is String
        "installed_private_routes" -> value is Set<*>
        "tcp_connection" -> when (key) {
            "host" -> value is String
            "port" -> value is Int && value in 1..65_535
            "auto_reconnect" -> value is Boolean
            else -> false
        }
        "conversation_preferences" -> when (key.substringAfterLast('/')) {
            "path", "flood", "route_width" -> value is Int
            "reset_path", "manual" -> value is Boolean
            "private_order", "channel_order", "flood_scope", "route_hops" -> value is String
            else -> false
        }
        else -> false
    }

    private fun readPreferences() = preferenceFiles.associateWith { file ->
        context.getSharedPreferences(file, Context.MODE_PRIVATE).all.mapNotNull { (key, value) ->
            if (value == null || !validPreference(file, key, value)) return@mapNotNull null
            when (value) {
                is String, is Int, is Boolean -> key to value
                is Set<*> -> key to value.filterIsInstance<String>().toSet()
                else -> null
            }
        }.toMap()
    }

    @SuppressLint("UseKtx") // The transaction must check the actual commit result.
    private fun writePreferences(values: Map<String, Map<String, Any>>) {
        for ((file, entries) in values) {
            val editor = context.getSharedPreferences(file, Context.MODE_PRIVATE).edit()
            if (file == "MainActivity") editor.remove("keep_screen_on") else editor.clear()
            for ((key, value) in entries) {
                when (value) {
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            check(editor.commit()) { "App-Einstellungen konnten nicht gespeichert werden." }
        }
    }

    private fun newDatabaseFile(): File = File.createTempFile("history-", ".db", scratch)

    companion object {
        private const val RECOVERY_FILE = "manual-restore.json"

        fun hasInterruptedRestore(context: Context): Boolean {
            val base = File(context.noBackupFilesDir, RECOVERY_FILE)
            return base.exists() || File(base.path + ".bak").exists()
        }

        private const val APP = 1
        private const val SETUP = 2
        private const val MAX_PREFERENCES = 8 * 1024 * 1024
        private val preferenceFiles = listOf(
            "MainActivity", "conversation_preferences", "live_preferences", "tcp_connection", "installed_private_routes",
        )
    }
}
