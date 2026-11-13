package de.meshcore.pocketmonitor

import de.meshcore.pocketmonitor.protocol.ChannelDecoding
import de.meshcore.pocketmonitor.protocol.CompanionProtocol
import de.meshcore.pocketmonitor.protocol.RadioChannel
import de.meshcore.pocketmonitor.rx.RxFrame
import de.meshcore.pocketmonitor.rx.decodeMessage
import de.meshcore.pocketmonitor.rx.isJournalable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceptionHistoryTest {
    @Test
    fun identityUsesOnlyFullNonzeroSelfPublicKey() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val self = byteArrayOf(5, 2, 18, 20) + key
        assertEquals(key.toHexString(), CompanionProtocol.selfIdentity(self + byteArrayOf(1, 2)))
        assertNull(CompanionProtocol.selfIdentity(self.copyOf(35)))
        assertNull(CompanionProtocol.selfIdentity(byteArrayOf(5, 2, 18, 20) + ByteArray(32)))
        assertNull(CompanionProtocol.selfIdentity(self.copyOf().apply { this[0] = 13 }))
    }

    @Test
    fun secretBearingRepliesStayOutsideTheJournal() {
        for (code in listOf(0x0d, 0x0e, 0x12, 0x1c)) {
            assertFalse(RxFrame(0, byteArrayOf(code.toByte())).isJournalable())
        }
        assertTrue(RxFrame(0, byteArrayOf(0x88.toByte(), 0x12, 0x1c)).isJournalable())
    }

    @Test
    fun privateDecodeUsesKeyIdentityAndForwardingKeepsComparisonId() {
        val key = "fbaf59884c3c5daa692bf27ecc579f12".hexToByteArray()
        val channel = RadioChannel("Public", key)
        assertNotEquals(RadioChannel.builtIn.identity, channel.identity)
        assertEquals(channel.identity, RadioChannel("Renamed", key).identity)
        val privateFrame = RxFrame(100, "8800b01450f500000011805bef37f4455929c6341abd97900f030449".hexToByteArray())
        assertNull(decodeMessage(privateFrame, ChannelDecoding.default))
        val decoded = requireNotNull(decodeMessage(privateFrame, ChannelDecoding(listOf(channel))))
        assertEquals("Olaf", decoded.payload.sender)
        assertEquals("Test", decoded.payload.text)
        assertEquals(channel.identity, decoded.payload.channelIdentity)

        val public = "8800b0150011ac636130151a26a9a6710a2cad0bb9a5b9d8b073aa850126e87c47509a61a47bf107".hexToByteArray()
        val forwarded = "8800b015012a".hexToByteArray() + public.copyOfRange(5, public.size)
        assertEquals(
            requireNotNull(decodeMessage(RxFrame(100, public), ChannelDecoding.default)).comparison,
            requireNotNull(decodeMessage(RxFrame(200, forwarded), ChannelDecoding.default)).comparison,
        )
    }
}
