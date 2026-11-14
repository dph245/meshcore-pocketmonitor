package de.meshcore.pocketmonitor

import de.meshcore.pocketmonitor.protocol.ChannelDecoding
import de.meshcore.pocketmonitor.protocol.CompanionRead
import de.meshcore.pocketmonitor.protocol.GroupSend
import de.meshcore.pocketmonitor.protocol.MeshPacketDecoder
import de.meshcore.pocketmonitor.protocol.MeshPayload
import de.meshcore.pocketmonitor.protocol.RadioChannel
import org.junit.Assert.*
import org.junit.Test

class GroupSendTest {
    @Test fun channelTextUsesLittleEndianTimeAndUtf8WithoutTerminator() {
        val bytes = GroupSend.textBytes("Grüße 📻")!!
        assertEquals("030028785634124772c3bcc39f6520f09f93bb", GroupSend.command(40, 0x12345678, bytes).toHexString())
        assertEquals(127, GroupSend.textLimit(512))
        assertEquals(43, GroupSend.textLimit(53))
        assertNull(GroupSend.textBytes(" "))
        assertNull(GroupSend.textBytes("a\u0000b"))
        assertNull(GroupSend.textBytes("\uD800"))
    }

    @Test fun companionDefaultLeavesScopeAloneAndExplicitOptionsFollowFirmware() {
        assertEquals(listOf(GroupSend.DEFAULT), GroupSend.scopeOptions(7))
        assertNull(GroupSend.scopeOverride(GroupSend.DEFAULT))
        assertTrue("#de" in GroupSend.scopeOptions(8))
        assertFalse(GroupSend.UNSCOPED in GroupSend.scopeOptions(11))
        assertTrue(GroupSend.UNSCOPED in GroupSend.scopeOptions(12))
        assertEquals("3601", GroupSend.scopeOverride(GroupSend.UNSCOPED)?.toHexString())
        assertEquals("3600", GroupSend.restoreScope.toHexString())
        assertEquals(18, GroupSend.scopeOverride("#de")?.size)
    }

    @Test fun reproducesMeshCorePayloads() {
        for (frame in listOf(
            "150011cb8bd388be58b000d1a990acef0234cc185bb073aa850126e87c47509a61a47bf107",
            "1450f500000011805bef37f4455929c6341abd97900f030449",
        )) {
            val bytes = frame.hexToByteArray()
            val channel = if (frame.startsWith("15")) RadioChannel.builtIn else
                RadioChannel("Private", "fbaf59884c3c5daa692bf27ecc579f12".hexToByteArray())
            val packet = MeshPacketDecoder.decode(bytes, ChannelDecoding(listOf(channel)))
            val text = packet.payload as MeshPayload.GroupText
            val payloadOffset = if (frame.startsWith("15")) 2 else 6
            val pathOffset = payloadOffset - 1
            val repeat = bytes.copyOf(pathOffset) + byteArrayOf(1, 42) + bytes.copyOfRange(payloadOffset, bytes.size)
            val parsed = MeshPacketDecoder.decode(repeat, ChannelDecoding(includeBuiltIn = false))
            val sender = requireNotNull(text.sender).encodeToByteArray()
            val digest = GroupSend.repeatDigest(channel, text.timestampSeconds, sender,
                text.text.encodeToByteArray())
            assertNull(packet.repeatDigest)
            assertEquals(digest, parsed.repeatDigest)
            assertEquals(listOf("2A"), parsed.path?.prefixes)
            assertNotEquals(digest, GroupSend.repeatDigest(channel, text.timestampSeconds + 1,
                sender, text.text.encodeToByteArray()))
            assertNull(MeshPacketDecoder.decode(repeat.copyOf(repeat.size - 1)).repeatDigest)
        }
    }

    @Test fun waitsForGattWriteAndMatchingCompanionReply() {
        val write = CompanionRead(byteArrayOf(3), 0)
        write.receive(byteArrayOf(0x88.toByte()))
        write.receive(byteArrayOf(0))
        assertNull(write.result)
        write.acknowledgeWrite()
        assertArrayEquals(byteArrayOf(0), write.result)
        val reject = CompanionRead(byteArrayOf(3), 0)
        reject.acknowledgeWrite()
        assertNull(reject.result)
        reject.receive(byteArrayOf(1, 2))
        assertArrayEquals(byteArrayOf(1, 2), reject.result)
    }
}
