package de.meshcore.pocketmonitor

import de.meshcore.pocketmonitor.ble.ChannelCatalog
import de.meshcore.pocketmonitor.protocol.ChannelSlot
import de.meshcore.pocketmonitor.protocol.RadioChannel
import de.meshcore.pocketmonitor.rx.RxFrame
import de.meshcore.pocketmonitor.rx.RxJournal
import de.meshcore.pocketmonitor.ui.ChannelHistory
import de.meshcore.pocketmonitor.ui.ChannelSelection
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChannelHistoryTest {
    private val public = ChannelSlot(0, RadioChannel.builtIn)
    // Same name and one-byte on-air hash as Public, different key and channel identity.
    private val private = ChannelSlot(1, RadioChannel("Public", "fbaf59884c3c5daa692bf27ecc579f12".hexToByteArray()))
    private val empty = ChannelSlot(2, RadioChannel("Leer", ByteArray(16) { 1 }))
    private val publicFrame =
        "8800b0150011ac636130151a26a9a6710a2cad0bb9a5b9d8b073aa850126e87c47509a61a47bf107".hexToByteArray()
    private val forwardedPublicFrame =
        "8800b015012a".hexToByteArray() + publicFrame.copyOfRange(5, publicFrame.size)
    private val olderPublicFrame = "8800b01500117ec0e0a178ec50d4dcde729a9ec18ea15f45".hexToByteArray()
    private val privateFrame = "8800b01450f500000011805bef37f4455929c6341abd97900f030449".hexToByteArray()

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun readsBeyondLiveWindowAndAppendsInMessageTimestampOrder() = runBlocking {
        val journal = journal()
        try {
            journal.offerFrame(publicFrame)
            repeat(501) { journal.offerFrame(byteArrayOf(0x88.toByte())) }
            journal.offerFrame(privateFrame)
            journal.offerFrame(publicFrame.copyOf().apply { this[lastIndex] = 0 })
            journal.awaitCount(504)
            val catalog = ChannelCatalog(journal.name, listOf(public, private, empty), null)
            val initial = ChannelHistory.update(journal, catalog, null)
            assertEquals(listOf(1L), initial.content.messagesFor(public).map { it.sequence })
            assertEquals(1L, initial.content.messagesFor(public).single().receptionCount)
            assertEquals("Test", initial.content.messagesFor(private).single().text)
            assertTrue(initial.content.messagesFor(empty).isEmpty())

            journal.offerFrame(byteArrayOf(0x00))
            journal.awaitCount(505)
            val advanced = ChannelHistory.update(journal, catalog, initial)
            assertSame(initial.content, advanced.content)

            journal.offerFrame(olderPublicFrame)
            journal.offerFrame(forwardedPublicFrame)
            journal.offerFrame(publicFrame)
            journal.awaitCount(508)
            val updated = ChannelHistory.update(journal, catalog, advanced)
            assertEquals(listOf(506L, 1L), updated.content.messagesFor(public).map { it.sequence })
            assertEquals(listOf(123L, 0xf2345678L), updated.content.messagesFor(public).map { it.timestampSeconds })
            assertEquals(listOf(1L, 3L), updated.content.messagesFor(public).map { it.receptionCount })
            assertEquals(listOf(503L), updated.content.messagesFor(private).map { it.sequence })
            assertSame(initial.content.messagesFor(private), updated.content.messagesFor(private))
            val rebuilt = ChannelHistory.update(journal, catalog, null)
            assertEquals(updated.content.messagesFor(public), rebuilt.content.messagesFor(public))
            assertSame(updated, ChannelHistory.update(journal, catalog, updated))
        } finally {
            journal.close()
            journal.completion.join()
        }
    }

    @Test
    fun resolvesMovesByKeyAndRebuildsForChangedKeysOrCompanion() = runBlocking {
        val journal = journal()
        val other = journal()
        try {
            journal.offerFrame(publicFrame)
            journal.offerFrame(privateFrame)
            journal.awaitCount(2)
            val initial = ChannelHistory.update(journal, ChannelCatalog(journal.name, listOf(public), null), null)

            val moved = private.copy(index = 0)
            val updated = ChannelHistory.update(journal, ChannelCatalog(journal.name, listOf(moved), null), initial)
            assertEquals("Olaf", updated.content.messagesFor(moved).single().sender)
            assertTrue(updated.content.messagesFor(public).isEmpty())
            assertEquals(moved, ChannelSelection(private.index, private.channel.identity).resolve(listOf(moved)))
            assertNull(ChannelSelection(public.index, public.channel.identity).resolve(listOf(moved)))

            val switched = ChannelHistory.update(other, ChannelCatalog(other.name, listOf(private), null), updated)
            assertTrue(switched.content.messagesFor(private).isEmpty())
        } finally {
            journal.close()
            other.close()
            journal.completion.join()
            other.completion.join()
        }
    }

    private fun journal() = RxJournal(temp.newFolder()) { error(it) }

    private fun RxJournal.offerFrame(bytes: ByteArray) {
        assertTrue(offer(RxFrame(0, bytes)))
    }

    private suspend fun RxJournal.awaitCount(count: Long) = withTimeout(5_000) {
        while (snapshot(null).count < count) delay(10)
    }
}
