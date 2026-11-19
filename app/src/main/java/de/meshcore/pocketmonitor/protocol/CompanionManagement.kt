package de.meshcore.pocketmonitor.protocol

import java.nio.charset.CharacterCodingException

internal enum class ContactCategory(val label: String, val type: Int) {
    CHAT("Chat", 1),
    REPEATER("Repeater", CompanionContact.TYPE_REPEATER),
    ROOMSERVER("Roomserver", CompanionContact.TYPE_ROOM_SERVER),
    OTHER("Weitere", 0);

    fun contains(contact: CompanionContact): Boolean = when (this) {
        OTHER -> contact.type !in 1..3
        else -> contact.type == type
    }
}

internal sealed interface CompanionAction {
    data class SetScope(val name: String) : CompanionAction
    data class AddContact(val key: String, val name: String, val category: ContactCategory) : CompanionAction
    data class RemoveContact(val key: String) : CompanionAction
    data class RunCli(val text: String) : CompanionAction
}

internal object CompanionManagement {
    const val CLI_MIN_VERSION = 14
    const val CLI_REPLY = 0x1d
    const val SCOPE_INPUT_ERROR = "Scope einschließlich # höchstens 30 UTF-8-Bytes, ohne Leer- oder Steuerzeichen."
    const val CONTACT_INPUT_ERROR = "Name mit 1–31 UTF-8-Bytes und öffentlicher Schlüssel mit 64 Hex-Zeichen erforderlich."
    private const val SET_SCOPE: Byte = 0x3f
    private const val ADD_CONTACT: Byte = 0x09
    private const val REMOVE_CONTACT: Byte = 0x0f
    private const val RUN_CLI: Byte = 0x42
    private val publicKeyPattern = Regex("[0-9a-fA-F]{64}")

    fun scopeCommand(input: String): ByteArray? {
        if (input.isBlank()) return byteArrayOf(SET_SCOPE)
        val name = namedScope(input) ?: return null
        val bytes = name.encodeToByteArray()
        return byteArrayOf(SET_SCOPE) + bytes.copyOf(31) + FloodScope.namedKey(name)
    }

    fun namedScope(input: String): String? {
        val trimmed = input.trim()
        val name = if (trimmed.startsWith('#')) trimmed else "#$trimmed"
        if (name.length < 2 || name.any(Char::isWhitespace) || textBytes(name, 30) == null) return null
        return name
    }

    fun scopeMatches(command: ByteArray, frame: ByteArray): Boolean =
        if (command.size == 1) {
            frame.contentEquals(byteArrayOf(ChannelQuery.DEFAULT_FLOOD_SCOPE.toByte()))
        } else {
            // Firmware may leave bytes after the name's NUL unchanged.
            val end = command.indexOf(0.toByte())
            ChannelQuery.defaultScope(frame) != null &&
                command.copyOfRange(1, end + 1).contentEquals(frame.copyOfRange(1, end + 1)) &&
                command.copyOfRange(32, 48).contentEquals(frame.copyOfRange(32, 48))
        }

    fun addContact(action: CompanionAction.AddContact): ByteArray? {
        val key = publicKey(action.key) ?: return null
        val name = textBytes(action.name.trim(), 31) ?: return null
        if (action.category == ContactCategory.OTHER) return null
        // MyMesh::updateContactFromFrame. No learned path (0xff). Omit lastmod
        // so the Companion assigns its own timestamp.
        return ByteArray(144).apply {
            this[0] = ADD_CONTACT
            key.copyInto(this, 1)
            this[33] = action.category.type.toByte()
            this[35] = 0xff.toByte()
            name.copyInto(this, 100)
        }
    }

    fun removeContact(key: String): ByteArray? = publicKey(key)?.let { byteArrayOf(REMOVE_CONTACT) + it }

    fun cliTextLimit(mtu: Int): Int = minOf(160, mtu - 7)

    fun cliCommand(input: String, prefix: String, maxBytes: Int): ByteArray? {
        if (input.any(Char::isISOControl)) return null
        val bytes = textBytes(input.trim().padEnd(2), maxBytes) ?: return null
        return byteArrayOf(RUN_CLI) + prefix.encodeToByteArray() + bytes
    }

    fun cliReply(frame: ByteArray, prefix: String): String? {
        if (frame.firstOrNull() != CLI_REPLY.toByte()) return null
        val text = frame.decodeToString(startIndex = 1)
        return text.takeIf { it.startsWith(prefix) }?.substring(prefix.length)
    }

    private fun publicKey(input: String): ByteArray? =
        input.trim().takeIf(publicKeyPattern::matches)?.hexToByteArray()

    private fun textBytes(input: String, limit: Int): ByteArray? {
        if (input.isBlank() || input.any(Char::isISOControl)) return null
        return try {
            input.encodeToByteArray(throwOnInvalidSequence = true).takeIf { it.size <= limit }
        } catch (_: CharacterCodingException) {
            null
        }
    }
}
