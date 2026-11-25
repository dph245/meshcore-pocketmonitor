package de.meshcore.pocketmonitor.backup

import de.meshcore.pocketmonitor.protocol.ChannelQuery
import de.meshcore.pocketmonitor.protocol.CompanionGps
import de.meshcore.pocketmonitor.protocol.CompanionManagement
import de.meshcore.pocketmonitor.protocol.CompanionPosition
import de.meshcore.pocketmonitor.protocol.CompanionProtocol
import de.meshcore.pocketmonitor.protocol.CompanionProtocol.Command
import de.meshcore.pocketmonitor.protocol.CompanionRead

internal enum class SetupRestoreStage { PREPARING, SETTINGS, CHANNELS, CONTACTS, VERIFYING_CONTACTS, VERIFYING_SETTINGS }

internal data class SetupRestoreProgress(
    val stage: SetupRestoreStage = SetupRestoreStage.PREPARING,
    val restoredChannels: Int = 0,
    val restoredContacts: Int = 0,
    val skippedContacts: Int = 0,
)

internal class CompanionSetupTransfer(
    private val exchange: suspend (CompanionRead) -> ByteArray,
    private val readContacts: suspend () -> List<ByteArray>,
) {
    private data class Device(val version: Int, val maxContacts: Int, val slots: Int, val repeat: Int, val pathWidth: Int)

    suspend fun capture(expectedIdentity: String, includeIdentity: Boolean): CompanionSetup {
        val device = readDevice()
        val self = readSelf(expectedIdentity)
        val scope = if (device.version >= ChannelQuery.SCOPE_MIN_VERSION) {
            canonicalScope(success(exchange(ChannelQuery()), "Standard-Scope lesen"))
        } else ByteArray(0)
        val gps = readGps(device.version)
        val channels = List(device.slots) { readChannel(it) }
        val contacts = readContacts().map { canonicalContact(it, device.version) }
        val key = if (includeIdentity) readKey() else null
        return try {
            CompanionSetup(self, device.version, device.repeat, device.pathWidth, gps, scope, channels, contacts, key)
        } catch (error: Exception) {
            key?.fill(0)
            throw error
        }
    }

    suspend fun restore(
        source: CompanionSetup,
        targetKey: String,
        restoreIdentity: Boolean,
        onProgress: (SetupRestoreProgress) -> Unit = {},
    ): Int {
        val device = readDevice()
        val target = readSelf(targetKey)
        val actualRadio = SetupRadio.read(target)
        val targetOther = checkNotNull(CompanionManagement.otherParams(target))
        val targetGps = readGps(device.version)
        val targetPosition = checkNotNull(CompanionManagement.selfPosition(target))
        val targetScope = if (source.scope.isNotEmpty() && device.version >= ChannelQuery.SCOPE_MIN_VERSION) {
            canonicalScope(success(exchange(ChannelQuery()), "Standard-Scope lesen"))
        } else ByteArray(0)
        val targetChannels = List(minOf(source.channels.size, device.slots)) { readChannel(it) }
        check(source.channels.drop(device.slots).all(ChannelQuery::emptySlot)) {
            "Auf dem Ziel-Companion fehlen belegte Kanal-Slots aus der Sicherung."
        }
        check(source.txPower <= target[3].toInt()) { "Die Sendeleistung der Sicherung übersteigt das Zielgerätelimit." }
        if (device.pathWidth == 0) {
            check(source.pathWidth <= 1) { "Das Zielgerät unterstützt keine Mehrbyte-Pfade." }
            val existingKeys = readContacts().mapTo(mutableSetOf()) { it.contactKey() }
            checkContactPaths(contactsThatFit(source.contacts, existingKeys, device.maxContacts), device)
        }
        check(source.other.multiAcks != true || device.version >= 7) { "Das Zielgerät unterstützt keine Multi-ACKs." }
        check(source.repeat != 1 || device.repeat >= 0) { "Das Zielgerät unterstützt den Client-Repeater-Modus nicht." }
        check(source.scope.size <= 1 || targetScope.isNotEmpty()) { "Das Zielgerät unterstützt keinen Standard-Scope." }
        val sourceGps = source.gps
        check(!sourceGps.available || targetGps.available) { "Das Zielgerät meldet kein steuerbares GPS." }

        val desiredKey = if (restoreIdentity) {
            checkNotNull(source.privateKey) { "Die Sicherung enthält keinen privaten Identitätsschlüssel." }
            source.publicKey
        } else targetKey

        var step = "Identität"
        var attemptedWrite = false
        var progress = SetupRestoreProgress()
        fun report(update: SetupRestoreProgress) {
            progress = update
            onProgress(update)
        }
        suspend fun apply(label: String, bytes: ByteArray) {
            step = label
            attemptedWrite = true
            write(bytes, label)
        }
        try {
            report(progress.copy(stage = SetupRestoreStage.SETTINGS))
            if (desiredKey != targetKey) {
                val command = byteArrayOf(IMPORT_PRIVATE_KEY) + checkNotNull(source.privateKey)
                try {
                    // A disabled or rejected import leaves the setup untouched.
                    write(command, "Identität importieren")
                    attemptedWrite = true
                    readSelf(desiredKey)
                } finally {
                    command.fill(0)
                }
            }
            if (source.name != target.decodeToString(58)) {
                apply("Gerätename", byteArrayOf(8) + source.name.encodeToByteArray())
            }
            val desiredRepeat = source.repeat.takeIf { it >= 0 && device.repeat >= 0 } ?: device.repeat
            if (source.radio != actualRadio || desiredRepeat != device.repeat) {
                apply("LoRa-Parameter", source.radio.command(desiredRepeat))
            }
            if (source.txPower != target[2].toInt()) {
                apply("Sendeleistung", byteArrayOf(12, source.txPower.toByte()))
            }
            if (sourceGps.available && sourceGps != targetGps) {
                apply("GPS", CompanionManagement.gpsCommand(sourceGps == CompanionGps.ENABLED))
                check(readGps(device.version) == sourceGps) { "GPS-Status stimmt nach dem Schreiben nicht überein." }
            }
            if (!positionsMatch(source.position, targetPosition)) {
                apply("Position", CompanionManagement.positionCommand(source.position))
            }
            val sourceAcks = source.other.multiAcks.takeIf { source.version >= CompanionManagement.MULTI_ACKS_MIN_VERSION }
            val desiredAcks = (sourceAcks ?: targetOther.multiAcks).takeIf {
                device.version >= CompanionManagement.MULTI_ACKS_MIN_VERSION
            }
            if (source.other.copy(multiAcks = desiredAcks) != targetOther.copy(multiAcks = targetOther.multiAcks.takeIf { device.version >= 7 })) {
                val data = byteArrayOf(38, source.other.manualAddContacts, source.other.telemetry, source.other.advertLocationPolicy)
                apply("Advert- und Kontaktparameter", if (desiredAcks == null) data else data + (if (desiredAcks) 1.toByte() else 0.toByte()))
            }
            if (source.pathWidth > 0 && device.pathWidth > 0 && source.pathWidth != device.pathWidth) {
                apply("Pfad-Hash-Länge", checkNotNull(CompanionManagement.pathHashCommand(source.pathWidth)))
            }
            if (source.scope.isNotEmpty() && targetScope.isNotEmpty() && !source.scope.contentEquals(targetScope)) {
                apply("Standard-Scope", source.scope.asCommand(63))
                check(canonicalScope(success(exchange(ChannelQuery()), "Standard-Scope lesen")).contentEquals(source.scope)) {
                    "Standard-Scope stimmt nach dem Schreiben nicht überein."
                }
            }
            report(progress.copy(stage = SetupRestoreStage.CHANNELS))
            for (index in targetChannels.indices) {
                val channel = source.channels[index]
                if (!channel.contentEquals(targetChannels[index])) {
                    apply("Kanal-Slot $index", channel.asCommand(32))
                    check(readChannel(index).contentEquals(channel)) { "Kanal-Slot $index stimmt nach dem Schreiben nicht überein." }
                }
                if (!ChannelQuery.emptySlot(channel)) {
                    report(progress.copy(restoredChannels = progress.restoredChannels + 1))
                }
            }

            step = "Kontakte"
            report(progress.copy(stage = SetupRestoreStage.CONTACTS))
            // Import reloads persisted contacts, and live adverts can change the remaining capacity.
            val currentContacts = readContacts().associateBy { it.contactKey() }
            val selectedContacts = contactsThatFit(source.contacts, currentContacts.keys, device.maxContacts)
            checkContactPaths(selectedContacts, device)
            report(progress.copy(skippedContacts = source.contacts.size - selectedContacts.size))
            var tableFull = false
            val writtenContacts = mutableListOf<ByteArray>()
            for (contact in selectedContacts) {
                val previous = currentContacts[contact.contactKey()]
                if (!contact.contentEquals(previous?.let { canonicalContact(it, device.version) })) {
                    if (previous == null && tableFull) {
                        report(progress.copy(skippedContacts = progress.skippedContacts + 1))
                        continue
                    }
                    attemptedWrite = true
                    val reply = exchange(CompanionRead(contact.asCommand(ADD_UPDATE_CONTACT), CompanionProtocol.OK))
                    if (previous == null && reply.contentEquals(byteArrayOf(CompanionProtocol.ERROR.toByte(), CONTACT_TABLE_FULL))) {
                        tableFull = true
                        report(progress.copy(skippedContacts = progress.skippedContacts + 1))
                        continue
                    }
                    expectWriteOk(reply, step)
                    writtenContacts.add(contact)
                }
                report(progress.copy(restoredContacts = progress.restoredContacts + 1))
            }
            if (writtenContacts.isNotEmpty()) {
                report(progress.copy(stage = SetupRestoreStage.VERIFYING_CONTACTS))
                val actual = readContacts().associateBy { it.contactKey() }
                check(writtenContacts.all { contact ->
                    contact.contentEquals(actual[contact.contactKey()]?.let { canonicalContact(it, device.version) })
                }) { "Kontaktbestand stimmt nach dem Schreiben nicht überein." }
            }
            step = "Einstellungen zurücklesen"
            report(progress.copy(stage = SetupRestoreStage.VERIFYING_SETTINGS))
            val verified = readSelf(desiredKey)
            val verifiedDevice = readDevice()
            val verifiedOther = CompanionManagement.otherParams(verified)
            check(verified.decodeToString(58) == source.name && source.radio.matches(SetupRadio.read(verified)) &&
                verified[2].toInt() == source.txPower && desiredRepeat == verifiedDevice.repeat &&
                positionsMatch(source.position, CompanionManagement.selfPosition(verified)) &&
                verifiedOther?.copy(multiAcks = verifiedOther.multiAcks.takeIf { device.version >= 7 }) ==
                source.other.copy(multiAcks = desiredAcks) &&
                (source.pathWidth == 0 || device.pathWidth == 0 || source.pathWidth == verifiedDevice.pathWidth)) {
                "Companion-Einstellungen stimmen nach dem Schreiben nicht vollständig überein."
            }
            return progress.skippedContacts
        } catch (error: IllegalStateException) {
            if (!attemptedWrite) throw error
            throw IllegalStateException("$step: ${error.message} Der Companion kann teilweise geändert sein.", error)
        }
    }

    private fun contactsThatFit(contacts: List<ByteArray>, existing: Set<String>, maxContacts: Int): List<ByteArray> {
        var freeSlots = (maxContacts - existing.size).coerceAtLeast(0)
        return buildList {
            for (contact in contacts) {
                if (contact.contactKey() in existing) {
                    add(contact)
                } else if (freeSlots > 0) {
                    add(contact)
                    freeSlots--
                }
            }
        }
    }

    private fun checkContactPaths(contacts: List<ByteArray>, device: Device) {
        check(device.pathWidth != 0 || contacts.all { it[35].toUByte().toInt() in 0..63 || it[35] == 0xff.toByte() }) {
            "Das Zielgerät unterstützt keine Mehrbyte-Pfade."
        }
    }

    private suspend fun readDevice(): Device {
        val frame = success(exchange(CompanionRead(Command.DEVICE_QUERY.encode(), Command.DEVICE_QUERY.expectedReply)), "Geräteinformationen lesen")
        check(frame.size >= 4) { "Unvollständige Geräteinformationen." }
        val version = frame[1].toUByte().toInt()
        check(version >= 3) { "Companion-Setup benötigt Protokollversion 3 oder höher." }
        val repeat = if (version >= 9) frame.getOrNull(80)?.toInt() else -1
        check(repeat != null && (version < 9 || repeat in 0..1)) { "Der Client-Repeater-Status fehlt." }
        val width = if (version >= 10) checkNotNull(CompanionManagement.pathHashWidth(frame)) {
            "Die Pfad-Hash-Länge fehlt."
        } else 0
        return Device(version, frame[2].toUByte().toInt() * 2, frame[3].toUByte().toInt(), repeat, width)
    }

    private suspend fun readSelf(identity: String): ByteArray {
        val frame = success(exchange(CompanionRead(Command.APP_START.encode(), Command.APP_START.expectedReply)), "Companion-Einstellungen lesen")
        check(frame.size in 59..89 && CompanionProtocol.selfIdentity(frame) == identity) {
            "Unvollständige Self-Info oder unerwartete Companion-Identität."
        }
        return frame
    }

    private suspend fun readGps(version: Int): CompanionGps {
        if (version < CompanionManagement.LOCATION_PARAMS_MIN_VERSION) return CompanionGps.UNAVAILABLE
        val frame = exchange(CompanionRead(byteArrayOf(CompanionManagement.GET_CUSTOM_VARS), CompanionManagement.CUSTOM_VARS))
        if (frame.isUnsupported()) return CompanionGps.UNAVAILABLE
        return CompanionManagement.gpsSetting(success(frame, "GPS-Status lesen")).also {
            check(it != CompanionGps.UNKNOWN) { "Der Companion meldet einen ungültigen GPS-Status." }
        }
    }

    private suspend fun readChannel(index: Int): ByteArray {
        val frame = success(exchange(ChannelQuery(index)), "Kanal-Slot $index lesen")
        check(ChannelQuery.emptySlot(frame) || ChannelQuery.configuredSlot(frame) != null) {
            "Ungültige Antwort für Kanal-Slot $index."
        }
        return frame.canonicalName(2, 34)
    }

    private suspend fun readKey(): ByteArray? {
        val frame = exchange(CompanionRead(byteArrayOf(23), CompanionProtocol.PRIVATE_KEY_REPLY))
        try {
            if (frame.isUnsupported()) return null
            check(success(frame, "Identität exportieren").size == 65) { "Ungültige Antwort beim Identitätsexport." }
            return frame.copyOfRange(1, 65)
        } finally {
            frame.fill(0)
        }
    }

    private suspend fun write(bytes: ByteArray, label: String) {
        expectWriteOk(exchange(CompanionRead(bytes, CompanionProtocol.OK)), label)
    }

    private fun expectWriteOk(reply: ByteArray, label: String) {
        check(!reply.isUnsupported()) { "$label: Die Firmware unterstützt den Befehl nicht." }
        check(success(reply, label).contentEquals(byteArrayOf(CompanionProtocol.OK.toByte()))) {
            "$label: Ungültige Bestätigung."
        }
    }

    private fun success(frame: ByteArray, label: String): ByteArray {
        val code = frame.firstOrNull()?.toUByte()?.toInt()
        val error = frame.getOrNull(1)?.toUByte()?.toInt()
        check(code != null && code != CompanionProtocol.DISABLED && code != CompanionProtocol.ERROR) {
            "$label: Companion hat den Befehl abgelehnt${error?.let { " (Code $it)" }.orEmpty()}."
        }
        return frame
    }

    private fun ByteArray.isUnsupported() =
        contentEquals(byteArrayOf(CompanionProtocol.DISABLED.toByte())) ||
            contentEquals(byteArrayOf(CompanionProtocol.ERROR.toByte(), 1))

    private fun canonicalScope(frame: ByteArray): ByteArray {
        if (frame.contentEquals(byteArrayOf(ChannelQuery.DEFAULT_FLOOD_SCOPE.toByte()))) return frame
        check(ChannelQuery.defaultScope(frame) != null) { "Ungültiger Standard-Scope." }
        return frame.canonicalName(1, 32)
    }

    private fun canonicalContact(frame: ByteArray, version: Int): ByteArray {
        require(frame.size == 148)
        val path = frame[35].toUByte().toInt()
        val length = when {
            path == 255 -> 0
            version < 10 -> path
            else -> ((path shr 6) + 1) * (path and 63)
        }
        check(length in 0..64) { "Ungültiger Kontaktpfad." }
        return frame.canonicalName(100, 132).apply { fill(0, 36 + length, 100) }
    }

    private fun ByteArray.canonicalName(start: Int, end: Int): ByteArray {
        val terminator = (start until end).firstOrNull { this[it] == 0.toByte() }
        check(terminator != null) { "Ungültiger Name in Companion-Antwort." }
        return copyOf().apply { fill(0, terminator, end) }
    }

    private fun ByteArray.asCommand(code: Byte) = copyOf().also { it[0] = code }
    private fun ByteArray.contactKey() = toHexString(1, 33)
    private val CompanionGps.available get() = this == CompanionGps.ENABLED || this == CompanionGps.DISABLED

    private fun positionsMatch(expected: CompanionPosition, actual: CompanionPosition?) = actual != null &&
        kotlin.math.abs(expected.latitudeE6 - actual.latitudeE6) <= 1 &&
        kotlin.math.abs(expected.longitudeE6 - actual.longitudeE6) <= 1

    companion object {
        const val IMPORT_PRIVATE_KEY: Byte = 24
        private const val ADD_UPDATE_CONTACT: Byte = 9
        private const val CONTACT_TABLE_FULL: Byte = 3
    }
}
