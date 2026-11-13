package de.meshcore.pocketmonitor

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import de.meshcore.pocketmonitor.protocol.ChannelDecoding
import de.meshcore.pocketmonitor.protocol.ChannelSlot
import de.meshcore.pocketmonitor.protocol.CompanionContact
import java.nio.ByteBuffer
import java.nio.ByteOrder
import de.meshcore.pocketmonitor.protocol.RadioChannel
import de.meshcore.pocketmonitor.rx.CHAT_PAGE_SIZE
import de.meshcore.pocketmonitor.rx.CompanionHistory
import de.meshcore.pocketmonitor.rx.HISTORY_AGE_MS
import de.meshcore.pocketmonitor.rx.HistoryDatabase
import de.meshcore.pocketmonitor.rx.KnownCompanion
import de.meshcore.pocketmonitor.rx.LegacyJournalImport
import de.meshcore.pocketmonitor.rx.MessageCursor
import de.meshcore.pocketmonitor.rx.Received
import de.meshcore.pocketmonitor.rx.ReceptionWriter
import de.meshcore.pocketmonitor.rx.RxFrame
import de.meshcore.pocketmonitor.rx.decodeMessage
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class HistoryInstrumentation : Instrumentation() {
    private val public = RadioChannel.builtIn
    private val privateKey = "fbaf59884c3c5daa692bf27ecc579f12".hexToByteArray()
    private val private = RadioChannel("Public", privateKey)
    private val publicFrame =
        "8800b0150011ac636130151a26a9a6710a2cad0bb9a5b9d8b073aa850126e87c47509a61a47bf107".hexToByteArray()
    private val privateFrame = "8800b01450f500000011805bef37f4455929c6341abd97900f030449".hexToByteArray()
    private val id = "aa".repeat(32)
    private val other = "bb".repeat(32)
    private val now = 1_800_000_000_000L

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val name = "history-test-${UUID.randomUUID()}.db"
        val legacy = File(targetContext.cacheDir, UUID.randomUUID().toString()).apply { mkdir() }
        val result = Bundle()
        val code = try {
            runBlocking {
                HistoryDatabase(targetContext, name).use { db ->
                    verifyWriteAndPaging(CompanionHistory(db))
                }
                HistoryDatabase(targetContext, name).use { db ->
                    val history = CompanionHistory(db)
                    verifyReopenAndRetention(history)
                    verifyLegacyImport(history, legacy)
                }
                verifyMessageSync()
            }
            result.putString("stream", "\nPASS: SQLite reopen, companion isolation, slots, deduplication, retention, legacy import, queue synchronization, private messages and paging\n")
            Activity.RESULT_OK
        } catch (e: Throwable) {
            result.putString("stream", "\n${e.stackTraceToString()}\n")
            Activity.RESULT_CANCELED
        } finally {
            targetContext.deleteDatabase(name)
            legacy.deleteRecursively()
        }
        finish(code, result)
    }

    private suspend fun verifyWriteAndPaging(history: CompanionHistory) {
        val writer = ReceptionWriter(history, onIdentified = {}, onError = { error(it) })
        val cutoff = now - HISTORY_AGE_MS
        val forwarding = "8800b015012a".hexToByteArray() + publicFrame.copyOfRange(5, publicFrame.size)
        check(writer.offer(RxFrame(cutoff - 1, publicFrame)))
        check(writer.identify(KnownCompanion(id, "Gleicher Name")))
        check(writer.offer(RxFrame(cutoff, forwarding)))
        check(writer.offer(RxFrame(now, privateFrame)))
        check(writer.offer(RxFrame(now, byteArrayOf(0x12) + privateKey)))
        check(writer.configure(listOf(ChannelSlot(0, public), ChannelSlot(1, private)), true))
        check(writer.configure(listOf(ChannelSlot(3, RadioChannel("Umbenannt", privateKey))), true))
        writer.close()
        withTimeout(10_000) { writer.completion.join() }

        history.register(other, "Gleicher Name", now)
        val samples = listOf(Received(1, RxFrame(now, publicFrame)))
        repeat(2) { history.append(other, "other", samples, ChannelDecoding.default) }
        check(history.messages(other, public.identity, null).rows.single().receptionCount == 1L)
        check(history.messages(id, public.identity, null).rows.single().receptionCount == 2L)
        check(history.messages(other, private.identity, null).rows.isEmpty())
        val journal = history.journal(id, null, null, null)
        check(journal.count == 3L)
        check(journal.newestFirst.map { it.frame.bytes.toHexString() } ==
            listOf(privateFrame, forwarding, publicFrame).map { it.toHexString() })
        val privateMessage = history.messages(id, private.identity, null).rows.single()
        check(privateMessage.sender == "Olaf" && privateMessage.text == "Test")
        check(history.channels(id).first { it.identity == private.identity }.let { it.slot == 3 && it.name == "Umbenannt" })
        check(history.channels(id).first { it.identity == public.identity }.slot == null)

        // Equal sender timestamps require a stable secondary key at the page boundary.
        val decoded = checkNotNull(decodeMessage(RxFrame(now, publicFrame), ChannelDecoding.default))
        for (start in 0 until 50_000 step 500) {
            val batch = List(500) { Received((start + it + 1).toLong(), RxFrame(now, byteArrayOf(0x88.toByte()))) }
            history.append(other, "large", batch, ChannelDecoding.default)
            val rx = history.journal(other, null, null, null).newestRadioFirst
            history.linkDecoded(other, rx.map {
                it.sequence to decoded.copy(comparison = "synthetic-${it.sequence}")
            })
        }
        val page = history.journal(other, null, null, null)
        val older = history.journal(other, page, page.newestRadioFirst.last().sequence, null)
        check(page.count == 50_001L && page.hasOlderRadio && older.hasOlderRadio)
        check(page.newestRadioFirst.size == 500 && older.newestRadioFirst.size == 500)
        check(older.newestRadioFirst.first().sequence < page.newestRadioFirst.last().sequence)
        val chat = history.messages(other, public.identity, null)
        val tail = chat.rows.first()
        val olderChat = history.messages(other, public.identity, MessageCursor(tail.timestampSeconds, tail.sequence))
        check(chat.rows.size == CHAT_PAGE_SIZE && chat.hasOlder && olderChat.hasOlder)
        check(olderChat.rows.last().sequence < tail.sequence)
    }

    private fun verifyReopenAndRetention(history: CompanionHistory) {
        check(history.knownCompanions().map { it.id }.toSet() == setOf(id, other))
        check(history.messages(id, private.identity, null).rows.single().text == "Test")
        history.prune(now)
        check(history.journal(id, null, null, null).count == 2L)
        check(history.messages(id, public.identity, null).rows.single().receptionCount == 1L)
        history.prune(now + 1)
        check(history.messages(id, public.identity, null).rows.isEmpty())
        history.prune(now + HISTORY_AGE_MS + 1)
        check(history.journal(id, null, null, null).count == 0L)
        check(history.knownCompanions().size == 2)
        check(history.channels(id).single().identity == private.identity)
    }

    private suspend fun verifyMessageSync() {
        val file = "sync-test-${UUID.randomUUID()}.db"
        val prefix = "ABCDEF012345"
        val contactKey = prefix + "67".repeat(26)
        val contact = CompanionContact(contactKey, "Ada", 1, 0, 0, "", 0, 0.0, 0.0, 0)
        val contacts = mapOf(prefix to listOf(contact))
        val slot = listOf(ChannelSlot(4, public))
        val decoded = checkNotNull(decodeMessage(RxFrame(now, publicFrame), ChannelDecoding.default))
        val payload = decoded.payload
        val timestamp = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(payload.timestampSeconds.toInt()).array()
        val sender = payload.sender?.let { "$it: " }.orEmpty()
        val channel = "11e4000004ff00".hexToByteArray() + timestamp + (sender + payload.text).encodeToByteArray()
        val pm = "10f40000${prefix}ff00".hexToByteArray() + timestamp + "Privat".encodeToByteArray()
        val cutoff = now - HISTORY_AGE_MS
        try {
            HistoryDatabase(targetContext, file).use { db ->
                val history = CompanionHistory(db)
                val writer = ReceptionWriter(history, onIdentified = {}, onError = { error(it) })
                var committed = 0
                check(writer.identify(KnownCompanion(id, "Companion")))
                check(writer.offerQueued(RxFrame(cutoff, channel), slot, contacts) { committed++ })
                check(writer.offer(RxFrame(cutoff, publicFrame)))
                check(writer.offerQueued(RxFrame(now, pm), slot, contacts) { committed++ })
                writer.close() // Disconnect before all callbacks finish.
                withTimeout(10_000) { writer.completion.join() }
                check(committed == 2)
                check(history.messages(id, public.identity, null).rows.single().let {
                    it.sequence > 0 && it.text == payload.text && it.synchronized && it.receptionCount == 1L
                })
            }
            HistoryDatabase(targetContext, file).use { db ->
                val history = CompanionHistory(db)
                check(history.messages(id, "pm:$contactKey", null).rows.single().let {
                    it.sender == "Ada" && it.text == "Privat" && it.receptionCount == 0L && it.synchronized
                })
                val writer = ReceptionWriter(history, onIdentified = {}, onError = { error(it) })
                check(writer.identify(KnownCompanion(id, "Companion")))
                for (frame in listOf(publicFrame, channel, pm)) {
                    if (frame === publicFrame) check(writer.offer(RxFrame(now, frame)))
                    else check(writer.offerQueued(RxFrame(now, frame), slot, contacts) {})
                }
                val collision = contact.copy(publicKey = prefix + "89".repeat(26))
                val ambiguous = contacts + (prefix to listOf(contact, collision))
                check(writer.offerQueued(RxFrame(now, pm), slot, ambiguous) {})
                val unknown = pm.copyOf().also { it[4] = 0x12 }
                check(writer.offerQueued(RxFrame(now, unknown), slot, contacts) {})
                check(writer.offerQueued(RxFrame(now, channel.copyOf().also { it[4] = 5 }), slot, contacts) {})
                repeat(CHAT_PAGE_SIZE + 1) { index ->
                    val entry = pm.copyOf(16) + "Seite $index".encodeToByteArray()
                    check(writer.offerQueued(RxFrame(now, entry), slot, contacts) {})
                }
                writer.close()
                withTimeout(10_000) { writer.completion.join() }
                val privatePage = history.messages(id, "pm:$contactKey", null)
                val first = privatePage.rows.first()
                check(privatePage.rows.size == CHAT_PAGE_SIZE && privatePage.hasOlder)
                check(history.messages(id, "pm:$contactKey", MessageCursor(first.timestampSeconds, first.sequence))
                    .rows.map { it.text } == listOf("Privat", "Seite 0"))
                check(history.channels(id, private = true).map { it.identity }.toSet() ==
                    setOf("pm:$contactKey", "pm:prefix:$prefix", "pm:prefix:12CDEF012345"))
                check(history.channels(id).map { it.identity }.toSet() == setOf(public.identity, "slot:5"))
                check(history.messages(id, public.identity, null).rows.single().receptionCount == 2L)
                history.register(other, "Companion", now)
                history.appendQueued(other, "other-sync", Received(1, RxFrame(cutoff, pm)), slot, emptyMap())
                check(history.channels(other, private = true).single().identity == "pm:prefix:$prefix")
                check(history.messages(other, "pm:$contactKey", null).rows.isEmpty())
                history.prune(now + 1)
                check(history.channels(other, private = true).isEmpty())
                check(history.messages(id, public.identity, null).rows.single().let {
                    it.receptionCount == 1L && it.synchronized
                })
                history.prune(now + HISTORY_AGE_MS + 1)
                check(history.channels(id, private = true).isEmpty() && history.channels(id).isEmpty())
            }
        } finally {
            targetContext.deleteDatabase(file)
        }
    }

    private suspend fun verifyLegacyImport(history: CompanionHistory, legacy: File) {
        val file = File(legacy, "rx-$now-11111111-1111-1111-1111-111111111111.tsv")
        val key = "cc".repeat(32)
        val self = ("05021214" + key).hexToByteArray()
        val slot = byteArrayOf(0x12, 7) + "Public".encodeToByteArray().copyOf(32) + privateKey
        val rows = listOf(self, slot, privateFrame, byteArrayOf(), byteArrayOf(0x0d, 3, 0, 0, 1, 2, 3, 4))
        val content = buildString {
            appendLine("sequence\tepoch_ms\tframe_hex")
            rows.forEachIndexed { index, bytes -> appendLine("${index + 1}\t$now\t${bytes.toHexString()}") }
        }
        file.writeText(content)
        check(file.setLastModified(now))
        // Simulate interruption after the first committed batch and before source removal.
        history.importBatch(KnownCompanion(key, "Altgerät"), file.name, now, 1,
            listOf(Received(1, RxFrame(now, self))), ChannelDecoding.default)
        repeat(2) { LegacyJournalImport(history, legacy).run(now) }
        check(!file.exists())
        val journal = history.journal(key, null, null, null)
        check(journal.count == 3L && journal.newestFirst.first().frame.bytes.isEmpty())
        check(journal.newestRadioFirst.single().frame.bytes.contentEquals(privateFrame))
        check(history.messages(key, private.identity, null).rows.single().receptionCount == 1L)
        check(history.channels(key).single().slot == 7)
    }
}
