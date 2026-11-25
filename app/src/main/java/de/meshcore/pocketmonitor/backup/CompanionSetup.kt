package de.meshcore.pocketmonitor.backup

import de.meshcore.pocketmonitor.protocol.ChannelQuery
import de.meshcore.pocketmonitor.protocol.CompanionContact
import de.meshcore.pocketmonitor.protocol.CompanionGps
import de.meshcore.pocketmonitor.protocol.CompanionManagement
import de.meshcore.pocketmonitor.protocol.CompanionProtocol
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

internal class CompanionSetup(
    val self: ByteArray,
    val version: Int,
    val repeat: Int,
    val pathWidth: Int,
    val gps: CompanionGps,
    val scope: ByteArray,
    val channels: List<ByteArray>,
    val contacts: List<ByteArray>,
    val privateKey: ByteArray?,
) {
    val publicKey = CompanionProtocol.selfIdentity(self)
        ?: error("Der öffentliche Schlüssel des Backups fehlt.")
    val name = self.decodeToString(58, throwOnInvalidSequence = true)
    val radio = SetupRadio.read(self)
    val position = checkNotNull(CompanionManagement.selfPosition(self)) { "Ungültige Companion-Position." }
    val other = checkNotNull(CompanionManagement.otherParams(self))
    val configuredChannels get() = channels.count { !ChannelQuery.emptySlot(it) }
    val txPower get() = self[2].toInt()

    init {
        check(version in 3..255 && name.encodeToByteArray().size in 1..31 && name.none(Char::isISOControl)) {
            "Ungültige Companion-Protokollversion oder ungültiger Gerätename."
        }
        check(txPower in -9..127 && repeat in -1..1 && pathWidth in 0..3 &&
            (version >= 7 || other.multiAcks == null || self[44] == 0.toByte()) &&
            (version >= 9) == (repeat >= 0) && (version >= 10) == (pathWidth > 0)) {
            "Ungültige Funkoptionen im Backup."
        }
        check(scope.isEmpty() || scope.contentEquals(byteArrayOf(ChannelQuery.DEFAULT_FLOOD_SCOPE.toByte())) ||
            (ChannelQuery.defaultScope(scope) != null && scope[31] == 0.toByte())) {
            "Ungültiger Standard-Scope im Backup."
        }
        check((version >= ChannelQuery.SCOPE_MIN_VERSION) == scope.isNotEmpty())
        check(channels.size <= 255 && channels.withIndex().all { (index, frame) ->
            frame.size == ChannelQuery.CHANNEL_FRAME_SIZE && frame[1].toUByte().toInt() == index &&
                (ChannelQuery.emptySlot(frame) || (ChannelQuery.configuredSlot(frame) != null && frame[33] == 0.toByte()))
        }) { "Ungültige Kanalbelegung im Backup." }
        val keys = contacts.map { frame ->
            check(frame.firstOrNull() == CompanionProtocol.CONTACT.toByte() && frame.size == 148 &&
                frame[131] == 0.toByte()) { "Ungültiger Kontakt im Backup." }
            val path = frame[35].toUByte().toInt()
            check(path == 0xff || if (version >= 10) {
                ((path shr 6) + 1) * (path and 63) <= 64
            } else path <= 64) { "Ungültiger Kontaktpfad im Backup." }
            CompanionContact.decode(frame)!!.publicKey
        }
        check(keys.size <= MAX_CONTACTS && keys.toSet().size == keys.size) { "Doppelte oder zu viele Kontakte im Backup." }
        check(privateKey == null || (privateKey.size == 64 && privateKey.any { it != 0.toByte() })) {
            "Ungültiger Identitätsschlüssel im Backup."
        }
    }

    fun write(output: DataOutputStream) {
        output.writeByte(version)
        output.writeByte(repeat)
        output.writeByte(pathWidth)
        output.writeByte(gps.ordinal)
        output.writeFrame(self)
        output.writeFrame(scope)
        output.writeByte(channels.size)
        for (frame in channels) output.write(frame)
        output.writeShort(contacts.size)
        for (frame in contacts) output.write(frame)
        output.writeFrame(privateKey ?: ByteArray(0))
    }

    companion object {
        private const val MAX_CONTACTS = 4096

        fun read(input: DataInputStream): CompanionSetup {
            val version = input.readUnsignedByte()
            val repeat = input.readByte().toInt()
            val pathWidth = input.readUnsignedByte()
            val gps = CompanionGps.entries.getOrNull(input.readUnsignedByte()) ?: error("Ungültiger GPS-Status.")
            val self = input.readFrame(89)
            val scope = input.readFrame(48)
            val channels = List(input.readUnsignedByte()) { ByteArray(50).also(input::readFully) }
            val count = input.readUnsignedShort()
            check(count <= MAX_CONTACTS) { "Zu viele Kontakte im Backup." }
            val contacts = List(count) { ByteArray(148).also(input::readFully) }
            val key = input.readFrame(64).takeIf { it.isNotEmpty() }
            return try {
                CompanionSetup(self, version, repeat, pathWidth, gps, scope, channels, contacts, key)
            } catch (error: Exception) {
                key?.fill(0)
                throw error
            }
        }

        private fun DataOutputStream.writeFrame(frame: ByteArray) {
            writeByte(frame.size)
            write(frame)
        }

        private fun DataInputStream.readFrame(max: Int): ByteArray {
            val size = readUnsignedByte()
            check(size <= max) { "Ungültige Länge eines Companion-Eintrags." }
            return ByteArray(size).also(::readFully)
        }
    }
}

internal data class SetupRadio(val frequency: Int, val bandwidth: Int, val spreading: Int, val coding: Int) {
    init {
        check(frequency in 150_000..2_500_000 && bandwidth in 7_000..500_000 && spreading in 5..12 && coding in 5..8) {
            "Ungültige LoRa-Parameter."
        }
    }

    fun command(repeat: Int): ByteArray = ByteBuffer.allocate(if (repeat < 0) 11 else 12)
        .order(ByteOrder.LITTLE_ENDIAN).apply {
            put(11).putInt(frequency).putInt(bandwidth).put(spreading.toByte()).put(coding.toByte())
            if (repeat >= 0) put(repeat.toByte())
        }.array()

    fun matches(actual: SetupRadio): Boolean =
        abs(actual.frequency - frequency) <= 1 && abs(actual.bandwidth - bandwidth) <= 1 &&
            actual.spreading == spreading && actual.coding == coding

    companion object {
        fun read(self: ByteArray): SetupRadio {
            check(self.size in 59..89) { "Unvollständige Self-Info-Antwort." }
            val fields = ByteBuffer.wrap(self, 48, 10).order(ByteOrder.LITTLE_ENDIAN)
            return SetupRadio(fields.int, fields.int, fields.get().toUByte().toInt(), fields.get().toUByte().toInt())
        }
    }
}
