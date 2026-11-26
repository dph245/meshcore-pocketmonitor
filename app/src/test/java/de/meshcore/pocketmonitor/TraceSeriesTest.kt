package de.meshcore.pocketmonitor

import de.meshcore.pocketmonitor.protocol.CompanionProtocol
import de.meshcore.pocketmonitor.protocol.TraceMeasurement
import de.meshcore.pocketmonitor.protocol.TracePhase
import de.meshcore.pocketmonitor.protocol.TraceRadio
import de.meshcore.pocketmonitor.protocol.TraceRoute
import de.meshcore.pocketmonitor.protocol.TraceSchedule
import de.meshcore.pocketmonitor.protocol.TraceSnrStats
import de.meshcore.pocketmonitor.protocol.TraceSummary
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceSeriesTest {
    @Test
    fun preservesEntryOrderAndLabelsDirectionsByPosition() {
        val mirrored = TraceRoute(2, listOf("abcd" + "0".repeat(60), "4321", "BCDE"), true)
        assertEquals(listOf("ABCD", "4321", "BCDE", "4321", "ABCD"), mirrored.path?.hashes)
        assertEquals("Hinweg 3", mirrored.hopLabel(2))
        assertEquals("Rückweg 1", mirrored.hopLabel(3))

        val explicit = TraceRoute(1, listOf("AB", "CD", "EF", "01"), false, turn = 2)
        assertEquals(listOf("AB", "CD", "EF", "01"), explicit.path?.hashes)
        assertEquals("Rückweg 2", explicit.hopLabel(3))
        assertEquals("Hop 4 · Richtung unbekannt", explicit.copy(turn = null).hopLabel(3))
    }

    @Test
    fun readsOnlyAvailableRadioFieldsFromMatchingIdentity() {
        val self = ByteArray(58).apply {
            this[0] = 5
            this[2] = -3
            this[4] = 42
            ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(48, 869_525)
                .putInt(52, 62_500).put(56, 7).put(57, 6)
        }
        val id = CompanionProtocol.selfIdentity(self)!!
        val device = TraceRadio.fromDeviceInfo(ByteArray(82).apply {
            this[0] = 13
            this[1] = 14
            "v1.18.0".encodeToByteArray().copyInto(this, 60)
        })
        assertEquals(TraceRadio(869525, 62500, 7, 6, -3, "v1.18.0", 14), device.withSelfInfo(self, id))
        assertEquals(device, device.withSelfInfo(self, "other"))
        assertEquals(device, device.withSelfInfo(byteArrayOf(5), id))
        assertEquals(device.copy(powerDbm = -3), device.withSelfInfo(self.copyOf(49), id))
        assertEquals(TraceRadio(), TraceRadio.fromDeviceInfo(byteArrayOf(13)))
        assertNull(device.radioIc)
    }

    @Test
    fun appliesTheStartIntervalAndFirmwareWindowAfterTimeout() {
        assertEquals(31_000L, TraceSchedule.nextStartMs(1_000, 30_000, TracePhase.ANSWERED, 50_000))
        assertEquals(50_000L, TraceSchedule.nextStartMs(1_000, 30_000, TracePhase.TIMED_OUT, 50_000))
        assertTrue(TraceSchedule.continuesAfter(TracePhase.TIMED_OUT))
        assertTrue(TraceSchedule.continuesAfter(TracePhase.ANSWERED))
        for (phase in listOf(TracePhase.FAILED, TracePhase.CANCELLED, TracePhase.DISCONNECTED)) {
            assertFalse(TraceSchedule.continuesAfter(phase))
        }
    }

    @Test
    fun reportsStatisticsSeparatelyForRepeatedHashesAndFinalReception() {
        val measurements = listOf(
            result(1, TracePhase.ANSWERED, listOf(-2.0, 8.0, -8.0, 1.0)),
            result(2, TracePhase.ANSWERED, listOf(-1.75, 6.0, -4.0, 3.0)),
            result(3, TracePhase.TIMED_OUT, emptyList()),
            result(4, TracePhase.FAILED, emptyList()),
            result(5, TracePhase.DISCONNECTED, emptyList()),
        )
        val summary = TraceSummary(measurements, 3)
        assertEquals(5, summary.attempts)
        assertEquals(2, summary.answered)
        assertEquals(1, summary.timeouts)
        assertEquals(1, summary.errors)
        assertEquals(1, summary.aborted)
        assertEquals(40.0, summary.rate!!, 0.0)
        assertEquals(listOf(
            TraceSnrStats(-2.0, -1.875, -1.75), TraceSnrStats(6.0, 7.0, 8.0),
            TraceSnrStats(-8.0, -6.0, -4.0), TraceSnrStats(1.0, 2.0, 3.0),
        ), summary.snr)
        assertEquals(List(4) { null }, TraceSummary(emptyList(), 3).snr)
        assertNull(TraceSummary(emptyList(), 3).rate)
    }

    private fun result(index: Int, phase: TracePhase, snr: List<Double>) =
        TraceMeasurement(index, 0, phase, phase == TracePhase.ANSWERED, null, snr, null)
}
