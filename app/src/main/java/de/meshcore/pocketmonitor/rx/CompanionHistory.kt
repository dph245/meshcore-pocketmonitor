package de.meshcore.pocketmonitor.rx

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import de.meshcore.pocketmonitor.protocol.ChannelDecoding
import de.meshcore.pocketmonitor.protocol.ChannelQuery
import de.meshcore.pocketmonitor.protocol.ChannelSlot
import de.meshcore.pocketmonitor.protocol.CompanionContact
import de.meshcore.pocketmonitor.protocol.MessageAddress
import de.meshcore.pocketmonitor.protocol.QueuedMessage
import de.meshcore.pocketmonitor.protocol.splitChannelText
import de.meshcore.pocketmonitor.protocol.CompanionProtocol
import de.meshcore.pocketmonitor.protocol.MeshPayload
import de.meshcore.pocketmonitor.protocol.MeshHeader
import de.meshcore.pocketmonitor.protocol.PacketType
import de.meshcore.pocketmonitor.ui.ChannelMessage
import de.meshcore.pocketmonitor.ui.channelClock
import de.meshcore.pocketmonitor.ui.decodeRadioRow
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

internal data class KnownCompanion(val id: String, val name: String)
internal data class HistoryChannel(val identity: String, val name: String, val slot: Int?)
internal data class HistoryPage<T>(val rows: List<T>, val hasOlder: Boolean)
internal data class MessageCursor(val time: Long, val id: Long)
internal data class Received(val ordinal: Long, val frame: RxFrame)
internal data class DecodedMessage(
    val payload: MeshPayload.GroupText,
    val comparison: String,
)

private const val PRIVATE_ID_PREFIX = "pm:"

internal const val LIVE_PAGE_SIZE = 500
internal const val CHAT_PAGE_SIZE = 200
internal const val HISTORY_AGE_MS = 30L * 24 * 60 * 60 * 1000

internal class CompanionHistory(private val helper: HistoryDatabase) {
    private val changes = AtomicLong()
    private val configChanges = AtomicLong()
    val configRevision: Long get() = configChanges.get()
    val revision: Long get() = changes.get()
    private val db: SQLiteDatabase get() = helper.writableDatabase

    fun register(id: String, name: String, time: Long) {
        val database = db
        database.inTransaction { register(database, id, name, time) }
        changes.incrementAndGet()
    }

    fun knownCompanions(): List<KnownCompanion> =
        db.rawQuery("SELECT id, name FROM companion ORDER BY last_seen DESC, id", null)
            .use { cursor -> cursor.collect { KnownCompanion(getString(0), getString(1)) } }

    fun configure(companion: String, slots: List<ChannelSlot>, complete: Boolean) {
        val database = db
        database.inTransaction {
            if (complete) database.execSQL("UPDATE channel SET slot = NULL WHERE companion = ?", arrayOf(companion))
            for (slot in slots.distinctBy { it.channel.identity }) {
                val channel = HistoryChannel(slot.channel.identity, slot.channel.name, slot.index.takeIf { complete })
                insertChannel(database, companion, channel)
                if (complete) {
                    database.execSQL(
                        "UPDATE channel SET name = ?, slot = ? WHERE companion = ? AND identity = ?",
                        arrayOf<Any?>(channel.name, channel.slot, companion, channel.identity),
                    )
                }
            }
            if (complete) database.execSQL("""
                DELETE FROM channel WHERE companion = ? AND slot IS NULL AND NOT EXISTS (
                    SELECT 1 FROM message WHERE message.companion = channel.companion AND message.channel = channel.identity
                )
            """, arrayOf(companion))
        }
        configChanges.incrementAndGet()
        changes.incrementAndGet()
    }

