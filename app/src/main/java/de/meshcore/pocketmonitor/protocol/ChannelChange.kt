package de.meshcore.pocketmonitor.protocol

import kotlin.io.encoding.Base64

internal data class ChannelReference(val slot: Int, val name: String, val identity: String) {
    fun matches(frame: ByteArray): Boolean = ChannelQuery.configuredSlot(frame)?.reference() == this
}

internal fun ChannelSlot.reference() = ChannelReference(index, channel.name, channel.identity)

internal class NewChannel private constructor(val name: String, private val key: ByteArray) {
    val identity = RadioChannel(name, key).identity

    fun change(slot: Int): ChannelChange {
        val nameBytes = name.encodeToByteArray()
        val command = ChannelChange.clearCommand(slot)
        nameBytes.copyInto(command, 2)
        key.copyInto(command, 34)
        return ChannelChange(slot, command, before = null, after = ChannelReference(slot, name, identity))
    }

    companion object {
        fun publicName(name: String): Boolean = name == RadioChannel.builtIn.name || name.startsWith('#')

        fun validName(name: String): Boolean {
            val trimmed = name.trim()
            if (trimmed.isEmpty() || trimmed == "#" || trimmed.any(Char::isISOControl)) return false
            return runCatching { trimmed.encodeToByteArray(throwOnInvalidSequence = true).size in 1..31 }
                .getOrDefault(false)
        }

        fun parse(name: String, secret: String): NewChannel? {
            if (!validName(name)) return null
            val trimmed = name.trim()
            val key = when {
                trimmed == RadioChannel.builtIn.name -> RadioChannel.publicKeyBytes()
                trimmed.startsWith('#') -> FloodScope.namedKey(trimmed)
                else -> decodeKey(secret.trim()) ?: return null
            }
            if (key.size != 16 || key.all { it == 0.toByte() }) return null
            return NewChannel(trimmed, key)
        }

        private fun decodeKey(secret: String): ByteArray? = runCatching {
            when (secret.length) {
                32 -> secret.hexToByteArray()
                22, 24 -> Base64.decode(secret.padEnd(24, '='))
                else -> null
            }
        }.getOrNull()
    }
}

internal class ChannelChange(
    val slot: Int,
    val command: ByteArray,
    private val before: ChannelReference?,
    private val after: ChannelReference?,
) {
    fun matchesBefore(frame: ByteArray) = matches(frame, before)
    fun matchesAfter(frame: ByteArray) = matches(frame, after)

    private fun matches(frame: ByteArray, expected: ChannelReference?): Boolean =
        frame.getOrNull(1)?.toInt()?.and(0xff) == slot &&
            (expected?.matches(frame) ?: ChannelQuery.emptySlot(frame))

    companion object {
        fun remove(channel: ChannelReference) =
            ChannelChange(channel.slot, clearCommand(channel.slot), before = channel, after = null)

        fun clearCommand(slot: Int) =
            ByteArray(ChannelQuery.CHANNEL_FRAME_SIZE).apply {
                this[0] = 0x20
                this[1] = slot.toByte()
            }
    }
}
