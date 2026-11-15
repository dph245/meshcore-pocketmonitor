package de.meshcore.pocketmonitor.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal enum class PrivateSendStatus(val label: String) {
    WAITING("Wartend"),
    ACCEPTED("An Companion übergeben"),
    ACKNOWLEDGED("ACK erhalten"),
    FAILED("Fehlgeschlagen"),
    UNKNOWN("Zustellung unbekannt"),
}

internal data class PrivateRetryOptions(
    val pathAttempts: Int = 3,
    val floodAttempts: Int = 3,
    val resetPathForFlood: Boolean = false,
) {
    init {
        require(pathAttempts in 1..3 && floodAttempts in 1..3)
    }
}

internal enum class PrivateSendStep { SEND, RESET_PATH, DONE }

internal data class PrivateSendReceipt(val flood: Boolean, val token: Long, val estimateMs: Long) {
    // The estimate includes mesh propagation. Leave extra room for the radio TX queue.
    val waitMs: Long get() = maxOf(estimateMs + 5_000, if (flood) 20_000 else 10_000)

    companion object {
        fun decode(frame: ByteArray): PrivateSendReceipt? {
            if (frame.size != 10 || frame[0] != 6.toByte() || frame[1].toInt() !in 0..1) return null
            val fields = ByteBuffer.wrap(frame, 2, 8).order(ByteOrder.LITTLE_ENDIAN)
            return PrivateSendReceipt(frame[1].toInt() == 1, fields.int.toUInt().toLong(),
                fields.int.toUInt().toLong())
        }
    }
}

internal class PrivateRetry(private val options: PrivateRetryOptions) {
    private var path = 0
    private var flood = 0
    private var reset = false
    private var routeChanged = false
    private val tokens = mutableSetOf<Long>()
    var acknowledged = false
        private set
    val attempt: Int get() = path + flood

    fun receipt(receipt: PrivateSendReceipt) {
        if (receipt.flood) {
            flood++
        } else {
            if (flood > 0 || reset) routeChanged = true
            path++
        }
        if (receipt.token != 0L) tokens.add(receipt.token)
    }

    fun acknowledge(token: Long) {
        if (token in tokens) acknowledged = true
    }

    fun pathUpdated() {
        if (reset || flood > 0) routeChanged = true
    }

    fun pathReset() {
        reset = true
    }

    fun next(): PrivateSendStep = when {
        acknowledged || routeChanged || flood >= options.floodAttempts -> PrivateSendStep.DONE
        flood > 0 -> PrivateSendStep.SEND
        reset || path < options.pathAttempts -> PrivateSendStep.SEND
        options.resetPathForFlood -> PrivateSendStep.RESET_PATH
        else -> PrivateSendStep.DONE
    }
}

internal object PrivateSend {
    const val SENT = 6
    const val PATH_UPDATED = 0x81
    const val NEW_ADVERT = 0x8a
    const val CONTACT_DELETED = 0x8f

    fun changedContact(frame: ByteArray): String? = when {
        frame.size == 148 && frame[0] == NEW_ADVERT.toByte() ||
            frame.size == 33 && frame[0] == CONTACT_DELETED.toByte() ->
            frame.toHexString(1, 33, HexFormat.UpperCase)
        else -> null
    }

    fun textLimit(mtu: Int): Int = minOf(158, mtu - 3 - 13)

    fun contact(publicKey: String, byPrefix: Map<String, List<CompanionContact>>): CompanionContact? =
        byPrefix[publicKey.take(12)]?.singleOrNull()?.takeIf { it.publicKey == publicKey }

    fun command(publicKey: String, seconds: Long, attempt: Int, bytes: ByteArray): ByteArray =
        byteArrayOf(2, 0, attempt.toByte()) + GroupSend.timestamp(seconds) +
            publicKey.take(12).hexToByteArray() + bytes

    fun resetPath(publicKey: String): ByteArray = byteArrayOf(13) + publicKey.hexToByteArray()

    fun ack(frame: ByteArray): Long? {
        if (frame.size != 9 || frame[0] != 0x82.toByte()) return null
        return ByteBuffer.wrap(frame, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int.toUInt().toLong().takeIf { it != 0L }
    }
}
