package de.meshcore.pocketmonitor

import de.meshcore.pocketmonitor.rx.RxFrame
import de.meshcore.pocketmonitor.rx.RxJournal
import de.meshcore.pocketmonitor.ui.LiveSnapshot
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RxJournalTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun burstSurvivesCloseAndUiLimitByteForByte() = runBlocking {
        val journal = RxJournal(temp.root) { throw AssertionError(it) }
        val samples = List(5_000) { i ->
            when (i % 4) {
                0 -> byteArrayOf()
                1 -> byteArrayOf(0x88.toByte(), 0x00, 0xff.toByte(), 0x80.toByte())
                2 -> byteArrayOf(0x0d, 3, 0)
                else -> ByteArray(176) { (i + it).toByte() }
            }
        }
        samples.forEachIndexed { index, bytes ->
            assertTrue(journal.offer(RxFrame(index.toLong(), bytes)))
        }
        journal.close()
        withTimeout(10_000) { journal.completion.join() }

        val lines = journal.file.readLines()
        assertEquals("sequence\tepoch_ms\tframe_hex", lines.first())
        assertEquals(samples.size + 1, lines.size)
        samples.forEachIndexed { index, bytes ->
            val hex = bytes.joinToString("") { "%02X".format(it) }
            assertEquals("${index + 1}\t$index\t$hex", lines[index + 1])
        }
        val snapshot = journal.snapshot(null)
        assertEquals(5_000L, snapshot.count)
        assertEquals((5_000L downTo 4_501L).toList(), snapshot.newestFirst.map { it.sequence })
        assertSame(snapshot, journal.snapshot(snapshot))
        val radio = LiveSnapshot.update(snapshot, null)
        assertEquals(500, radio.radioRows.size)
        assertSame(radio, LiveSnapshot.update(snapshot, radio))
        radio.radioRows.forEach { item ->
            val row = item.row
            val index = (row.original.sequence - 1).toInt()
            assertArrayEquals(samples[index], row.original.frame.bytes)
            assertEquals(index.toLong(), row.original.timeMs)
            assertNotNull(row.packet?.issue)
        }
    }

    @Test
    fun inaccessibleJournalReportsFailure() = runBlocking {
        val blocked = temp.newFile()
        var failure: String? = null
        val journal = RxJournal(File(blocked, "rx")) { failure = it }
        withTimeout(10_000) { journal.completion.join() }

        assertNotNull(failure)
        assertEquals(failure, journal.snapshot(null).error)
        assertTrue(!journal.offer(RxFrame(0, byteArrayOf(1))))
    }
}
