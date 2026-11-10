package de.meshcore.pocketmonitor.protocol

import java.util.UUID

internal object CompanionProtocol {
    val service: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val radioRx: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val radioTx: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    val cccd: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val REQUESTED_MTU = 512
    const val FULL_FRAME_MTU = 179 // Firmware MAX_FRAME_SIZE (176) + ATT notification header.
    const val ERROR = 0x01
    const val GET_CONTACTS = 0x04
    const val CONTACTS_START = 0x02
    const val CONTACT = 0x03
    const val CONTACTS_END = 0x04
    const val DISABLED = 0x0f
    const val CONTACT_FRAME_MTU = 151 // 148-byte contact frame + ATT header.
    const val RF_LOG = 0x88

    enum class Command(val code: Int, val expectedReply: Int) {
        APP_START(0x01, 0x05),
        DEVICE_QUERY(0x16, 0x0d);

        fun encode(): ByteArray = when (this) {
            APP_START -> byteArrayOf(0x01, 0, 0, 0, 0, 0, 0, 0) + "PocketMonitor".encodeToByteArray()
            DEVICE_QUERY -> byteArrayOf(0x16, 0x03)
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
