package de.meshcore.pocketmonitor.rx

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal class HistoryDatabase(context: Context, name: String = "companion-history.db") :
    SQLiteOpenHelper(context, name, null, 2) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE companion (
                id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, last_seen INTEGER NOT NULL,
                rx_count INTEGER NOT NULL DEFAULT 0
            )
        """)
        db.execSQL("""
            CREATE TABLE channel (
                companion TEXT NOT NULL REFERENCES companion(id), identity TEXT NOT NULL,
                name TEXT NOT NULL, slot INTEGER,
                PRIMARY KEY (companion, identity)
            )
        """)
        db.execSQL("""
            CREATE TABLE message (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                companion TEXT NOT NULL, channel TEXT NOT NULL, comparison TEXT NOT NULL,
                sender TEXT, sent_at INTEGER NOT NULL, body TEXT NOT NULL,
                receptions INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (companion, channel) REFERENCES channel(companion, identity),
                UNIQUE (companion, channel, comparison)
            )
        """)
        db.execSQL("CREATE INDEX message_page ON message(companion, channel, sent_at DESC, id DESC)")
        db.execSQL("""
            CREATE TABLE rx (
                id INTEGER PRIMARY KEY AUTOINCREMENT, companion TEXT NOT NULL REFERENCES companion(id),
                session TEXT NOT NULL, ordinal INTEGER NOT NULL, received_at INTEGER NOT NULL,
                code INTEGER, frame BLOB NOT NULL, message_id INTEGER REFERENCES message(id),
                UNIQUE (session, ordinal)
            )
        """)
        db.execSQL("CREATE INDEX rx_page ON rx(companion, id DESC)")
        db.execSQL("CREATE INDEX rx_radio_page ON rx(companion, code, id DESC)")
        db.execSQL("CREATE INDEX rx_pending ON rx(companion, id) WHERE code = 136 AND message_id IS NULL")
        db.execSQL("CREATE INDEX rx_expiry ON rx(received_at)")
        db.execSQL("CREATE INDEX rx_message ON rx(message_id)")
        db.execSQL("""
            CREATE TRIGGER rx_insert AFTER INSERT ON rx BEGIN
                UPDATE companion SET rx_count = rx_count + 1 WHERE id = NEW.companion;
            END
        """)
        upgradeMessages(db)
        db.execSQL("CREATE TABLE legacy_import (session TEXT PRIMARY KEY NOT NULL, ordinal INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2)
        db.execSQL("DROP TRIGGER rx_link")
        db.execSQL("DROP TRIGGER rx_delete")
        upgradeMessages(db)
    }

    private fun upgradeMessages(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE message ADD COLUMN synchronized_count INTEGER NOT NULL DEFAULT 0")
        db.execSQL("""
            CREATE TRIGGER rx_link AFTER UPDATE OF message_id ON rx
            WHEN OLD.message_id IS NULL AND NEW.message_id IS NOT NULL BEGIN
                UPDATE message SET
                    receptions = receptions + (NEW.code = 136),
                    synchronized_count = synchronized_count + (NEW.code != 136)
                WHERE id = NEW.message_id;
            END
        """)
        db.execSQL("""
            CREATE TRIGGER rx_delete AFTER DELETE ON rx BEGIN
                UPDATE companion SET rx_count = rx_count - 1 WHERE id = OLD.companion;
                UPDATE message SET
                    receptions = receptions - (OLD.code = 136),
                    synchronized_count = synchronized_count - (OLD.code != 136)
                WHERE id = OLD.message_id;
                DELETE FROM message WHERE id = OLD.message_id AND receptions + synchronized_count = 0;
            END
        """)
    }
}

internal inline fun <T> SQLiteDatabase.inTransaction(block: () -> T): T {
    beginTransactionNonExclusive()
    try {
        return block().also { setTransactionSuccessful() }
    } finally {
        endTransaction()
    }
}
