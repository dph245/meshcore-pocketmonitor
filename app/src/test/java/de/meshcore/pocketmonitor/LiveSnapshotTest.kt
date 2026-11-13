package de.meshcore.pocketmonitor

import de.meshcore.pocketmonitor.protocol.HopResolver
import de.meshcore.pocketmonitor.rx.JournalSnapshot
import de.meshcore.pocketmonitor.rx.RxFrame
import de.meshcore.pocketmonitor.rx.RxRow
import de.meshcore.pocketmonitor.ui.LiveSnapshot
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LiveSnapshotTest {
    @Test
    fun reusesDecodedRowsAcrossRawOnlyUpdatesAndRollingWindow() {
        val first = LiveSnapshot.update(snapshot(3, listOf(row(3), row(1))), null)
        val rawOnly = LiveSnapshot.update(snapshot(4, listOf(row(3), row(1))), first)
        assertSame(first.radioRows, rawOnly.radioRows)
        assertEquals(4L, rawOnly.journal.count)

        val rolled = LiveSnapshot.update(snapshot(5, listOf(row(5), row(3))), rawOnly)
        assertEquals(listOf(5L, 3L), rolled.radioRows.map { it.row.original.sequence })
        assertSame(first.radioRow(3), rolled.radioRow(3))
        assertNull(rolled.radioRow(1))

        val skipped = LiveSnapshot.update(snapshot(9, listOf(row(9), row(8))), rolled)
        assertEquals(listOf(9L, 8L), skipped.radioRows.map { it.row.original.sequence })
        val switched = LiveSnapshot.update(skipped.journal.copy(fileName = "other"), skipped)
        assertNotSame(skipped.radioRow(9)?.row, switched.radioRow(9)?.row)
    }

    @Test
    fun presentationChangesRetainTheDecodedPacketAndOriginalBytes() {
        val frame = "8800b0150011ac636130151a26a9a6710a2cad0bb9a5b9d8b073aa850126e87c47509a61a47bf107".hexToByteArray()
        val radio = RxRow(1, RxFrame(0, frame))
        val journal = snapshot(1, listOf(radio))
        val first = LiveSnapshot.update(journal, null, zone = ZoneOffset.UTC)
        val otherResolver = HopResolver(emptyList())
        val contacts = LiveSnapshot.update(journal, first, resolver = otherResolver, zone = ZoneOffset.UTC)
        val timeZone = LiveSnapshot.update(journal, contacts, resolver = otherResolver, zone = ZoneOffset.ofHours(1))
        assertSame(first.radioRow(1)?.row, contacts.radioRow(1)?.row)
        assertSame(first.radioRow(1)?.row, timeZone.radioRow(1)?.row)
        assertSame(frame, timeZone.radioRow(1)?.row?.original?.frame?.bytes)
        assertEquals("00:00:00", first.radioRow(1)?.display?.time)
        assertEquals("01:00:00", timeZone.radioRow(1)?.display?.time)
    }

    @Test
    fun retentionAndPagingCannotReuseTheWrongPacket() {
        val initial = LiveSnapshot.update(snapshot(6, listOf(row(6), row(5), row(4), row(3))), null)
        val pruned = LiveSnapshot.update(snapshot(5, listOf(row(7), row(6), row(4), row(3))), initial)
        assertEquals(listOf(7L, 6L, 4L, 3L), pruned.radioRows.map { it.row.original.sequence })
        assertSame(initial.radioRow(4), pruned.radioRow(4))
        val older = LiveSnapshot.update(snapshot(5, listOf(row(2), row(1))), pruned)
        assertEquals(listOf(2L, 1L), older.radioRows.map { it.row.original.sequence })
    }

    private fun row(sequence: Long) = RxRow(sequence, RxFrame(0, byteArrayOf(0x88.toByte())))

    private fun snapshot(count: Long, rows: List<RxRow>) = JournalSnapshot("rx", count, rows, rows, null)
}
