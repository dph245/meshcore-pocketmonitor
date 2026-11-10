package de.meshcore.pocketmonitor.protocol

import java.util.UUID

internal object CompanionProtocol {
    val service: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val radioRx: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val radioTx: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    val cccd: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val REQUESTED_MTU = 512
    const val FULL_FRAME_MTU = 179 // Firmware MAX_FRAME_SIZE (176) + ATT notification header.
    const val OK = 0x00
    const val ERROR = 0x01
    const val GET_CONTACTS = 0x04
    const val CONTACTS_START = 0x02
    const val CONTACT = 0x03
    const val CONTACTS_END = 0x04
    const val DISABLED = 0x0f
    const val CONTACT_FRAME_MTU = 151 // 148-byte contact frame + ATT header.
    const val RF_LOG = 0x88
    private const val SEND_CONTROL_DATA = 0x37
    const val DISCOVER_MIN_VERSION = 8
    const val DISCOVER_MIN_MTU = 46 // 38-byte payload + 2 RF header + 3 RX log + 3 ATT.

    fun encodeDiscover(tag: UInt): ByteArray = byteArrayOf(
        SEND_CONTROL_DATA.toByte(),
        0x80.toByte(), // DISCOVER_REQ, request full public keys.
        (1 shl CompanionContact.TYPE_REPEATER).toByte(),
        tag.toByte(), (tag shr 8).toByte(), (tag shr 16).toByte(), (tag shr 24).toByte(),
    ) // Omitted since defaults to zero.

    enum class Command(val code: Int, val expectedReply: Int) {
        APP_START(0x01, 0x05),
        DEVICE_QUERY(0x16, 0x0d);

        fun encode(): ByteArray = when (this) {
            APP_START -> byteArrayOf(0x01, 0, 0, 0, 0, 0, 0, 0) + "PocketMonitor".encodeToByteArray()
            DEVICE_QUERY -> byteArrayOf(0x16, 0x03)
        }
    }
}

internal sealed interface DiscoverReply {
    data object Accepted : DiscoverReply
    data class Rejected(val message: String, val unsupported: Boolean = false) : DiscoverReply
    data object Malformed : DiscoverReply
}

/** SEND_CONTROL_DATA replies carry no request ID. Only one command may be outstanding. */
internal class DiscoverCommandReply {
    private var writeComplete = false
    private var reply: DiscoverReply? = null
    val result: DiscoverReply? get() = reply.takeIf { writeComplete }

    fun acknowledgeWrite() {
        writeComplete = true
    }

    fun receive(frame: ByteArray) {
        if (reply != null) return
        reply = when (frame.firstOrNull()?.toInt()?.and(0xff)) {
            CompanionProtocol.OK -> if (frame.size == 1) DiscoverReply.Accepted else DiscoverReply.Malformed
            CompanionProtocol.DISABLED -> if (frame.size == 1) {
                DiscoverReply.Rejected("DISCOVER ist auf dem Companion deaktiviert.", unsupported = true)
            } else DiscoverReply.Malformed
            CompanionProtocol.ERROR -> if (frame.size == 2) {
                val code = frame[1].toInt() and 0xff
                val reason = when (code) {
                    1 -> "Befehl nicht unterstützt"
                    2 -> "Eintrag nicht gefunden"
                    3 -> "Paketpuffer voll"
                    4 -> "Ungültiger Gerätezustand"
                    5 -> "Speicherfehler"
                    6 -> "Ungültiges Argument"
                    else -> "Unbekannter Fehler"
                }
                DiscoverReply.Rejected("$reason (Companion-Code $code).", unsupported = code == 1)
            } else DiscoverReply.Malformed
            else -> null
        }
    }
}

// A reply can arrive before Android dispatches the write-completion callback.
internal class PendingReply(val command: CompanionProtocol.Command) {
    private var writeComplete = false
    private var replyReceived = false

    fun acknowledgeWrite(): Boolean {
        writeComplete = true
        return replyReceived
    }

    fun receive(code: Int?): Boolean {
        if (code != command.expectedReply) return false
        replyReceived = true
        return writeComplete
    }
}
