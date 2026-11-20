package de.meshcore.pocketmonitor

import de.meshcore.pocketmonitor.protocol.ChannelChange
import de.meshcore.pocketmonitor.protocol.ChannelQuery
import de.meshcore.pocketmonitor.protocol.NewChannel
import de.meshcore.pocketmonitor.protocol.reference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelChangeTest {
    @Test
    fun addRequiresAnEmptySlotAndConfirmsTheNameAndKey() {
        val channel = checkNotNull(NewChannel.parse("  Privat  ", "00112233445566778899aabbccddeeff"))
        val change = channel.change(4)
        assertArrayEquals(
            ("2004507269766174" + "00".repeat(26) + "00112233445566778899aabbccddeeff").hexToByteArray(),
            change.command,
        )
        val empty = channelInfo(ChannelChange.clearCommand(4)).apply { this[3] = 0x41 }
        val actual = channelInfo(change.command)
        assertTrue(change.matchesBefore(empty))
        assertFalse(change.matchesBefore(actual))
        assertFalse(change.matchesBefore(empty.copyOf().apply { this[34] = 1 }))
        assertFalse(change.matchesBefore(byteArrayOf(1, 2)))
        assertTrue(change.matchesAfter(actual))
        assertFalse(change.matchesAfter(actual.copyOf().apply { this[2] = 0x52 }))
        assertFalse(change.matchesAfter(actual.copyOf().apply { this[34] = 1 }))
        assertFalse(change.matchesAfter(actual.copyOf().apply { this[1] = 5 }))
        assertFalse(change.matchesAfter(actual.copyOf(49)))
    }

    @Test
    fun removalRequiresTheSelectedChannelAndConfirmsAClearedSlot() {
        val configured = channelInfo(checkNotNull(NewChannel.parse("Public", "")).change(0).command)
        val channel = checkNotNull(ChannelQuery.configuredSlot(configured)).reference()
        val removal = ChannelChange.remove(channel)
        assertArrayEquals(byteArrayOf(0x20, 0) + ByteArray(48), removal.command)
        assertTrue(removal.matchesBefore(configured))
        assertFalse(removal.matchesBefore(configured.copyOf().apply { this[1] = 1 }))
        assertFalse(removal.matchesBefore(configured.copyOf().apply { this[49] = 0 }))
        assertTrue(removal.matchesAfter(channelInfo(removal.command)))
        assertFalse(removal.matchesAfter(configured))
    }

    @Test
    fun namesAndPrivateKeysAreValidatedWithoutTruncation() {
        assertTrue(NewChannel.validName("ä".repeat(15) + "x"))
        assertFalse(NewChannel.validName("ä".repeat(16)))
        assertFalse(NewChannel.validName("A\u0000B"))
        assertNull(NewChannel.parse("Raum", ""))
        assertNull(NewChannel.parse("Raum", "00".repeat(16)))
        assertNull(NewChannel.parse("Raum", "00112233445566778899aabbccddeefg"))
        assertNull(NewChannel.parse("#", ""))
        val hex = NewChannel.parse("Raum", "00112233445566778899aabbccddeeff")
        val base64 = NewChannel.parse("Raum", "ABEiM0RVZneImaq7zN3u/w==")
        assertNotNull(hex)
        assertEquals(hex?.identity, base64?.identity)
        assertEquals(hex?.identity, NewChannel.parse("Raum", "ABEiM0RVZneImaq7zN3u/w")?.identity)
        assertNotNull(NewChannel.parse("#Test", ""))
    }

    private fun channelInfo(command: ByteArray) =
        command.copyOf().apply { this[0] = ChannelQuery.CHANNEL_INFO.toByte() }
}
