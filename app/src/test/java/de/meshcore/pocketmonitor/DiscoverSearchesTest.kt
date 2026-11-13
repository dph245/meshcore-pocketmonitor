package de.meshcore.pocketmonitor

import de.meshcore.pocketmonitor.protocol.CompanionContact
import de.meshcore.pocketmonitor.protocol.DiscoverPhase
import de.meshcore.pocketmonitor.protocol.DiscoverSearches
import de.meshcore.pocketmonitor.protocol.HopResolver
import de.meshcore.pocketmonitor.protocol.HopStatus
import de.meshcore.pocketmonitor.rx.RxFrame
import de.meshcore.pocketmonitor.rx.JournalSnapshot
import de.meshcore.pocketmonitor.rx.RxRow
import de.meshcore.pocketmonitor.protocol.CompanionProtocol
import de.meshcore.pocketmonitor.ui.LiveSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverSearchesTest {
    private val key = "1020304050607080" + "AB".repeat(24)
    private val keyWithSamePrefix = key.take(16) + "CD".repeat(24)
    private var tag = 0u
    private val searches = DiscoverSearches { ++tag }
    private val runs get() = searches.history.value.runs
    private val latest get() = runs.last()

    @Test
    fun correlatesOnlyValidZeroHopRepeaterRepliesAndKeepsBothSnrDirections() {
        val run = start()
        searches.receive("radio", response(run.tag + 1u), 1_100)
        searches.receive("radio", response(run.tag, header = "2d00"), 1_101) // Flood
        searches.receive("radio", response(run.tag, header = "2e01ff"), 1_102) // Has a hop
        searches.receive("radio", response(run.tag, flags = "91"), 1_103) // Client
        searches.receive("radio", response(run.tag, id = key.dropLast(2)), 1_104)
        searches.receive("other", response(run.tag), 1_105)
        searches.receive("radio", response(run.tag), 999)
        assertTrue(latest.receptions.isEmpty())

        // A notification can precede the write callback and firmware acceptance.
        searches.receive("radio", response(run.tag, timeMs = 2_100), 1_200)
        searches.accept(1_500)
        val row = latest.receptions.single()
        assertEquals(run.tag.toLong(), row.response.tag)
        assertEquals(key, row.response.publicKeyOrPrefix)
        assertEquals(3.75, row.rxSnrDb, 0.0)
        assertEquals(-10.0, row.response.requestSnrDb, 0.0)
        assertEquals(-119, row.rssiDbm)
        assertEquals(200L, row.latencyMs)
        assertEquals(2_100L, row.receivedAtMs)
        assertFalse(row.late)
    }

    @Test
    fun preservesDuplicateMeasurementsAndOtherRepliesFromSameRepeaterInReceptionOrder() {
        val run = start()
        searches.accept(1_000)
        searches.receive("radio", response(run.tag, timeMs = 2_100), 1_100)
        searches.receive("radio", response(run.tag, timeMs = 2_200, rx = "20"), 1_200)
        searches.receive("radio", response(run.tag, timeMs = 2_300, tx = "19"), 1_300)
        searches.receive("radio", response(run.tag, timeMs = 2_400, id = keyWithSamePrefix), 1_400)
        assertEquals(listOf(false, true, false, false), latest.receptions.map { it.duplicate })
        assertEquals(listOf(2_100L, 2_200L, 2_300L, 2_400L), latest.receptions.map { it.receivedAtMs })
        assertEquals(listOf(3.75, 8.0, 3.75, 3.75), latest.receptions.map { it.rxSnrDb })
        assertEquals(listOf(-10.0, -10.0, 6.25, -10.0), latest.receptions.map { it.response.requestSnrDb })
    }

    @Test
    fun lateRepliesStayWithOriginalRunEvenWhileNextRunIsPending() {
        val first = start()
        searches.accept(1_000)
        searches.receive("radio", response(first.tag), 8_999)
        searches.receive("radio", response(first.tag), 9_000)
        searches.complete()
        val second = start(elapsed = 10_000)
        searches.receive("radio", response(first.tag), 10_100)
        searches.accept(10_200)
        searches.receive("radio", response(second.tag), 10_300)
        assertEquals(listOf(false, true, true), runs.first().receptions.map { it.late })
        assertEquals(300L, latest.receptions.single().latencyMs)
        assertFalse(latest.receptions.single().duplicate)
    }

    @Test
    fun resolvesOnlyKnownRepeaterKeysAndMarksAmbiguousPrefixes() {
        val run = start()
        searches.accept(1_000)
        searches.receive("radio", response(run.tag, id = key.take(16)), 1_200)
        searches.receive("radio", response(run.tag, id = keyWithSamePrefix), 1_300)
        searches.receive("radio", response(run.tag, id = "EF".repeat(8)), 1_400)
        fun identities() = latest.receptions.map { latest.resolver.resolve(it.response.publicKeyOrPrefix) }
        assertEquals(List(3) { HopStatus.UNKNOWN }, identities().map { it.status })

        val resolver = HopResolver(listOf(
            contact(key, "A"),
            contact(keyWithSamePrefix, "B"),
            contact("EF".repeat(32), "Client", type = 1),
        ))
        searches.updateResolver("other", resolver)
        assertEquals(List(3) { HopStatus.UNKNOWN }, identities().map { it.status })
        searches.updateResolver("radio", resolver)
        val resolved = identities()
        assertEquals(listOf(HopStatus.AMBIGUOUS, HopStatus.UNIQUE, HopStatus.UNKNOWN), resolved.map { it.status })
        assertEquals("B", resolved[1].matches.single().name)
        assertEquals("EF".repeat(8), resolved[2].prefix)
    }

    @Test
    fun failedRequestDiscardsUnconfirmedMatchesAndRespectsCooldown() {
        val run = start()
        searches.receive("radio", response(run.tag), 1_100)
        searches.fail("Paketpuffer voll")
        searches.receive("radio", response(run.tag), 1_200)
        assertEquals(DiscoverPhase.FAILED, latest.phase)
        assertTrue(latest.receptions.isEmpty())
        searches.endCooldown(2_999)
        assertTrue(searches.history.value.coolingDown)
        assertNull(searches.start("radio", 2_999, 2_999, HopResolver.EMPTY))
        searches.endCooldown(3_000)
        assertFalse(searches.history.value.coolingDown)
        assertNotNull(start(elapsed = 3_000))
        assertEquals(2, runs.size)
    }

    @Test
    fun activeRunRemainsLockedBeyondCooldownAndDisconnectPreservesConfirmedReplies() {
        val run = start()
        searches.accept(1_050)
        searches.receive("radio", response(run.tag), 1_500)
        searches.endCooldown(3_000)
        assertNull(searches.start("radio", 3_500, 3_500, HopResolver.EMPTY))
        searches.disconnect("radio", "GATT getrennt")
        searches.receive("radio", response(run.tag), 3_600)
        assertEquals(DiscoverPhase.DISCONNECTED, latest.phase)
        assertEquals("GATT getrennt", latest.message)
        assertEquals(1, latest.receptions.size)

        val next = start(journal = "other", elapsed = 4_000)
        searches.accept(4_100)
        searches.receive("other", response(run.tag), 4_300)
        searches.receive("other", response(next.tag), 4_400)
        assertEquals(1, runs.first().receptions.size)
        assertEquals(next.tag.toLong(), latest.receptions.single().response.tag)
    }

    @Test
    fun disconnectBeforeCompanionConfirmationEndsPendingRun() {
        val run = start()
        searches.receive("radio", response(run.tag), 1_100)
        searches.disconnect("radio", "Getrennt")
        assertEquals(DiscoverPhase.DISCONNECTED, latest.phase)
        assertTrue(latest.receptions.isEmpty())
        assertFalse(searches.history.value.busy)
    }

    @Test
    fun tagsAreNotReusedAcrossRuns() {
        val tags = listOf(7u, 7u, 8u).iterator()
        val history = DiscoverSearches { tags.next() }
        assertEquals(7u, history.start("radio", 1_000, 1_000, HopResolver.EMPTY)?.tag)
        history.fail("Abgelehnt")
        assertEquals(8u, history.start("radio", 3_000, 3_000, HopResolver.EMPTY)?.tag)
    }

    @Test
    fun discoverProcessingKeepsUnmatchedRepliesInLiveView() {
        val run = start()
        searches.accept(1_000)
        val matching = response(run.tag).bytes
        val unmatched = response(run.tag + 1u).bytes
        val samples = listOf("00".hexToByteArray(), matching, matching, unmatched,
            "8e0f89".hexToByteArray(), "0103".hexToByteArray(), "88ff".hexToByteArray(), byteArrayOf())
        val rows = samples.mapIndexed { index, bytes ->
            val frame = RxFrame(index.toLong(), bytes)
            searches.receive("radio", frame, 1_100L + index)
            RxRow(index + 1L, frame)
        }.asReversed()
        assertEquals(2, latest.receptions.size)
        val snapshot = JournalSnapshot("radio", rows.size.toLong(), rows,
            rows.filter { it.code == CompanionProtocol.RF_LOG }, null)
        val live = LiveSnapshot.update(snapshot, null)
        assertEquals(listOf(7L, 4L, 3L, 2L), live.radioRows.map { it.row.original.sequence })
    }

    private fun start(journal: String = "radio", elapsed: Long = 1_000) =
        checkNotNull(searches.start(journal, 2_000, elapsed, HopResolver.EMPTY))

    private fun contact(id: String, name: String, type: Int = 2) =
        CompanionContact(id, name, type, 0, 0, "", 0, 0.0, 0.0, 0)

    private fun response(
        tag: UInt, id: String = key, timeMs: Long = 2_000, header: String = "2e00",
        flags: String = "92", rx: String = "0f", tx: String = "d8",
    ): RxFrame {
        val tagBytes = byteArrayOf(tag.toByte(), (tag shr 8).toByte(), (tag shr 16).toByte(), (tag shr 24).toByte())
        return RxFrame(timeMs, "88${rx}89${header}${flags}$tx".hexToByteArray() + tagBytes + id.hexToByteArray())
    }
}
