package de.meshcore.pocketmonitor

import de.meshcore.pocketmonitor.protocol.ChannelDecoding
import de.meshcore.pocketmonitor.protocol.ChannelQuery
import de.meshcore.pocketmonitor.protocol.DecodeStatus
import de.meshcore.pocketmonitor.protocol.FloodScope
import de.meshcore.pocketmonitor.protocol.MeshPacketDecoder
import de.meshcore.pocketmonitor.protocol.MeshPayload
import de.meshcore.pocketmonitor.protocol.RadioChannel
import de.meshcore.pocketmonitor.rx.JournalSnapshot
import de.meshcore.pocketmonitor.rx.RxFrame
import de.meshcore.pocketmonitor.rx.RxRow
import de.meshcore.pocketmonitor.ui.LiveSnapshot
import de.meshcore.pocketmonitor.ui.scopeLabel
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ChannelDecodingTest {
    // Independent vectors made with OpenSSL AES-128-ECB and Python hashlib/hmac.
    private val publicPacket = "150011ac636130151a26a9a6710a2cad0bb9a5b9d8b073aa850126e87c47509a61a47bf107"
        .hexToByteArray()
    private val hashtagPacket = "1450f500000011805bef37f4455929c6341abd97900f030449".hexToByteArray()
    private val hashtagKey = "fbaf59884c3c5daa692bf27ecc579f12".hexToByteArray()
    private val scopeKey = "0102030405060708090a0b0c0d0e0f10".hexToByteArray()

    @Test
    fun readsPublicTextWithoutCompanionSlots() {
        val packet = MeshPacketDecoder.decode(publicPacket)
        assertEquals(DecodeStatus.DECODED, packet.status)
        assertEquals(
            MeshPayload.GroupText("Public", RadioChannel.builtIn.identity, "Jörg", "Moin: Funk! 📻", 0xf2345678, 2,
                MeshPayload.Encrypted("AC63", 32, channelHash = 0x11)),
            packet.payload,
        )
        assertEquals("Ohne Scope", packet.scopeLabel())
    }

    @Test
    fun macAndChannelHashMustBothMatchAndGroupDataStaysEncrypted() {
        for (offset in listOf(2, 3, publicPacket.lastIndex)) {
            val corrupt = publicPacket.copyOf().apply { this[offset] = (this[offset].toInt() xor 1).toByte() }
            assertEquals(DecodeStatus.ENCRYPTED, MeshPacketDecoder.decode(corrupt).status)
        }
        val groupData = publicPacket.copyOf().apply { this[0] = 0x19 }
        assertEquals(DecodeStatus.ENCRYPTED, MeshPacketDecoder.decode(groupData).status)
    }

    @Test
    fun handlesExactBlocksMissingSenderUnsupportedFlagsAndInvalidUtf8() {
        val payload = MeshPacketDecoder.decode("1500116f75f21d27e86da3f4ed79f16926f2a2df91".hexToByteArray())
            .payload as MeshPayload.GroupText
        assertEquals("12345678901", payload.text)
        assertNull(payload.sender)
        assertEquals(DecodeStatus.UNSUPPORTED, MeshPacketDecoder.decode(
            "150011e65efb8d6ec1871f4bb2ca27e71a1e98f30216acd75dfc7333634d864e9ea9c2c984".hexToByteArray(),
        ).status)
        assertEquals(DecodeStatus.MALFORMED, MeshPacketDecoder.decode(
            "150011ae680c195467f3eaaf8a5c3869d40a7cb370".hexToByteArray(),
        ).status)
    }

    @Test
    fun readsHashtagDespiteCollisionWithPublicAndRedecodesCachedRows() {
        val frame = RxFrame(123, "8800b0".hexToByteArray() + hashtagPacket)
        val original = frame.bytes.copyOf()
        val row = RxRow(1, frame)
        val journal = JournalSnapshot("test", 1, listOf(row), listOf(row), null)
        val first = LiveSnapshot.update(journal, null)
        assertEquals(DecodeStatus.ENCRYPTED, first.radioRows.single().row.packet?.status)

        val channel = requireNotNull(ChannelQuery.configuredSlot(channelInfo(7, "#test256", hashtagKey))?.channel)
        val defaultScope = ChannelQuery.defaultScope(byteArrayOf(0x1c) + fixedName("Ort", 31) + scopeKey)
        val updated = LiveSnapshot.update(journal, first, ChannelDecoding(listOf(channel), defaultScope))
        val packet = requireNotNull(updated.radioRows.single().row.packet)
        val message = packet.payload as MeshPayload.GroupText
        assertEquals("#test256", message.channel)
        assertEquals("Olaf", message.sender)
        assertEquals("Test", message.text)
        assertEquals("Scope #Ort", packet.scopeLabel())
        assertEquals(first.radioRows.single().row.packet?.comparisonId, packet.comparisonId)
        assertSame(row, updated.radioRows.single().row.original)
        assertArrayEquals(original, frame.bytes)
    }

    @Test
    fun scopeDependsOnThePacketAndPreservesUnknownAndAmbiguousCodes() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/rf/group-scoped.hex"))
            .bufferedReader().use { it.readText().trim().hexToByteArray() }
        assertEquals("Scope #de-ni", MeshPacketDecoder.decode(bytes).scopeLabel())
        val changed = bytes.copyOf().apply { this[lastIndex] = 0 }
        assertEquals("Unbekannter Scope 0x5BF1", MeshPacketDecoder.decode(changed).scopeLabel())
        val zero = bytes.copyOf().apply { this[1] = 0; this[2] = 0 }
        assertEquals("Ohne Scope (*)", MeshPacketDecoder.decode(zero).scopeLabel())
        val reserved = bytes.copyOf().apply { this[1] = -1; this[2] = -1 }
        assertEquals("Reservierter Scope-Code 0xFFFF", MeshPacketDecoder.decode(reserved).scopeLabel())
        // A known public scope with a second name and the same transport key.
        val key = MessageDigest.getInstance("SHA-256").digest("#de-ni".encodeToByteArray()).copyOf(16)
        val alias = ChannelDecoding(defaultScope = FloodScope("#Alias", key))
        assertEquals("Mehrdeutiger Scope (#de-ni / #Alias)", MeshPacketDecoder.decode(bytes, alias).scopeLabel())
    }

    @Test
    fun queriesOneSlotAndWaitsForBothReplyAndWriteAcknowledgement() {
        val query = ChannelQuery(7)
        assertArrayEquals(byteArrayOf(0x1f, 7), query.command)
        query.receive(channelInfo(1, "#test256", hashtagKey))
        query.receive(byteArrayOf(0x88.toByte()))
        assertNull(query.result)
        val answer = channelInfo(7, "#test256", hashtagKey)
        query.receive(answer)
        assertNull(query.result)
        query.acknowledgeWrite()
        assertSame(answer, query.result)

        val scope = ChannelQuery()
        assertArrayEquals(byteArrayOf(0x40), scope.command)
        scope.acknowledgeWrite()
        val rejected = byteArrayOf(0x01, 0x01)
        scope.receive(rejected)
        assertSame(rejected, scope.result)
        assertNull(ChannelQuery.defaultScope(rejected))
        assertNull(ChannelQuery.defaultScope(byteArrayOf(0x1c)))
    }

    @Test
    fun readsPrivateSlotsAndKeepsThemOutOfLiveDiagnostics() {
        assertNull(ChannelQuery.configuredSlot(channelInfo(0, "", ByteArray(16))))
        val privateSlot = requireNotNull(ChannelQuery.configuredSlot(channelInfo(7, "Public", hashtagKey)))
        assertEquals(7, privateSlot.index)
        assertEquals("Public", privateSlot.channel.name)
        assertNull(privateSlot.channel.forLiveDiagnostics())
        assertNull(ChannelQuery.configuredSlot(channelInfo(0, "#test256", hashtagKey).copyOf(49)))
    }

    private fun channelInfo(slot: Int, name: String, key: ByteArray) =
        byteArrayOf(0x12, slot.toByte()) + fixedName(name, 32) + key

    private fun fixedName(name: String, width: Int) = name.encodeToByteArray().copyOf(width)
}