    fun channels(companion: String, currentOnly: Boolean = false, private: Boolean = false): List<HistoryChannel> {
        val visible = if (currentOnly) "slot IS NOT NULL" else """
            (slot IS NOT NULL OR EXISTS (
                SELECT 1 FROM message WHERE message.companion = channel.companion AND message.channel = channel.identity
            ))
        """
        return db.rawQuery("""
            SELECT identity, name, slot FROM channel WHERE companion = ? AND $visible
                AND identity ${if (private) "LIKE" else "NOT LIKE"} '${PRIVATE_ID_PREFIX}%'
            ORDER BY slot IS NULL, slot, name, identity
        """, arrayOf(companion)).use { cursor ->
            cursor.collect { HistoryChannel(getString(0), getString(1), if (isNull(2)) null else getInt(2)) }
        }
    }

    fun append(companion: String, session: String, frames: List<Received>, decoding: ChannelDecoding) {
        val decoded = frames.map { decodeMessage(it.frame, decoding) }
        val database = db
        val inserted = database.inTransaction {
            insertBatch(database, companion, session, frames, decoded)
        }
        if (inserted) changes.incrementAndGet()
    }

    fun appendQueued(companion: String, session: String, item: Received, slots: List<ChannelSlot>,
                     contactsByPrefix: Map<String, List<CompanionContact>>) {
        val database = db
        val queued = QueuedMessage.decode(item.frame.bytes)
        val inserted = database.inTransaction {
            insertBatch(database, companion, session, listOf(item), listOf(null)) { id ->
                if (queued != null) linkQueued(database, companion, id, queued, slots, contactsByPrefix)
            }
        }
        if (inserted) changes.incrementAndGet()
    }

    fun pendingRadio(companion: String, after: Long, through: Long): List<RxRow> =
        db.rawQuery("""
            SELECT id, received_at, frame FROM rx
            WHERE companion = ? AND code = ${CompanionProtocol.RF_LOG} AND id > ? AND id <= ? AND message_id IS NULL
            ORDER BY id LIMIT 128
        """, arrayOf(companion, after.toString(), through.toString())).use { it.collect(::readRx) }

    fun newestId(companion: String): Long =
        db.rawQuery("SELECT id FROM rx WHERE companion = ? ORDER BY id DESC LIMIT 1", arrayOf(companion))
            .use { if (it.moveToFirst()) it.getLong(0) else 0 }

    fun linkDecoded(companion: String, decoded: List<Pair<Long, DecodedMessage>>) {
        if (decoded.isEmpty()) return
        val database = db
        database.inTransaction {
            for ((id, message) in decoded) link(database, companion, id, message)
        }
        changes.incrementAndGet()
    }

    fun journal(companion: String, previous: JournalSnapshot?, before: Long?, error: String?): JournalSnapshot {
        val revision = revision
        if (previous?.fileName == companion && previous.before == before &&
            previous.revision == revision && previous.error == error
        ) return previous
        val database = db
        val raw = radioPage(database, companion, before, false)
        val radio = radioPage(database, companion, before, true)
        val count = database.rawQuery("SELECT rx_count FROM companion WHERE id = ?", arrayOf(companion))
            .use { if (it.moveToFirst()) it.getLong(0) else 0 }
        return JournalSnapshot(companion, count, raw.rows, radio.rows, error, raw.hasOlder, radio.hasOlder, revision, before)
    }

    fun messages(companion: String, identity: String, before: MessageCursor?, limit: Int = CHAT_PAGE_SIZE):
        HistoryPage<ChannelMessage> {
        val args = arrayListOf(companion, identity)
        val boundary = if (before == null) "" else {
            args.addAll(listOf(before.time.toString(), before.id.toString()))
            "AND (sent_at, id) < (?, ?)"
        }
        val clock = channelClock()
        val rows = db.rawQuery("""
            SELECT id, sender, sent_at, body, comparison, receptions, synchronized_count FROM message
            WHERE companion = ? AND channel = ? $boundary
            ORDER BY sent_at DESC, id DESC LIMIT ${limit + 1}
        """, args.toTypedArray()).use { cursor ->
            cursor.collect {
                val time = getLong(2)
                ChannelMessage(getLong(0), getString(1), time, getString(3), clock.format(Instant.ofEpochSecond(time)),
                    getString(4), getLong(5), getLong(6) > 0)
            }
        }
        return HistoryPage(rows.take(limit).asReversed(), rows.size > limit)
    }

