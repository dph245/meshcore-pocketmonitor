package de.meshcore.pocketmonitor

import de.meshcore.pocketmonitor.protocol.CompanionContact
import de.meshcore.pocketmonitor.protocol.HeardRepeater
import de.meshcore.pocketmonitor.protocol.RepeaterAttribution
import de.meshcore.pocketmonitor.protocol.RepeaterSort
import de.meshcore.pocketmonitor.protocol.RepeaterWindow
import java.security.KeyPairGenerator
import java.security.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRepeatersTest {
    private val first = contact("2A11", "Alpha")
    private val last = contact("9B22", "Beta")

    @Test fun `only the last matching flood hop is heard and gets the local signal metrics`() {
        val attribution = RepeaterAttribution(listOf(first, last))
        val rx = attribution.receive("88f2b00d022a9b01020304".hexToByteArray(), 42, 234)
        assertEquals(HeardRepeater("9B", "Beta (9B?)", 42, 234, -80, -3.5), rx)
        val scoped = attribution.receive("88f2b00c01020000422a119b2201020304".hexToByteArray(), 42, 234)
        assertEquals(HeardRepeater(last.publicKey, "Beta", 42, 234, -80, -3.5), scoped)
    }

    @Test fun `unknown and ambiguous flood hops retain their hash and signal metrics`() {
        val collision = contact("9BFF", "Client", type = 1)
        val attribution = RepeaterAttribution(listOf(first, last, collision))
        val window = RepeaterWindow()
        val expected = listOf(
            "88f2b00d022a9b01020304" to HeardRepeater("9B", "9B (2?)", 42, 234, -80, -3.5),
            "88f2b00d022add01020304" to HeardRepeater("DD", "DD?", 42, 234, -80, -3.5),
            "88f2b00d422a11dd2201020304" to HeardRepeater("DD22", "DD22", 42, 234, -80, -3.5),
        )
        expected.forEach { (frame, row) ->
            val heard = checkNotNull(attribution.receive(frame.hexToByteArray(), 42, 234))
            assertEquals(row, heard)
            window.add(heard)
        }
        window.add(expected.last().second)
        assertEquals(mapOf("9B" to 1, "DD" to 1, "DD22" to 2), window.snapshot(234).associate { it.id to it.count })
    }

    @Test fun `direct route truncated and non radio frames cannot provide a measured hop`() {
        val attribution = RepeaterAttribution(listOf(first, last))
        listOf(
            "88f2b00e022a9b01020304",
            "88f2b00d022a",
            "82f2b00d022a9b01020304",
        ).forEach { assertNull(attribution.receive(it.hexToByteArray(), 0, 0)) }
    }

    @Test fun `verified zero hop repeater advert identifies a previously unknown radio`() {
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val key = keys.public.encoded.takeLast(32).toByteArray()
        val header = key + byteArrayOf(1, 2, 3, 4)
        val app = byteArrayOf(0x82.toByte()) + "Station".encodeToByteArray()
        val signature = Signature.getInstance("Ed25519").run {
            initSign(keys.private)
            update(header + app)
            sign()
        }
        val rx = "8800b01200".hexToByteArray() + header + signature + app
        val attribution = RepeaterAttribution(emptyList())
        val heard = checkNotNull(attribution.receive(rx, 1, 2))
        assertEquals(key.toHexString(HexFormat.UpperCase), heard.id)
        assertEquals("Station", heard.name)
        rx[rx.lastIndex] = 0x58
        assertNull(attribution.receive(rx, 1, 2))
    }

    @Test fun `window expires individual receptions and keeps the latest reading`() {
        val window = RepeaterWindow()
        val heard = HeardRepeater(first.publicKey, "Alpha", 5_000, 100, -81, 2.5)
        window.add(heard)
        window.add(heard.copy(receivedAt = 4_000, elapsedMs = 200, rssi = -55))
        assertEquals(30_100L, window.nextExpiry())
        assertEquals(2, window.snapshot(30_099).single().count)
        val remaining = window.snapshot(30_100).single()
        assertEquals(1, remaining.count)
        assertEquals(-55, remaining.rssi)
        assertEquals(4_000L, remaining.receivedAt)
        assertTrue(window.snapshot(30_200).isEmpty())
        assertNull(window.nextExpiry())
    }

    @Test fun `companion sessions have separate windows and all sort orders are available`() {
        val window = RepeaterWindow()
        val a = HeardRepeater(first.publicKey, "Alpha", 1, 1, -50, 1.0)
        val b = HeardRepeater(last.publicKey, "Beta", 2, 2, -70, 3.0)
        window.add(a)
        window.add(a)
        window.add(b)
        val rows = window.snapshot(2)
        mapOf(RepeaterSort.RECENT to b, RepeaterSort.NAME to a, RepeaterSort.RSSI to a,
            RepeaterSort.SNR to b, RepeaterSort.COUNT to a).forEach { (sort, firstRow) ->
            assertEquals(firstRow.id, rows.sortedWith(sort.order).first().id)
        }
        assertTrue(RepeaterWindow().snapshot(2).isEmpty())
    }

    private fun contact(prefix: String, name: String, type: Int = 2) =
        CompanionContact(prefix + "00".repeat(30), name, type, 0, 0, "", 0, 0.0, 0.0, 0)
}
