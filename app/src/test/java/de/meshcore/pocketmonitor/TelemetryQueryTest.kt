package de.meshcore.pocketmonitor

import de.meshcore.pocketmonitor.protocol.TelemetryMetric
import de.meshcore.pocketmonitor.protocol.TelemetryQuery
import de.meshcore.pocketmonitor.ui.formatUptime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryQueryTest {
    @Test
    fun readsBatteryInMillivoltsFromLegacyAndCurrentReplies() {
        val query = TelemetryQuery(TelemetryMetric.BATTERY)
        assertArrayEquals("14".hexToByteArray(), query.command)
        assertEquals(3987L, query.value("0C930F".hexToByteArray()))
        assertEquals(3987L, query.value("0C930F0100000002000000".hexToByteArray()))
        assertEquals(65534L, query.value("0CFEFF".hexToByteArray()))
        assertNull(query.value("0C00".hexToByteArray()))
        assertNull(query.value("0C0000".hexToByteArray()))
    }

    @Test
    fun readsSignedNoiseFloorAndIgnoresOtherStatsAndPushes() {
        val query = TelemetryQuery(TelemetryMetric.NOISE_FLOOR)
        assertArrayEquals("3801".hexToByteArray(), query.command)
        query.receive("8800AB".hexToByteArray())
        query.receive("1800930F".hexToByteArray())
        val reply = "18018DFFB01C0000000000000000".hexToByteArray()
        query.receive(reply)
        assertNull(query.result)
        query.acknowledgeWrite()
        assertArrayEquals(reply, query.result)
        assertEquals(-115L, query.value(reply))
        assertNull(query.value("18018D".hexToByteArray()))
        assertNull(query.value("18010000".hexToByteArray()))
        assertNull(query.value("18028DFF".hexToByteArray()))
    }

    @Test
    fun readsCoreUptimeAsUnsignedSecondsAndFormatsWholeMinutes() {
        val query = TelemetryQuery(TelemetryMetric.UPTIME)
        assertArrayEquals("3800".hexToByteArray(), query.command)
        query.acknowledgeWrite()
        val radio = "18018DFFB01C0000000000000000".hexToByteArray()
        query.receive(radio)
        assertNull(query.result)
        assertNull(query.value(radio))

        val core = "1800930FA8740100000000".hexToByteArray()
        query.receive(core)
        assertArrayEquals(core, query.result)
        assertNull(query.value(core.copyOf(7)))
        assertEquals(95400L, query.value(core))
        assertEquals(4294967295L, query.value("1800930FFFFFFFFF000000".hexToByteArray()))
        assertEquals("1d 2h 30min", formatUptime(checkNotNull(query.value(core))))
        val boot = checkNotNull(query.value("1800930F00000000000000".hexToByteArray()))
        assertEquals(0L, boot)
        assertEquals("0min", formatUptime(boot))
        assertEquals("1h 0min", formatUptime(3659))
    }

    @Test
    fun recognizesExplicitCommandErrorWithoutInterpretingItAsAReading() {
        val query = TelemetryQuery(TelemetryMetric.NOISE_FLOOR)
        val error = "0101".hexToByteArray()
        query.acknowledgeWrite()
        query.receive(error)
        assertArrayEquals(error, query.result)
        assertNull(query.value(error))
    }
}