    fun prune(now: Long = System.currentTimeMillis()) {
        // Small transactions let ingestion and readers make progress after a long absence.
        val database = db
        database.compileStatement("""
            DELETE FROM rx WHERE id IN (
                SELECT id FROM rx WHERE received_at < ? ORDER BY received_at LIMIT 2000
            )
        """).use { statement ->
            statement.bindLong(1, now - HISTORY_AGE_MS)
            while (true) {
                val deleted = statement.executeUpdateDelete()
                if (deleted > 0) changes.incrementAndGet()
                if (deleted < 2000) break
            }
        }
        database.execSQL("""
            DELETE FROM channel WHERE slot IS NULL AND NOT EXISTS (
                SELECT 1 FROM message WHERE message.companion = channel.companion AND message.channel = channel.identity
            )
        """)
    }

    fun importedOrdinal(session: String): Long =
        db.rawQuery("SELECT ordinal FROM legacy_import WHERE session = ?", arrayOf(session))
            .use { if (it.moveToFirst()) it.getLong(0) else 0 }

    fun importBatch(companion: KnownCompanion, session: String, time: Long, ordinal: Long,
                    frames: List<Received>, decoding: ChannelDecoding) {
        val decoded = frames.map { decodeMessage(it.frame, decoding) }
        val database = db
        database.inTransaction {
            register(database, companion.id, companion.name, time)
            insertBatch(database, companion.id, session, frames, decoded)
            database.execSQL("INSERT OR REPLACE INTO legacy_import VALUES (?, ?)", arrayOf<Any>(session, ordinal))
        }
        changes.incrementAndGet()
    }

    fun importConfiguration(companion: KnownCompanion, time: Long, slots: List<ChannelSlot>) {
        val database = db
        database.inTransaction {
            register(database, companion.id, companion.name, time)
            val latest = database.rawQuery("SELECT last_seen FROM companion WHERE id = ?", arrayOf(companion.id))
                .use { check(it.moveToFirst()); it.getLong(0) }
            if (time == latest && slots.isNotEmpty()) configure(companion.id, slots, complete = true)
        }
    }

    fun finishImport(session: String) {
        db.delete("legacy_import", "session = ?", arrayOf(session))
    }

    private fun radioPage(database: SQLiteDatabase, companion: String, before: Long?, radio: Boolean): HistoryPage<RxRow> {
        val code = if (radio) "AND code = ${CompanionProtocol.RF_LOG}" else ""
        val boundary = if (before == null) "" else "AND id < ?"
        val args = if (before == null) arrayOf(companion) else arrayOf(companion, before.toString())
        val rows = database.rawQuery("""
            SELECT id, received_at, frame FROM rx WHERE companion = ? $code $boundary
            ORDER BY id DESC LIMIT ${LIVE_PAGE_SIZE + 1}
        """, args).use { it.collect(::readRx) }
        return HistoryPage(rows.take(LIVE_PAGE_SIZE), rows.size > LIVE_PAGE_SIZE)
    }

    private fun register(database: SQLiteDatabase, id: String, name: String, time: Long) {
        database.execSQL("INSERT OR IGNORE INTO companion(id, name, last_seen) VALUES (?, ?, ?)", arrayOf<Any>(id, name, time))
        database.execSQL("UPDATE companion SET name = ?, last_seen = ? WHERE id = ? AND last_seen <= ?",
            arrayOf<Any>(name, time, id, time))
    }

