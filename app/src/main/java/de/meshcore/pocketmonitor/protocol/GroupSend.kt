package de.meshcore.pocketmonitor.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException

internal object GroupSend {
    const val SCOPE_MIN_VERSION = 8
    const val UNSCOPED_MIN_VERSION = 12
    private const val MAX_TEXT = 160
    private const val MAX_NAME = 31
    private const val HEADER = 7
    private const val SCOPE: Byte = 54

    const val DEFAULT = "Companion Default"
    const val UNSCOPED = "Ohne Scope"

    fun scopeOptions(version: Int): List<String> = buildList {
        add(DEFAULT)
        if (version >= UNSCOPED_MIN_VERSION) add(UNSCOPED)
        if (version >= SCOPE_MIN_VERSION) addAll(ChannelDecoding.publicScopeNames)
    }

    val restoreScope: ByteArray get() = byteArrayOf(SCOPE, 0)

    fun scopeOverride(scope: String): ByteArray? = when (scope) {
        DEFAULT -> null
        UNSCOPED -> byteArrayOf(SCOPE, 1)
        else -> byteArrayOf(SCOPE, 0) + FloodScope.namedKey(scope)
    }

    // Reserve room for the firmware's "<node name>: " prefix.
    fun textLimit(mtu: Int): Int = minOf(MAX_TEXT - MAX_NAME - 2, mtu - 3 - HEADER)

    fun textBytes(text: String): ByteArray? {
        if (text.isBlank() || '\u0000' in text) return null
        return try {
            text.encodeToByteArray(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private const val SELF_NAME_OFFSET = 58

    fun selfName(frame: ByteArray): ByteArray? =
        frame.takeIf { it.size in SELF_NAME_OFFSET..(SELF_NAME_OFFSET + MAX_NAME) }
            ?.copyOfRange(SELF_NAME_OFFSET, frame.size)?.takeUnless { 0.toByte() in it }

    fun timestamp(seconds: Long): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(seconds.toInt()).array()

    fun command(slot: Int, seconds: Long, bytes: ByteArray): ByteArray =
        byteArrayOf(3, 0, slot.toByte()) + timestamp(seconds) + bytes

    fun repeatDigest(channel: RadioChannel, seconds: Long, sender: ByteArray, text: ByteArray): String =
        groupPayloadDigest(channel.encryptGroupText(timestamp(seconds) + byteArrayOf(0) + sender + ": ".encodeToByteArray() + text))
}

internal enum class GroupSendStatus(val label: String) {
    UNKNOWN("Companion-Annahme unklar"),
    ACCEPTED("Vom Companion angenommen"),
    REJECTED("Vom Companion abgelehnt"),
    FAILED("Nicht an den Companion übergeben"),
}

internal fun groupPayloadDigest(payload: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").run {
        update(PacketType.GRP_TXT.code.toByte())
        digest(payload).toHexString()
    }
