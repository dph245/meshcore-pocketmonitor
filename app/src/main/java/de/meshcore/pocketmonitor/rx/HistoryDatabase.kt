package de.meshcore.pocketmonitor.rx

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import de.meshcore.pocketmonitor.protocol.CompanionContact

internal class HistoryDatabase(context: Context, name: String = "companion-history.db") :
    SQLiteOpenHelper(context, name, null, 10) {

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
                message_discarded INTEGER NOT NULL DEFAULT 0,
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
        createGroupSendTables(db)
        createPrivateSendTable(db)
        addPrivateSendProgress(db)
        addUnreadColumn(db)
        indexIncomingMessages(db)
        createContactTypes(db)
        db.execSQL("CREATE TABLE legacy_import (session TEXT PRIMARY KEY NOT NULL, ordinal INTEGER NOT NULL)")
        createBackupState(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("DROP TRIGGER rx_link")
            db.execSQL("DROP TRIGGER rx_delete")
            upgradeMessages(db)
        }
        if (oldVersion < 3) createGroupSendTables(db)
        if (oldVersion < 4) createPrivateSendTable(db)
        if (oldVersion < 5) addUnreadColumn(db)
        if (oldVersion < 6) indexIncomingMessages(db)
        if (oldVersion < 7) {
            createContactTypes(db)
            db.execSQL("""
                INSERT INTO contact_type (companion, public_key, type)
                SELECT companion, public_key, ${CompanionContact.TYPE_REPEATER} FROM group_repeater
            """)
        }
        if (oldVersion < 8) addPrivateSendProgress(db)
        if (oldVersion < 9) db.execSQL("ALTER TABLE rx ADD COLUMN message_discarded INTEGER NOT NULL DEFAULT 0")
        if (oldVersion < 10) createBackupState(db)
    }

    private fun createBackupState(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE backup_state (restore_id TEXT PRIMARY KEY NOT NULL)")
    }

    private fun createContactTypes(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE contact_type (
                companion TEXT NOT NULL REFERENCES companion(id), public_key TEXT NOT NULL, type INTEGER NOT NULL,
                PRIMARY KEY (companion, public_key)
            )
        """)
    }

    private fun addUnreadColumn(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE message ADD COLUMN unread INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX message_unread ON message(companion, channel) WHERE unread = 1")
    }

    private fun indexIncomingMessages(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE INDEX message_incoming ON message(companion, channel, id DESC)
            WHERE substr(comparison, 1, 3) <> 'tx:'
        """)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // A new process only restores history. It never resumes an RF transmission.
        db.execSQL("""
            UPDATE private_send SET status = 'UNKNOWN' WHERE status IN ('WAITING', 'ACCEPTED')
        """)
    }

    private fun createPrivateSendTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE private_send (
                message_id INTEGER PRIMARY KEY REFERENCES message(id) ON DELETE CASCADE,
                created_at INTEGER NOT NULL, status TEXT NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX private_send_expiry ON private_send(created_at)")
    }

    private fun addPrivateSendProgress(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE private_send ADD COLUMN attempts INTEGER")
        db.execSQL("ALTER TABLE private_send ADD COLUMN last_route TEXT")
    }

    private fun createGroupSendTables(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE companion ADD COLUMN group_wire_time INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE companion ADD COLUMN group_scope_dirty INTEGER NOT NULL DEFAULT 0")
        db.execSQL("""
            CREATE TABLE group_send (
                message_id INTEGER PRIMARY KEY REFERENCES message(id) ON DELETE CASCADE,
                companion TEXT NOT NULL REFERENCES companion(id),
                created_at INTEGER NOT NULL, scope TEXT NOT NULL, status TEXT NOT NULL, digest TEXT,
                channel_name TEXT NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX group_send_expiry ON group_send(created_at)")
        db.execSQL("CREATE INDEX group_send_match ON group_send(companion, digest, created_at)")
        db.execSQL("""
            CREATE TABLE group_repeat (
                rx_id INTEGER PRIMARY KEY REFERENCES rx(id) ON DELETE CASCADE,
                send_id INTEGER NOT NULL REFERENCES group_send(message_id) ON DELETE CASCADE,
                prefix TEXT NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX group_repeat_send ON group_repeat(send_id, rx_id)")
        db.execSQL("""
            CREATE TABLE group_repeater (
                companion TEXT NOT NULL REFERENCES companion(id), public_key TEXT NOT NULL, name TEXT NOT NULL,
                PRIMARY KEY(companion, public_key)
            )
        """)
        db.execSQL("""
            CREATE TABLE group_scope (
                companion TEXT NOT NULL REFERENCES companion(id), channel TEXT NOT NULL, scope TEXT NOT NULL,
                PRIMARY KEY(companion, channel)
            )
        """)
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