    private fun insertBatch(database: SQLiteDatabase, companion: String, session: String,
                            frames: List<Received>, decoded: List<DecodedMessage?>,
                            linkQueued: ((Long) -> Unit)? = null): Boolean {
        var inserted = false
        database.compileStatement("""
            INSERT OR IGNORE INTO rx(companion, session, ordinal, received_at, code, frame) VALUES (?, ?, ?, ?, ?, ?)
        """).use { statement ->
            for ((index, item) in frames.withIndex()) {
                statement.bindString(1, companion)
                statement.bindString(2, session)
                statement.bindLong(3, item.ordinal)
                statement.bindLong(4, item.frame.timeMs)
                item.frame.code?.let { statement.bindLong(5, it.toLong()) } ?: statement.bindNull(5)
                statement.bindBlob(6, item.frame.bytes)
                val id = statement.executeInsert()
                if (id == -1L) continue
                inserted = true
                decoded[index]?.let { link(database, companion, id, it) }
                linkQueued?.invoke(id)
            }
        }
        return inserted
    }

    private fun insertChannel(database: SQLiteDatabase, companion: String, channel: HistoryChannel) {
        database.execSQL("INSERT OR IGNORE INTO channel(companion, identity, name, slot) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>(companion, channel.identity, channel.name, channel.slot))
    }

    private fun link(database: SQLiteDatabase, companion: String, id: Long, message: DecodedMessage) {
        val payload = message.payload
        // A concurrent retention pass or decode may already have handled this reception.
        val pending = database.rawQuery("SELECT 1 FROM rx WHERE id = ? AND companion = ? AND message_id IS NULL",
            arrayOf(id.toString(), companion)).use { it.moveToFirst() }
        if (!pending) return
        val channel = HistoryChannel(payload.channelIdentity, payload.channel, null)
        // Keep exact RF comparison IDs. A queue reply exposes only the decoded fields.
        val exact = messageId(database, companion, channel, message.comparison)
        if (exact != null) {
            database.execSQL("UPDATE rx SET message_id = ? WHERE id = ?", arrayOf(exact, id))
            return
        }
        val originalText = payload.sender?.let { "$it: " }.orEmpty() + payload.text
        val synchronized = messageId(database, companion, channel,
            queuedComparison(payload.timestampSeconds, originalText, null))
        if (synchronized != null) {
            database.execSQL("UPDATE message SET comparison = ? WHERE id = ?",
                arrayOf<Any>(message.comparison, synchronized))
        }
        linkMessage(database, companion, id, channel, payload.sender, payload.timestampSeconds, payload.text,
            message.comparison, synchronized)
    }

    private fun linkQueued(database: SQLiteDatabase, companion: String, id: Long, queued: QueuedMessage,
                           slots: List<ChannelSlot>, contactsByPrefix: Map<String, List<CompanionContact>>) {
        val time = queued.timestampSeconds
        when (val address = queued.address) {
            is MessageAddress.Channel -> {
                val configured = slots.firstOrNull { it.index == address.slot }?.channel
                val channel = if (configured == null) HistoryChannel("slot:${address.slot}",
                    "Unbekannter Kanal (Slot ${address.slot})", null)
                else HistoryChannel(configured.identity, configured.name, null)
                val (sender, body) = splitChannelText(queued.text)
                val existing = matchingMessage(database, companion, channel, sender, time, body)
                val comparison = queuedComparison(time, queued.text, null)
                linkMessage(database, companion, id, channel, sender, time, body, comparison, existing)
            }
            is MessageAddress.Contact -> {
                val contact = contactsByPrefix[address.prefix]?.singleOrNull()
                val identity = if (contact == null) "prefix:${address.prefix}" else contact.publicKey
                val name = contact?.name?.takeIf { it.isNotBlank() } ?: "Unbekannt · ${address.prefix}"
                val channel = HistoryChannel(PRIVATE_ID_PREFIX + identity, name, null)
                val sender = queued.signedSenderPrefix?.let { "Absenderpräfix ${it}" } ?: name
                linkMessage(database, companion, id, channel, sender, time, queued.text,
                    queuedComparison(time, queued.text, queued.signedSenderPrefix))
                if (contact != null) database.execSQL("UPDATE channel SET name = ? WHERE companion = ? AND identity = ?",
                    arrayOf(name, companion, channel.identity))
            }
        }
    }

    private fun matchingMessage(database: SQLiteDatabase, companion: String, channel: HistoryChannel,
                                sender: String?, time: Long, body: String): Long? {
        val senderCondition = if (sender == null) "sender IS NULL" else "sender = ?"
        val args = listOf(companion, channel.identity, time.toString(), body) + listOfNotNull(sender)
        return database.rawQuery("""
            SELECT id FROM message WHERE companion = ? AND channel = ? AND sent_at = ? AND body = ?
            AND $senderCondition ORDER BY id LIMIT 1
        """, args.toTypedArray()).use { if (it.moveToFirst()) it.getLong(0) else null }
    }

    private fun linkMessage(database: SQLiteDatabase, companion: String, rxId: Long, channel: HistoryChannel,
                            sender: String?, time: Long, body: String, comparison: String, existing: Long? = null) {
        val messageId = existing ?: run {
            insertChannel(database, companion, channel)
            val values = ContentValues().apply {
                put("companion", companion)
                put("channel", channel.identity)
                put("comparison", comparison)
                put("sender", sender)
                put("sent_at", time)
                put("body", body)
            }
            val inserted = database.insertWithOnConflict("message", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            if (inserted != -1L) inserted else checkNotNull(messageId(database, companion, channel, comparison))
        }
        database.execSQL("UPDATE rx SET message_id = ? WHERE id = ?", arrayOf(messageId, rxId))
    }

    private fun messageId(database: SQLiteDatabase, companion: String, channel: HistoryChannel, comparison: String): Long? =
        database.rawQuery("SELECT id FROM message WHERE companion = ? AND channel = ? AND comparison = ?",
            arrayOf(companion, channel.identity, comparison),
        ).use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun queuedComparison(time: Long, text: String, signedPrefix: String?): String {
        val fields = "$time\u0000${signedPrefix.orEmpty()}\u0000$text"
        return "queue:" + MessageDigest.getInstance("SHA-256").digest(fields.encodeToByteArray()).toHexString()
    }

    companion object {
        @Volatile private var instance: CompanionHistory? = null

        fun get(context: Context): CompanionHistory = instance ?: synchronized(this) {
            instance ?: CompanionHistory(HistoryDatabase(context.applicationContext)).also { instance = it }
        }
    }
}

internal fun RxFrame.isJournalable(): Boolean = when (code) {
    ChannelQuery.CHANNEL_INFO, ChannelQuery.DEFAULT_FLOOD_SCOPE,
    CompanionProtocol.PRIVATE_KEY_REPLY, CompanionProtocol.Command.DEVICE_QUERY.expectedReply -> false
    else -> true
}

internal fun decodeMessage(frame: RxFrame, decoding: ChannelDecoding): DecodedMessage? {
    if (frame.code != CompanionProtocol.RF_LOG) return null
    val header = frame.bytes.getOrNull(3) ?: return null
    if (MeshHeader(header.toInt() and 0xff).type != PacketType.GRP_TXT) return null
    val packet = decodeRadioRow(RxRow(0, frame), decoding).packet ?: return null
    val payload = packet.payload as? MeshPayload.GroupText ?: return null
    return packet.comparisonId?.let { DecodedMessage(payload, it) }
}

private fun readRx(cursor: Cursor) = RxRow(cursor.getLong(0), RxFrame(cursor.getLong(1), cursor.getBlob(2)))

private inline fun <T> Cursor.collect(row: Cursor.() -> T): List<T> = buildList {
    while (moveToNext()) add(row())
}
