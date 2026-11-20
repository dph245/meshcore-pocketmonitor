package de.meshcore.pocketmonitor.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class TracePath private constructor(val hashBytes: Int, val hashes: List<String>) {
    val flags: Int get() = Integer.numberOfTrailingZeros(hashBytes)

    fun command(tag: UInt, auth: UInt): ByteArray {
        val path = hashes.joinToString("").hexToByteArray()
        return ByteBuffer.allocate(10 + path.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(36).putInt(tag.toInt()).putInt(auth.toInt()).put(flags.toByte()).put(path).array()
    }

    companion object {
        val widths = listOf(1, 2, 4)

        fun hash(text: String, width: Int): String? = text.trim().uppercase().takeIf { value ->
            width in widths && value.length == width * 2 && value.all { it in '0'..'9' || it in 'A'..'F' }
        }

        fun create(width: Int, hops: List<String>, mirror: Boolean): TracePath? {
            val maxHops = maxHops(width, CompanionProtocol.FULL_FRAME_MTU)
            if (hops.isEmpty() || hops.size > maxHops) return null
            val hashes = hops.map { hash(it, width) ?: return null }
            val path = if (mirror) hashes + hashes.asReversed().drop(1) else hashes
            return if (path.size <= maxHops) TracePath(width, path) else null
        }

        fun maxHops(width: Int, mtu: Int): Int {
            if (width !in widths) return 0
            // The response is larger than the write: 12 header bytes, hashes, hop SNRs, final SNR.
            val frameBytes = minOf(mtu, CompanionProtocol.FULL_FRAME_MTU) - 3
            // Firmware drops a TRACE with 64 SNR entries and the TX payload has 184 - 9 bytes for hashes.
            return minOf(63, 175 / width, (frameBytes - 13) / (width + 1)).coerceAtLeast(0)
        }
    }
}

internal data class TraceResponse(
    val tag: UInt,
    val auth: UInt,
    val flags: Int,
    val hashes: List<String>,
    val hopSnrDb: List<Double>,
    val finalSnrDb: Double,
) {
    companion object {
        const val CODE = 0x89

        fun decode(frame: ByteArray): TraceResponse? {
            if (frame.size < 13 || frame[0].toInt() and 0xff != CODE || frame[1] != 0.toByte()) return null
            val pathBytes = frame[2].toInt() and 0xff
            val flags = frame[3].toInt() and 0xff
            val width = 1 shl (flags and 3)
            val hopCount = pathBytes / width
            val snrOffset = 12 + pathBytes
            if (pathBytes % width != 0 || hopCount !in 1..63 || frame.size != snrOffset + hopCount + 1) return null
            val fields = ByteBuffer.wrap(frame, 4, 8).order(ByteOrder.LITTLE_ENDIAN)
            return TraceResponse(
                fields.int.toUInt(), fields.int.toUInt(), flags,
                List(hopCount) { index ->
                    val offset = 12 + index * width
                    frame.toHexString(offset, offset + width, HexFormat.UpperCase)
                },
                List(hopCount) { frame[snrOffset + it] / 4.0 },
                frame.last() / 4.0,
            )
        }
    }
}

internal class TraceCommand(path: TracePath, private val tag: UInt, auth: UInt) {
    private val read = CompanionRead(path.command(tag, auth), SENT)
    val command: ByteArray get() = read.command
    val result: ByteArray? get() = read.result

    fun acknowledgeWrite() = read.acknowledgeWrite()

    fun receive(frame: ByteArray) {
        if (frame.size == 10 && frame[0].toInt() == SENT && fields(frame).int.toUInt() != tag) return
        read.receive(frame)
    }

    companion object {
        private const val SENT = 6

        private fun fields(frame: ByteArray) = ByteBuffer.wrap(frame, 2, 8).order(ByteOrder.LITTLE_ENDIAN)

        fun estimateMs(frame: ByteArray): Long? =
            if (frame.size == 10 && frame[0].toInt() == SENT && frame[1] == 0.toByte()) {
                fields(frame).getInt(6).toUInt().toLong()
            } else null
    }
}

internal enum class TracePhase { CONFIRMING, LISTENING, ANSWERED, TIMED_OUT, FAILED, DISCONNECTED }

internal data class TraceRun(
    val session: String,
    val tag: UInt,
    val auth: UInt,
    val path: TracePath,
    val startedAtMs: Long,
    val resolver: HopResolver,
    val phase: TracePhase = TracePhase.CONFIRMING,
    val response: TraceResponse? = null,
    val message: String? = null,
    val late: Boolean = false,
) {
    val busy: Boolean get() = phase == TracePhase.CONFIRMING || phase == TracePhase.LISTENING

    fun matches(response: TraceResponse): Boolean =
        response.tag == tag && response.auth == auth && response.flags == path.flags && response.hashes == path.hashes
}
