package de.meshcore.pocketmonitor.protocol

import java.nio.charset.CharacterCodingException

internal class ChannelQuery(slot: Int? = null) : CompanionRead(
    command = if (slot == null) byteArrayOf(GET_DEFAULT_FLOOD_SCOPE) else byteArrayOf(GET_CHANNEL, slot.toByte()),
    responseCode = if (slot == null) DEFAULT_FLOOD_SCOPE else CHANNEL_INFO,
    responseSubtype = slot,
) {
    companion object {
        private const val GET_CHANNEL: Byte = 0x1f
        private const val GET_DEFAULT_FLOOD_SCOPE: Byte = 0x40
        const val CHANNEL_INFO = 0x12
        private const val DEFAULT_FLOOD_SCOPE = 0x1c
        const val SCOPE_MIN_VERSION = 10
        const val CHANNEL_MTU = 53
        const val SCOPE_MTU = 51

        fun publicChannel(frame: ByteArray): PublicChannel? {
            if (frame.size != 50 || frame[0] != CHANNEL_INFO.toByte()) return null
            val name = frame.name(2, 34) ?: return null
            return PublicChannel.fromSlot(name, frame.copyOfRange(34, 50))
        }

        fun defaultScope(frame: ByteArray): FloodScope? {
            if (frame.size != 48 || frame[0] != DEFAULT_FLOOD_SCOPE.toByte()) return null
            val name = frame.name(1, 32)?.takeIf { it.isNotBlank() } ?: return null
            val key = frame.copyOfRange(32, 48)
            if (key.all { it == 0.toByte() }) return null
            // Firmware build defaults can omit the leading '#' from the name field.
            return FloodScope(if (name.startsWith('#')) name else "#$name", key)
        }

        private fun ByteArray.name(start: Int, end: Int): String? {
            val stop = (start until end).firstOrNull { this[it] == 0.toByte() } ?: end
            return try {
                decodeToString(start, stop, throwOnInvalidSequence = true).takeUnless { it.any(Char::isISOControl) }
            } catch (_: CharacterCodingException) {
                null
            }
        }
    }
}
