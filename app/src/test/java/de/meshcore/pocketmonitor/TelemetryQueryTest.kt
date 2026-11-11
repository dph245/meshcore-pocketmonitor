package de.meshcore.pocketmonitor

import de.meshcore.pocketmonitor.protocol.TelemetryMetric
import de.meshcore.pocketmonitor.protocol.TelemetryQuery
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryQueryTest {
    @Test
    fun readsBatteryInMillivoltsFromLegacyAndCurrentReplies() {
        val query = TelemetryQuery(TelemetryMetric.BATTERY)
        assertArrayEquals("14".hexToByteArray(), query.command)
        assertEquals(3987, query.value("0C930F".hexToByteArray()))
        assertEquals(3987, query.value("0C930F0100000002000000".hexToByteArray()))
        assertEquals(65534, query.value("0CFEFF".hexToByteArray()))
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
        assertEquals(-115, query.value(reply))
        assertNull(query.value("18018D".hexToByteArray()))
        assertNull(query.value("18010000".hexToByteArray()))
        assertNull(query.value("18028DFF".hexToByteArray()))
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
