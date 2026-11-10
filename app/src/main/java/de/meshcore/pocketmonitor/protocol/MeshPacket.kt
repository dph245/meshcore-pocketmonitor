package de.meshcore.pocketmonitor.protocol

internal enum class Route(val hasTransportCodes: Boolean, val isDirect: Boolean) {
    TRANSPORT_FLOOD(true, false),
    FLOOD(false, false),
    DIRECT(false, true),
    TRANSPORT_DIRECT(true, true),
}

internal enum class PacketType(val code: Int) {
    REQ(0), RESPONSE(1), TXT_MSG(2), ACK(3), ADVERT(4), GRP_TXT(5), GRP_DATA(6),
    ANON_REQ(7), PATH(8), TRACE(9), MULTIPART(10), CONTROL(11), RAW_CUSTOM(15),
}

internal data class MeshHeader(val raw: Int) {
    val version = (raw shr 6) + 1
    val typeCode = (raw shr 2) and 0x0f
    val type = PacketType.entries.firstOrNull { it.code == typeCode }
    val route = Route.entries[raw and 3]
}

internal data class TransportCodes(val scope: Int, val reserved: Int)

internal data class MeshPath(
    val encodedLength: Int,
    val prefixSize: Int,
    val prefixes: List<String>,
    val traceSnrDb: List<Double>? = null,
) {
    // TRACE carries the planned prefixes separately from measurements already collected.
    val hopCount: Int get() = traceSnrDb?.size ?: prefixes.size
}

internal sealed interface MeshPayload {
    data class Encrypted(
        val macHex: String,
        val ciphertextSize: Int,
        val senderPrefix: Int? = null,
        val senderKey: String? = null,
        val recipientPrefix: Int? = null,
        val channelHash: Int? = null,
    ) : MeshPayload

    data class Advert(
        val publicKey: String,
        val timestampSeconds: Long,
        val signature: String,
        val flags: Int?,
        val name: String?,
        val latitude: Double?,
        val longitude: Double?,
    ) : MeshPayload

    data class Ack(val token: Long, val attemptByte: Int?, val randomByte: Int?) : MeshPayload
    data class Multipart(val remaining: Int, val typeCode: Int, val ack: Ack?) : MeshPayload
    data class Trace(val tag: Long, val authCode: Long, val flags: Int) : MeshPayload
    data class Control(val flags: Int) : MeshPayload

    sealed interface Discover : MeshPayload {
        val tag: Long
        val flags: Int

        data class Request(
            override val tag: Long,
            override val flags: Int,
            val typeFilter: Int,
            val sinceSeconds: Long?,
        ) : Discover {
            val prefixOnly: Boolean get() = flags and 1 != 0
        }

        data class Response(
            override val tag: Long,
            override val flags: Int,
            val requestSnrDb: Double,
            val publicKeyOrPrefix: String,
        ) : Discover {
            val nodeType: Int get() = flags and 0x0f
            val fullPublicKey: Boolean get() = publicKeyOrPrefix.length == 64
        }
    }
}

internal enum class DecodeStatus {
    DECODED, ENCRYPTED, UNSUPPORTED, MALFORMED,
}

internal data class MeshPacket(
    val size: Int,
    val header: MeshHeader? = null,
    val transport: TransportCodes? = null,
    val pathLengthByte: Int? = null,
    val path: MeshPath? = null,
    val payloadSize: Int? = null,
    val payload: MeshPayload? = null,
    val comparisonId: String? = null,
    val status: DecodeStatus = DecodeStatus.DECODED,
    val issue: String? = null,
)
