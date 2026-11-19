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
    val floodScope: String = GroupSend.DEFAULT,
    val manualRouting: Boolean = false,
    val manualRoute: PrivateRoute? = null,
) {
    val activeRoute: PrivateRoute? get() = manualRoute.takeIf { manualRouting }

    init {
        require(pathAttempts in 1..MAX_ATTEMPTS && floodAttempts in 1..MAX_ATTEMPTS)
        require(!manualRouting || manualRoute != null)
    }

    companion object {
        const val MAX_ATTEMPTS = 5
    }
}

internal enum class PrivateSendStep { SEND, RESET_PATH, DONE }

internal enum class PrivateSendRoute(val label: String) { DIRECT("Direct"), FLOOD("Flood") }

internal data class PrivateSendReceipt(val flood: Boolean, val token: Long, val estimateMs: Long) {
    val route: PrivateSendRoute get() = if (flood) PrivateSendRoute.FLOOD else PrivateSendRoute.DIRECT

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
    val floodPhase: Boolean get() = reset || flood > 0

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
    const val UNKNOWN_PATH = 0xff
    const val PATH_UPDATED = 0x81
    const val NEW_ADVERT = 0x8a
    const val CONTACT_DELETED = 0x8f

    fun changedContact(frame: ByteArray): String? = when {
        frame.size == 148 && frame[0] == NEW_ADVERT.toByte() ||
            frame.size == 33 && frame[0] == CONTACT_DELETED.toByte() ->
            frame.toHexString(1, 33, HexFormat.UpperCase)
        else -> null
    }

    fun pathUpdatedKey(frame: ByteArray): String? =
        frame.takeIf { it.size == 33 && it[0] == PATH_UPDATED.toByte() }
            ?.toHexString(1, 33, HexFormat.UpperCase)

    fun textLimit(mtu: Int): Int = minOf(158, mtu - 3 - 13)

    fun contact(publicKey: String, byPrefix: Map<String, List<CompanionContact>>): CompanionContact? =
        byPrefix[publicKey.take(12)]?.singleOrNull()?.takeIf { it.publicKey == publicKey }

    fun command(publicKey: String, seconds: Long, attempt: Int, bytes: ByteArray): ByteArray =
        byteArrayOf(2, 0, attempt.toByte()) + GroupSend.timestamp(seconds) +
            publicKey.take(12).hexToByteArray() + bytes

    fun resetPath(publicKey: String): ByteArray = byteArrayOf(13) + publicKey.hexToByteArray()

    fun readContact(publicKey: String): ByteArray = byteArrayOf(0x1e) + publicKey.hexToByteArray()

    fun setPath(contactFrame: ByteArray, route: PrivateRoute): ByteArray = contactFrame.copyOf().apply {
        this[0] = 9
        this[35] = route.encodedLength.toByte()
        fill(0, fromIndex = 36, toIndex = 100)
        route.hops.joinToString("").hexToByteArray().copyInto(this, 36)
    }

    fun outgoingPath(contact: CompanionContact): MeshPath? {
        val length = contact.outPathLength
        if (length !in 0 until UNKNOWN_PATH) return null
        val width = (length shr 6) + 1
        val bytes = (length and 63) * width
        if (width > 3 || bytes > 64 || bytes * 2 > contact.outPathHex.length) return null
        val hex = contact.outPathHex.take(bytes * 2)
        if (hex.any { it !in "0123456789abcdefABCDEF" }) return null
        return MeshPath(length, width, hex.uppercase().chunked(width * 2))
    }

    fun pathSignature(contact: CompanionContact): String? = outgoingPath(contact)?.let {
        "${it.encodedLength}:${it.prefixes.joinToString("")}"
    }

    fun ack(frame: ByteArray): Long? {
        if (frame.size != 9 || frame[0] != 0x82.toByte()) return null
        return ByteBuffer.wrap(frame, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int.toUInt().toLong().takeIf { it != 0L }
    }
}
