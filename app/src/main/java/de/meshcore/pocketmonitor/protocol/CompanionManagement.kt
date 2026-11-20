package de.meshcore.pocketmonitor.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import kotlin.math.roundToInt

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
    data class SendAdvert(val flood: Boolean) : CompanionAction
    data class SetPosition(val position: CompanionPosition) : CompanionAction
    data object ReadLocationSettings : CompanionAction
    data class SetAdvertLocation(val enabled: Boolean) : CompanionAction
    data class SetGpsEnabled(val enabled: Boolean) : CompanionAction
    data class AddContact(val key: String, val name: String, val category: ContactCategory) : CompanionAction
    data class RemoveContact(val key: String) : CompanionAction
    sealed interface EditChannel : CompanionAction {
        data class Add(val channel: NewChannel) : EditChannel
        data class Remove(val channel: ChannelReference) : EditChannel
    }
    data class ResetPrivatePath(val key: String) : CompanionAction
    data class RunCli(val text: String) : CompanionAction
}

internal data class AdvertLocationSettings(
    val policy: Int,
    val telemetry: Byte,
    val manualAddContacts: Byte,
) {
    val enabled: Boolean get() = policy != 0
}

internal enum class CompanionGps { UNKNOWN, UNAVAILABLE, DISABLED, ENABLED }

internal data class CompanionPosition(val latitudeE6: Int, val longitudeE6: Int) {
    init {
        require(latitudeE6 in -90_000_000..90_000_000 && longitudeE6 in -180_000_000..180_000_000)
    }

    companion object {
        val ZERO = CompanionPosition(0, 0)
        private val decimal = Regex("[+-]?(?:[0-9]+(?:[.,][0-9]*)?|[.,][0-9]+)")

        fun parse(latitude: String, longitude: String): CompanionPosition? {
            val lat = decimalDegrees(latitude) ?: return null
            val lon = decimalDegrees(longitude) ?: return null
            return fromDegrees(lat, lon)
        }

        fun fromDegrees(latitude: Double, longitude: Double): CompanionPosition? {
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
            return CompanionPosition((latitude * 1e6).roundToInt(), (longitude * 1e6).roundToInt())
        }

        private fun decimalDegrees(input: String): Double? =
            input.trim().takeIf(decimal::matches)?.replace(',', '.')?.toDoubleOrNull()
    }
}

internal object CompanionManagement {
    const val CLI_MIN_VERSION = 14
    const val CLI_REPLY = 0x1d
    const val LOCATION_PARAMS_MIN_VERSION = 5
    const val CUSTOM_VARS = 0x15
    const val SCOPE_INPUT_ERROR = "Scope einschließlich # höchstens 30 UTF-8-Bytes, ohne Leer- oder Steuerzeichen."
    const val CONTACT_INPUT_ERROR = "Name mit 1–31 UTF-8-Bytes und öffentlicher Schlüssel mit 64 Hex-Zeichen erforderlich."
    private const val SET_SCOPE: Byte = 0x3f
    private const val SEND_ADVERT: Byte = 0x07
    private const val SET_POSITION: Byte = 0x0e
    private const val SET_OTHER_PARAMS: Byte = 0x26
    const val GET_CUSTOM_VARS: Byte = 0x28
    private const val SET_CUSTOM_VAR: Byte = 0x29
    private const val ADD_CONTACT: Byte = 0x09
    private const val REMOVE_CONTACT: Byte = 0x0f
    private const val RUN_CLI: Byte = 0x42
    private val publicKeyPattern = Regex("[0-9a-fA-F]{64}")

    fun advertCommand(flood: Boolean): ByteArray = byteArrayOf(SEND_ADVERT, if (flood) 1 else 0)

    fun positionCommand(position: CompanionPosition): ByteArray =
        ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).put(SET_POSITION)
            .putInt(position.latitudeE6).putInt(position.longitudeE6).array()

    fun selfPosition(frame: ByteArray): CompanionPosition? {
        if (frame.size < 44 || frame[0].toInt() != CompanionProtocol.Command.APP_START.expectedReply) return null
        // SELF_INFO carries two signed microdegree fields after the 32-byte public key.
        val fields = ByteBuffer.wrap(frame, 36, 8).order(ByteOrder.LITTLE_ENDIAN)
        val latitude = fields.int
        val longitude = fields.int
        if (latitude !in -90_000_000..90_000_000 || longitude !in -180_000_000..180_000_000) return null
        return CompanionPosition(latitude, longitude)
    }

    fun advertLocationSettings(frame: ByteArray): AdvertLocationSettings? {
        if (frame.size < 58 || frame[0].toInt() != CompanionProtocol.Command.APP_START.expectedReply) return null
        return AdvertLocationSettings(frame[45].toInt() and 0xff, frame[46], frame[47])
    }

    // The trailing Multi-ACK byte is optional. Omitting it leaves that setting untouched.
    fun advertLocationCommand(current: AdvertLocationSettings, enabled: Boolean): ByteArray =
        byteArrayOf(SET_OTHER_PARAMS, current.manualAddContacts, current.telemetry, if (enabled) 1 else 0)

    fun gpsCommand(enabled: Boolean): ByteArray =
        byteArrayOf(SET_CUSTOM_VAR) + (if (enabled) "gps:1" else "gps:0").encodeToByteArray()

    fun gpsSetting(frame: ByteArray): CompanionGps {
        if (frame.firstOrNull()?.toInt() != CUSTOM_VARS) return CompanionGps.UNKNOWN
        val matches = frame.decodeToString(startIndex = 1).split(',').filter { it.substringBefore(':') == "gps" }
        return when {
            matches.isEmpty() -> CompanionGps.UNAVAILABLE
            matches.size != 1 -> CompanionGps.UNKNOWN
            matches.single() == "gps:1" -> CompanionGps.ENABLED
            matches.single() == "gps:0" -> CompanionGps.DISABLED
            else -> CompanionGps.UNKNOWN
        }
    }

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
