package de.meshcore.pocketmonitor.backup

import de.meshcore.pocketmonitor.protocol.ChannelChange
import de.meshcore.pocketmonitor.protocol.CompanionManagement
import de.meshcore.pocketmonitor.protocol.CompanionPosition
import de.meshcore.pocketmonitor.protocol.CompanionRead
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionSetupTransferTest {
    @Test
    fun migratesSetupAndOptionalIdentityAndKeepsAdditionalContacts() = runBlocking {
        val source = Companion(0x11, name = "Quelle", frequency = 869_525, position = CompanionPosition(123, 456))
        source.contacts.add(contact(0x37))
        source.contacts.add(contact(0x39))
        source.channels[0] = channel(0, "Test")
        source.scope = (checkNotNull(CompanionManagement.scopeCommand("#test"))).copyOf().apply { this[0] = 0x1c }
        val target = Companion(0x22, name = "Ziel", frequency = 868_000)
        target.contacts.addAll(listOf(contact(0x38), contact(0x39)))
        target.channels[1] = channel(1, "Alt")
        val captured = source.transfer.capture(source.identity, includeIdentity = true)
        val serialized = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use(captured::write)
        }.toByteArray()
        val restored = DataInputStream(serialized.inputStream()).use(CompanionSetup::read)
        val progress = mutableListOf<SetupRestoreProgress>()
        target.transfer.restore(restored, target.identity, restoreIdentity = true, onProgress = progress::add)

        assertEquals(source.identity, target.identity)
        assertEquals(source.name, target.name)
        assertEquals(source.frequency, target.frequency)
        assertEquals(source.position, target.position)
        assertArrayEquals(source.scope, target.scope)
        for (index in source.channels.indices) assertArrayEquals(source.channels[index], target.channels[index])
        assertEquals(listOf(0x38.toByte(), 0x39.toByte(), 0x37.toByte()), target.contacts.map { it[1] })
        assertEquals(listOf(0x37.toByte()), target.contactWrites)
        assertEquals(1, target.identityChanges)
        assertEquals(listOf(0, 1), progress.map { it.restoredChannels }.distinct())
        assertEquals(listOf(0, 1, 2), progress.map { it.restoredContacts }.distinct())
        assertTrue(progress.any { it.stage == SetupRestoreStage.VERIFYING_CONTACTS && it.restoredContacts == 2 })
        assertEquals(SetupRestoreProgress(SetupRestoreStage.VERIFYING_SETTINGS, 1, 2), progress.last())
    }

    @Test
    fun disabledIdentityExportAndImportLeaveOtherFeaturesAvailable() = runBlocking {
        val source = Companion(0x11, name = "Quelle")
        source.keyExport = false
        assertNull(source.transfer.capture(source.identity, includeIdentity = true).privateKey)
        source.keyExport = true
        val captured = source.transfer.capture(source.identity, includeIdentity = true)
        val target = Companion(0x22, name = "Ziel")
        target.keyImport = false
        val identity = target.identity
        val error = runCatching { target.transfer.restore(captured, identity, restoreIdentity = true) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("unterstützt den Befehl nicht"))
        assertEquals("Ziel", target.name)
        assertEquals(identity, target.identity)
        target.transfer.restore(captured, identity, restoreIdentity = false)
        assertEquals("Quelle", target.name)
        assertEquals(identity, target.identity)
    }

    @Test
    fun missingChannelSlotsStopBeforeAnyWrite() = runBlocking {
        val source = Companion(0x11, name = "Quelle")
        source.channels[1] = channel(1, "Test")
        val captured = source.transfer.capture(source.identity, includeIdentity = true)
        val target = Companion(0x22, name = "Ziel", slotCount = 1)
        val result = runCatching { target.transfer.restore(captured, target.identity, restoreIdentity = true) }
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Kanal-Slots"))
        assertEquals(0, target.identityChanges)
        assertEquals("Ziel", target.name)
    }

    @Test
    fun smallerContactTableKeepsExistingContactsAndStillMigratesIdentity() = runBlocking {
        val source = Companion(0x11, name = "Quelle")
        source.contacts.addAll(listOf(contact(0x31), contact(0x32), contact(0x33)))
        val target = Companion(0x22, name = "Ziel", advertisedContactCapacity = 2)
        target.contacts.add(contact(0x33).apply { this[34] = 1 })
        val captured = source.transfer.capture(source.identity, includeIdentity = true)

        val skipped = target.transfer.restore(captured, target.identity, restoreIdentity = true)

        assertEquals(1, skipped)
        assertEquals(source.identity, target.identity)
        assertEquals("Quelle", target.name)
        assertEquals(listOf(0x33.toByte(), 0x31.toByte()), target.contacts.map { it[1] })
        assertArrayEquals(source.contacts.last(), target.contacts.first())
    }

    @Test
    fun tableFullReplySkipsNewContactsAndStillUpdatesExistingContact() = runBlocking {
        val source = Companion(0x11, name = "Quelle")
        source.contacts.addAll(listOf(contact(0x31), contact(0x32), contact(0x33)))
        val target = Companion(0x22, name = "Ziel", advertisedContactCapacity = 4, actualContactCapacity = 2)
        val extra = contact(0x34)
        target.contacts.addAll(listOf(extra, contact(0x33).apply { this[34] = 1 }))
        val captured = source.transfer.capture(source.identity, includeIdentity = false)

        val progress = mutableListOf<SetupRestoreProgress>()
        val skipped = target.transfer.restore(captured, target.identity, restoreIdentity = false, onProgress = progress::add)

        assertEquals(2, skipped)
        assertEquals(listOf(0, 1, 2), progress.map { it.skippedContacts }.distinct())
        assertEquals(listOf(0, 1), progress.map { it.restoredContacts }.distinct())
        assertEquals("Quelle", target.name)
        assertEquals(listOf(0x31.toByte(), 0x33.toByte()), target.contactWrites)
        assertArrayEquals(extra, target.contacts.first())
        assertArrayEquals(source.contacts.last(), target.contacts.last())
    }

    private class Companion(
        private var publicByte: Byte,
        var name: String,
        var frequency: Int = 869_525,
        var position: CompanionPosition = CompanionPosition.ZERO,
        slotCount: Int = 2,
        private val advertisedContactCapacity: Int = 100,
        private val actualContactCapacity: Int = advertisedContactCapacity,
    ) {
        val identity get() = ByteArray(32) { publicByte }.toHexString()
        var keyExport = true
        var keyImport = true
        var identityChanges = 0
        val channels = MutableList(slotCount) { index -> ChannelChange.clearCommand(index).apply { this[0] = 0x12 } }
        val contacts = mutableListOf<ByteArray>()
        val contactWrites = mutableListOf<Byte>()
        var scope = byteArrayOf(0x1c)
        val transfer = CompanionSetupTransfer(::exchange) { contacts.toList() }

        private fun exchange(request: CompanionRead): ByteArray {
            val bytes = request.command
            return when (bytes[0].toInt()) {
                1 -> selfInfo()
                0x16 -> ByteArray(82).apply {
                    this[0] = 0x0d
                    this[1] = 14
                    this[2] = (advertisedContactCapacity / 2).toByte()
                    this[3] = channels.size.toByte()
                }
                0x28 -> byteArrayOf(0x15)
                0x1f -> channels[bytes[1].toUByte().toInt()].copyOf()
                0x40 -> scope.copyOf()
                23 -> if (keyExport) byteArrayOf(14) + ByteArray(64) { publicByte } else byteArrayOf(15)
                24 -> if (keyImport) byteArrayOf(0).also {
                    publicByte = bytes[1]
                    identityChanges++
                } else byteArrayOf(15)
                8 -> byteArrayOf(0).also { name = bytes.decodeToString(1) }
                9 -> {
                    contactWrites.add(bytes[1])
                    val key = bytes.copyOfRange(1, 33)
                    val existing = contacts.indexOfFirst { it.copyOfRange(1, 33).contentEquals(key) }
                    val contact = bytes.copyOf().apply { this[0] = 3 }
                    when {
                        existing >= 0 -> byteArrayOf(0).also { contacts[existing] = contact }
                        contacts.size < actualContactCapacity -> byteArrayOf(0).also { contacts.add(contact) }
                        else -> byteArrayOf(1, 3)
                    }
                }
                11 -> byteArrayOf(0).also { frequency = ByteBuffer.wrap(bytes, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int }
                14 -> byteArrayOf(0).also {
                    val fields = ByteBuffer.wrap(bytes, 1, 8).order(ByteOrder.LITTLE_ENDIAN)
                    position = CompanionPosition(fields.int, fields.int)
                }
                32 -> byteArrayOf(0).also { channels[bytes[1].toInt()] = bytes.copyOf().apply { this[0] = 0x12 } }
                63 -> byteArrayOf(0).also { scope = bytes.copyOf().apply { this[0] = 0x1c } }
                else -> error("Unbekannter Testbefehl")
            }
        }

        private fun selfInfo(): ByteArray = ByteBuffer.allocate(58 + name.encodeToByteArray().size)
            .order(ByteOrder.LITTLE_ENDIAN).apply {
                put(5).put(1).put(10).put(22)
                put(ByteArray(32) { publicByte })
                putInt(position.latitudeE6).putInt(position.longitudeE6)
                putInt(0)
                putInt(frequency).putInt(62_500).put(8).put(5)
                put(name.encodeToByteArray())
            }.array()
    }

    private fun contact(publicByte: Byte) = ByteArray(148).apply {
        this[0] = 3
        fill(publicByte, 1, 33)
        this[33] = 1
        this[35] = 0xff.toByte()
        "Testkontakt".encodeToByteArray().copyInto(this, 100)
    }

    private fun channel(index: Int, name: String) = ChannelChange.clearCommand(index).apply {
        this[0] = 0x12
        name.encodeToByteArray().copyInto(this, 2)
        fill(0x42, 34)
    }
}
