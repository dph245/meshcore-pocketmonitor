package de.meshcore.pocketmonitor

import de.meshcore.pocketmonitor.protocol.DecodeStatus
import de.meshcore.pocketmonitor.protocol.MeshPacketDecoder
import de.meshcore.pocketmonitor.protocol.MeshPayload
import de.meshcore.pocketmonitor.protocol.PacketType
import de.meshcore.pocketmonitor.protocol.Route
import de.meshcore.pocketmonitor.protocol.TransportCodes
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MeshPacketDecoderTest {
    @Test
    fun decodesCapturedGroupPacketWithoutInventingChannelOrSender() {
        val bytes = fixture("group-scoped")
        val original = bytes.copyOf()
        val packet = MeshPacketDecoder.decode(bytes)
        assertEquals(DecodeStatus.ENCRYPTED, packet.status)
        assertEquals(47, packet.size)
        assertEquals(PacketType.GRP_TXT, packet.header?.type)
        assertEquals(1, packet.header?.version)
        assertEquals(Route.TRANSPORT_FLOOD, packet.header?.route)
        assertEquals(TransportCodes(0x5bf1, 0), packet.transport)
        assertEquals(0x82, packet.pathLengthByte)
        assertEquals(3, packet.path?.prefixSize)
        assertEquals(listOf("FA9B11", "8B558F"), packet.path?.prefixes)
        assertEquals(2, packet.path?.hopCount)
        assertEquals(MeshPayload.Encrypted("612B", 32, channelHash = 0x28), packet.payload)
        assertEquals("CEC6EE6405AF323D", packet.comparisonId)
        assertArrayEquals(original, bytes)
    }

    @Test
    fun decodesCapturedExtendedAckAndStandardAck() {
        val packet = MeshPacketDecoder.decode(fixture("ack-extended"))
        assertEquals(DecodeStatus.DECODED, packet.status)
        assertEquals(Route.DIRECT, packet.header?.route)
        assertEquals(3, packet.path?.hopCount)
        assertEquals(MeshPayload.Ack(0x1b6a740e, 0, 4), packet.payload)
        assertEquals("657AAFF64E4CD73E", packet.comparisonId)
        assertEquals(MeshPayload.Ack(0xf4f3f2f1, null, null), decode("0D00F1F2F3F4").payload)
        assertEquals(MeshPayload.Multipart(2, 3, MeshPayload.Ack(0xf4f3f2f1, 1, 2)),
            decode("2A0023F1F2F3F40102").payload)
        assertEquals(DecodeStatus.UNSUPPORTED, decode("0D000102030405").status)
    }

    @Test
    fun parsesTraceMeasurementsSeparatelyFromPlannedPrefixes() {
        val bytes = fixture("trace")
        val packet = MeshPacketDecoder.decode(bytes)
        assertEquals(DecodeStatus.DECODED, packet.status)
        assertEquals(PacketType.TRACE, packet.header?.type)
        assertEquals(MeshPayload.Trace(0x99f432d2, 0, 2), packet.payload)
        val path = requireNotNull(packet.path)
        assertEquals(4, path.prefixSize)
        assertEquals(listOf("77730034", "8DBC81BE", "77730034"), path.prefixes)
        assertEquals(listOf(13.25, 2.5), path.traceSnrDb)
        assertEquals(2, path.hopCount)
        assertEquals("E3C8FE1622AEA5DE", packet.comparisonId)
        val earlier = MeshPacketDecoder.decode("2601FC".hexToByteArray() + bytes.copyOfRange(4, bytes.size))
        assertEquals(listOf(-1.0), earlier.path?.traceSnrDb)
        assertNotEquals(packet.comparisonId, earlier.comparisonId)
        assertEquals(DecodeStatus.MALFORMED, MeshPacketDecoder.decode(bytes.copyOf(bytes.size - 1)).status)
    }

    @Test
    fun handlesZeroHopsAndTwoBytePathsAndComparesForwardedPackets() {
        val zero = decode("0D8001020304")
        val multi = decode("0F341278564201AABB0201020304")
        val singleByte = decode("0D03AABBCC01020304")
        assertEquals(0, zero.path?.hopCount)
        assertEquals(3, zero.path?.prefixSize)
        assertEquals(Route.TRANSPORT_DIRECT, multi.header?.route)
        assertEquals(TransportCodes(0x1234, 0x5678), multi.transport)
        assertEquals(2, multi.path?.prefixSize)
        assertEquals(listOf("01AA", "BB02"), multi.path?.prefixes)
        assertEquals(3, singleByte.path?.hopCount)
        assertEquals(zero.comparisonId, multi.comparisonId)
        assertEquals(zero.comparisonId, singleByte.comparisonId)
    }

    @Test
    fun readsAdvertFixedFieldsAndOptionalAppData() {
        val fixed = "1100".hexToByteArray() + ByteArray(32) { it.toByte() } +
            "785634F2".hexToByteArray() + ByteArray(64) { 0x22 }
        val app = "F2C0BDF0FF40420F0001020304".hexToByteArray() + "Dörnten".encodeToByteArray()
        val packet = MeshPacketDecoder.decode(fixed + app)
        val advert = packet.payload as MeshPayload.Advert
        assertEquals(DecodeStatus.DECODED, packet.status)
        assertEquals(0xf2345678, advert.timestampSeconds)
        assertEquals("Dörnten", advert.name)
        assertEquals(0xf2, advert.flags)
        assertEquals(-1.0, advert.latitude)
        assertEquals(1.0, advert.longitude)
        assertEquals(64, advert.publicKey.length)
        assertEquals(128, advert.signature.length)
        assertNull((MeshPacketDecoder.decode(fixed).payload as MeshPayload.Advert).flags)
        assertEquals(DecodeStatus.MALFORMED, MeshPacketDecoder.decode(fixed + "91AB".hexToByteArray()).status)
        assertEquals(DecodeStatus.MALFORMED, MeshPacketDecoder.decode(fixed + "81FF".hexToByteArray()).status)
    }

    @Test
    fun identifiesPublicAddressFieldsOfEncryptedPackets() {
        val dm = MeshPacketDecoder.decode("0A00AABB1234".hexToByteArray() + ByteArray(16))
        assertEquals(DecodeStatus.ENCRYPTED, dm.status)
        assertEquals(MeshPayload.Encrypted("1234", 16, senderPrefix = 0xbb, recipientPrefix = 0xaa), dm.payload)
        val anonymous = MeshPacketDecoder.decode("1E00AA".hexToByteArray() + ByteArray(32) { 0x19 } +
            "1234".hexToByteArray() + ByteArray(16))
        assertEquals(MeshPayload.Encrypted("1234", 16, senderKey = "19".repeat(32), recipientPrefix = 0xaa),
            anonymous.payload)
        assertEquals(DecodeStatus.MALFORMED, decode("1500BB1234AA").status)
    }

    @Test
    fun decodesDiscoverRequestAndBothResponseKeyWidths() {
        val req = decode("2E008114785634F212345678")
        val reqPayload = req.payload as MeshPayload.Discover.Request
        assertEquals(0xf2345678, reqPayload.tag)
        assertEquals(true, reqPayload.prefixOnly)
        assertEquals(0x14, reqPayload.typeFilter)
        assertEquals(0x78563412L, reqPayload.sinceSeconds)
        assertNull((decode("2E008014785634F2").payload as MeshPayload.Discover.Request).sinceSeconds)
        for (keyBytes in listOf(8, 32)) {
            val res = decode("2E0092E3785634F2" + "AB".repeat(keyBytes))
            val response = res.payload as MeshPayload.Discover.Response
            assertEquals(reqPayload.tag, response.tag)
            assertEquals(2, response.nodeType)
            assertEquals(-7.25, response.requestSnrDb, 0.0)
            assertEquals(keyBytes == 32, response.fullPublicKey)
            assertEquals("AB".repeat(keyBytes), response.publicKeyOrPrefix)
        }
        assertEquals(DecodeStatus.MALFORMED, decode("2E0092E3785634F2AB").status)
        assertEquals(DecodeStatus.MALFORMED, decode("2E008114785634F212").status)
    }

    @Test
    fun reportsTruncationAndUnsupportedFormatsWithoutGuessingFields() {
        for (hex in listOf("", "0D", "0C0001", "0D40", "0D6201020304", "0D82000102", "0D0001", "260000")) {
            val packet = decode(hex)
            assertEquals(hex, DecodeStatus.MALFORMED, packet.status)
            assertNotNull(packet.issue)
            assertNull(packet.comparisonId)
        }
        for (hex in listOf("4D0001020304", "0DC001020304", "3100FF", "3E00FF", "2E0070")) {
            assertEquals(hex, DecodeStatus.UNSUPPORTED, decode(hex).status)
        }
        assertNull(decode("4D0001020304").path)
        assertEquals(12, decode("3100FF").header?.typeCode)
        assertNotNull(decode("3100FF").comparisonId)
    }

    @Test
    fun arbitraryAndTruncatedInputNeverThrowsOrMutatesBytes() {
        val random = Random(37)
        repeat(4_096) {
            val bytes = random.nextBytes(it % 257)
            val original = bytes.copyOf()
            assertEquals(original.size, MeshPacketDecoder.decode(bytes).size)
            assertArrayEquals(original, bytes)
        }
        for (name in listOf("group-scoped", "ack-extended", "trace")) {
            val bytes = fixture(name)
            for (length in bytes.indices) {
                assertEquals(length, MeshPacketDecoder.decode(bytes.copyOf(length)).size)
            }
        }
    }

    private fun decode(hex: String) = MeshPacketDecoder.decode(hex.hexToByteArray())

    private fun fixture(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/rf/$name.hex"))
        .bufferedReader().use { it.readText().trim().hexToByteArray() }
}
