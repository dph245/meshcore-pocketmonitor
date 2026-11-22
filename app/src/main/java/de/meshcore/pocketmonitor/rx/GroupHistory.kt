package de.meshcore.pocketmonitor.rx

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import de.meshcore.pocketmonitor.protocol.ChannelDecoding
import de.meshcore.pocketmonitor.protocol.CompanionContact
import de.meshcore.pocketmonitor.protocol.CompanionEvent
import de.meshcore.pocketmonitor.protocol.CompanionEventDecoder
import de.meshcore.pocketmonitor.protocol.CompanionProtocol
import de.meshcore.pocketmonitor.protocol.GroupSendStatus
import de.meshcore.pocketmonitor.protocol.MeshHeader
import de.meshcore.pocketmonitor.protocol.MeshPacketDecoder
import de.meshcore.pocketmonitor.protocol.PacketType
import de.meshcore.pocketmonitor.ui.decodeRadioRow
import java.util.UUID

internal data class OutgoingGroup(
    val scope: String,
    val status: GroupSendStatus,
    val repeats: Long,
    val channelName: String,
    val createdAt: Long,
)

internal data class GroupRepeat(val prefix: String, val row: RxRow)

internal class GroupHistory(private val db: SQLiteDatabase) {
    fun begin(companion: String, channel: HistoryChannel, text: String, now: Long, wireTime: Long,
              scope: String, digest: String?): Long {
        val message = ContentValues().apply {
            put("companion", companion)
            put("channel", channel.identity)
            put("comparison", "tx:${UUID.randomUUID()}")
            put("sent_at", now / 1000)
            put("body", text)
        }
        val id = db.insertOrThrow("message", null, message)
        db.execSQL("INSERT INTO group_send VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(id, companion, now, scope, GroupSendStatus.UNKNOWN.name, digest, channel.name))
        db.execSQL("INSERT OR REPLACE INTO group_scope VALUES (?, ?, ?)", arrayOf(companion, channel.identity, scope))
        // The wire clock is monotonic within each Companion, including across process restarts.
        db.execSQL("UPDATE companion SET group_wire_time = ? WHERE id = ?", arrayOf<Any>(wireTime, companion))
        return id
    }

    fun wireTime(companion: String, now: Long): Long =
        db.rawQuery("SELECT group_wire_time FROM companion WHERE id = ?", arrayOf(companion)).use {
            check(it.moveToFirst())
            maxOf(now / 1000, it.getLong(0) + 1)
        }

    fun update(id: Long, status: GroupSendStatus) {
        db.inTransaction {
            db.execSQL("UPDATE group_send SET status = ? WHERE message_id = ?", arrayOf<Any>(status.name, id))
            if (status == GroupSendStatus.REJECTED || status == GroupSendStatus.FAILED) {
                db.delete("group_repeat", "send_id = ?", arrayOf(id.toString()))
            }
        }
    }

    fun scope(companion: String, channel: String): String? =
        db.rawQuery("SELECT scope FROM group_scope WHERE companion = ? AND channel = ?", arrayOf(companion, channel))
            .use { if (it.moveToFirst()) it.getString(0) else null }

    fun receptionPaths(companion: String, messageId: Long): Map<List<String>, Int> = db.rawQuery("""
        SELECT id, received_at, frame FROM rx
        WHERE companion = ? AND message_id = ? AND code = ${CompanionProtocol.RF_LOG}
        ORDER BY id
    """, arrayOf(companion, messageId.toString())).use { cursor ->
        buildMap {
            while (cursor.moveToNext()) {
                val row = RxRow(cursor.getLong(0), RxFrame(cursor.getLong(1), cursor.getBlob(2)))
                val packet = decodeRadioRow(row, noKeys).packet ?: continue
                val header = packet.header ?: continue
                // DIRECT headers carry the remaining route. Only FLOOD records traversed hops.
                if (header.type != PacketType.GRP_TXT || header.route.isDirect) continue
                val prefixes = packet.path?.prefixes ?: continue
                put(prefixes, getOrDefault(prefixes, 0) + 1)
            }
        }
    }

    fun repeats(id: Long): List<GroupRepeat> = db.rawQuery("""
        SELECT prefix, rx.id, received_at, frame FROM group_repeat JOIN rx ON rx.id = rx_id
        WHERE send_id = ? ORDER BY rx.id
    """, arrayOf(id.toString())).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(GroupRepeat(cursor.getString(0), RxRow(cursor.getLong(1),
                    RxFrame(cursor.getLong(2), cursor.getBlob(3)))))
            }
        }
    }

    fun saveRepeaters(companion: String, contacts: List<CompanionContact>) {
        db.inTransaction {
            db.delete("group_repeater", "companion = ?", arrayOf(companion))
            val repeaters = contacts.filter { it.type == CompanionContact.TYPE_REPEATER }.distinctBy { it.publicKey }
            db.compileStatement("INSERT INTO group_repeater VALUES (?, ?, ?)").use { statement ->
                for (contact in repeaters) {
                    statement.bindString(1, companion)
                    statement.bindString(2, contact.publicKey)
                    statement.bindString(3, contact.displayName)
                    statement.executeInsert()
                }
            }
        }
    }

    fun repeaterNames(companion: String): Map<String, String> =
        db.rawQuery("SELECT public_key, name FROM group_repeater WHERE companion = ?", arrayOf(companion)).use {
            buildMap {
                while (it.moveToNext()) put(it.getString(0), it.getString(1))
            }
        }

    fun linkRepeat(companion: String, id: Long, frame: RxFrame): Boolean {
        if (frame.code != CompanionProtocol.RF_LOG) return false
        val header = MeshHeader((frame.bytes.getOrNull(3) ?: return false).toInt() and 0xff)
        if (header.type != PacketType.GRP_TXT || header.route.isDirect) return false
        val event = CompanionEventDecoder.decode(frame.bytes) as? CompanionEvent.RadioRx ?: return false
        val packet = MeshPacketDecoder.decode(event.radioBytes ?: return false, noKeys)
        val digest = packet.repeatDigest ?: return false
        val prefix = packet.path?.prefixes?.lastOrNull() ?: return false
        val sends = db.rawQuery("""
            SELECT message_id FROM group_send WHERE companion = ? AND digest = ?
                AND created_at <= ? AND created_at >= ? AND status IN ('UNKNOWN', 'ACCEPTED')
            LIMIT 2
        """, arrayOf(companion, digest, frame.timeMs.toString(), (frame.timeMs - REPEAT_WINDOW_MS).toString()))
            .use {
                if (!it.moveToFirst()) return false
                val candidate = it.getLong(0)
                if (it.moveToNext()) return false
                candidate
            }
        db.execSQL("INSERT OR IGNORE INTO group_repeat VALUES (?, ?, ?)", arrayOf<Any>(id, sends, prefix))
        return true
    }

    companion object {
        const val REPEAT_WINDOW_MS = 120_000L
        private val noKeys = ChannelDecoding(includeBuiltIn = false, diagnoseScope = false)
    }
}
