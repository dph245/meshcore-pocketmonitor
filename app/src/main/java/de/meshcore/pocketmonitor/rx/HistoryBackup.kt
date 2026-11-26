package de.meshcore.pocketmonitor.rx

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import java.io.File

internal object HistoryBackup {
    // Parent tables precede their dependants. sqlite_sequence is copied after the user tables.
    private val tables = listOf(
        "companion", "channel", "message", "rx", "legacy_import", "contact_type",
        "private_send", "group_send", "group_repeat", "group_repeater", "group_scope",
    )
    private val traceTables = listOf("trace_preset", "trace_series", "trace_measurement")
    private val allTables = tables + traceTables

    fun snapshot(source: SQLiteDatabase, destination: SQLiteDatabase) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            source.inTransaction { replace(destination, source) {} }
            return
        }
        source.beginTransactionReadOnly()
        try {
            replace(destination, source) {}
            source.setTransactionSuccessful()
        } finally {
            source.endTransaction()
        }
    }

    fun open(file: File): SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)

    fun validate(source: SQLiteDatabase, expectedVersion: Int) {
        check(source.version == expectedVersion || (source.version == 10 && expectedVersion == 11)) { "Die SQLite-Version dieser Sicherung wird nicht unterstützt." }
        check(source.rawQuery("PRAGMA quick_check", null).use { it.moveToFirst() && it.getString(0) == "ok" && !it.moveToNext() }) {
            "Die SQLite-Sicherung ist beschädigt."
        }
        check(source.rawQuery("PRAGMA foreign_key_check", null).use { !it.moveToFirst() }) {
            "Die SQLite-Sicherung enthält ungültige Verweise."
        }
        val names = source.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'", null)
            .use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        check(names - "android_metadata" == (tablesFor(source) + "backup_state").toSet()) { "Unerwartete Tabellen in der SQLite-Sicherung." }
    }

    fun replace(destination: SQLiteDatabase, source: SQLiteDatabase, afterCopy: () -> Unit) {
        destination.inTransaction {
            for (table in allTables.asReversed()) destination.delete(table, null, null)
            for (table in tablesFor(source)) {
                copyTable(source, destination, table)
                // rx_insert computes this count when the receptions are copied below.
                if (table == "companion") destination.execSQL("UPDATE companion SET rx_count = 0")
            }
            destination.delete("sqlite_sequence", null, null)
            copyTable(source, destination, "sqlite_sequence")
            afterCopy()
        }
    }

    private fun tablesFor(source: SQLiteDatabase) = if (source.version == 10) tables else allTables

    private fun copyTable(source: SQLiteDatabase, destination: SQLiteDatabase, table: String) {
        val columns = destination.rawQuery("SELECT * FROM `$table` LIMIT 0", null).use { it.columnNames }
        val columnsSql = columns.joinToString { "`$it`" }
        val placeholders = columns.joinToString { "?" }
        source.rawQuery("SELECT $columnsSql FROM `$table`", null).use { cursor ->
            destination.compileStatement("INSERT INTO `$table` ($columnsSql) VALUES ($placeholders)").use { insert ->
                while (cursor.moveToNext()) {
                    insert.clearBindings()
                    for (column in columns.indices) {
                        val parameter = column + 1
                        when (cursor.getType(column)) {
                            Cursor.FIELD_TYPE_NULL -> insert.bindNull(parameter)
                            Cursor.FIELD_TYPE_INTEGER -> insert.bindLong(parameter, cursor.getLong(column))
                            Cursor.FIELD_TYPE_FLOAT -> insert.bindDouble(parameter, cursor.getDouble(column))
                            Cursor.FIELD_TYPE_STRING -> insert.bindString(parameter, cursor.getString(column))
                            Cursor.FIELD_TYPE_BLOB -> insert.bindBlob(parameter, cursor.getBlob(column))
                        }
                    }
                    insert.executeInsert()
                }
            }
        }
    }
}
