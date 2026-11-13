package de.meshcore.pocketmonitor.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException

internal sealed interface MessageAddress {
    data class Channel(val slot: Int) : MessageAddress
    data class Contact(val prefix: String) : MessageAddress
}

internal data class QueuedMessage(
    val address: MessageAddress,
    val timestampSeconds: Long,
    val text: String,
    val signedSenderPrefix: String?,
) {
    companion object {
        const val NEXT = 0x0a
        const val EMPTY = 0x0a
        const val WAITING = 0x83
        private const val CONTACT = 0x07
        private const val CHANNEL = 0x08
        private const val CONTACT_V3 = 0x10
        private const val CHANNEL_V3 = 0x11
        private const val CHANNEL_DATA = 0x1b

        fun isQueueEntry(code: Int?) =
            code == CONTACT || code == CHANNEL || code == CONTACT_V3 || code == CHANNEL_V3 || code == CHANNEL_DATA

        fun decode(frame: ByteArray): QueuedMessage? {
            val code = frame.firstOrNull()?.toInt()?.and(0xff)
            val addressStart = when (code) {
                CONTACT, CHANNEL -> 1
                CONTACT_V3, CHANNEL_V3 -> 4 // SNR and two reserved bytes.
                else -> return null
            }
            val private = code == CONTACT || code == CONTACT_V3
            val pathOffset = addressStart + if (private) 6 else 1
            val timestampOffset = pathOffset + 2
            val timestampEnd = timestampOffset + 4
            if (frame.size < timestampEnd) return null
            val type = frame[pathOffset + 1].toInt()
            if (type != 0 && !(private && type == 2)) return null // CLI and binary data are journal-only.
            val textOffset = timestampEnd + if (type == 2) 4 else 0
            if (frame.size < textOffset) return null
            val text = try {
                frame.decodeToString(textOffset, frame.size, throwOnInvalidSequence = true)
                    .takeUnless { '\u0000' in it } ?: return null
            } catch (_: CharacterCodingException) {
                return null
            }
            val address = if (private) {
                MessageAddress.Contact(frame.toHexString(addressStart, pathOffset, HexFormat.UpperCase))
            } else {
                MessageAddress.Channel(frame[addressStart].toInt() and 0xff)
            }
            return QueuedMessage(
                address,
                ByteBuffer.wrap(frame, timestampOffset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toUInt().toLong(),
                text,
                if (type == 2) frame.toHexString(timestampEnd, textOffset, HexFormat.UpperCase) else null,
            )
        }
    }
}

/** A dequeue step has no request ID. Its reply can precede the GATT write callback. */
internal class MessageSyncStep {
    var writeComplete = false
        private set
    private var entryCommitted = false
    private var replyCode: Int? = null
    val receivedReply: Boolean get() = replyCode != null

    enum class Result { NEXT, EMPTY, REJECTED, MALFORMED }

    val result: Result?
        get() = if (!writeComplete) null else when (replyCode) {
            QueuedMessage.EMPTY -> Result.EMPTY
            CompanionProtocol.ERROR, CompanionProtocol.DISABLED -> Result.REJECTED
            INVALID_REPLY -> Result.MALFORMED
            null -> null
            else -> Result.NEXT.takeIf { entryCommitted }
        }

    fun acknowledgeWrite() {
        writeComplete = true
    }

    fun entryCommitted() {
        entryCommitted = true
    }

    // True only for a newly accepted queue entry that must be committed to the journal.
    fun receive(frame: ByteArray): Boolean {
        if (replyCode != null) return false
        val code = frame.firstOrNull()?.toInt()?.and(0xff)
        if (QueuedMessage.isQueueEntry(code)) {
            replyCode = code
            return true
        }
        replyCode = when (code) {
            QueuedMessage.EMPTY, CompanionProtocol.DISABLED -> if (frame.size == 1) code else INVALID_REPLY
            CompanionProtocol.ERROR -> if (frame.size == 2) code else INVALID_REPLY
            else -> null
        }
        return false
    }

    private companion object {
        const val INVALID_REPLY = -1
    }
}

internal fun splitChannelText(text: String): Pair<String?, String> {
    val separator = text.indexOf(": ")
    return if (separator > 0) text.substring(0, separator) to text.substring(separator + 2)
    else null to text
}
