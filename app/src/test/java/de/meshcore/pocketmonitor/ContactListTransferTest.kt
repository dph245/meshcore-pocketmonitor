package de.meshcore.pocketmonitor

import de.meshcore.pocketmonitor.protocol.CompanionProtocol
import de.meshcore.pocketmonitor.protocol.ContactListResult
import de.meshcore.pocketmonitor.protocol.ContactListTransfer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactListTransferTest {
    @Test
    fun completeListBeforeWriteCallbackWithInterleavedPushes() {
        assertEquals(0x04, CompanionProtocol.GET_CONTACTS)
        val transfer = ContactListTransfer()
        assertTrue(transfer.receive(listFrame(2, 5)))
        val entries = listOf("Änne", "Repeater West", "Room", "Sensor", "Neuer Typ")
        val types = listOf(1, 2, 3, 4, 255)
        entries.forEachIndexed { i, name ->
            assertFalse(transfer.receive(byteArrayOf(0x88.toByte(), 20, -100)))
            assertFalse(transfer.receive(contactFrame("EE", "New Advert", 2).apply { this[0] = 0x8A.toByte() }))
            assertTrue(transfer.receive(contactFrame("AB0${i + 1}", name, types[i])))
            assertNull(transfer.result)
        }
        assertTrue(transfer.receive(listFrame(4, 0xFFFF_FFFEL)))
        assertNull(transfer.result)
        transfer.acknowledgeWrite()

        val complete = transfer.result as ContactListResult.Complete
        assertEquals(0xFFFF_FFFEL, complete.newestModifiedSeconds)
        assertEquals(entries, complete.contacts.map { it.name })
        assertEquals(types, complete.contacts.map { it.type })
        assertEquals(listOf("Client", "Repeater", "RoomServer", "Sensor", "Typ 0xFF"), complete.contacts.map { it.typeLabel })
        with(complete.contacts.first()) {
            assertEquals("AB01".padEnd(64, '0'), publicKey)
            assertEquals(0xA5, flags)
            assertEquals(0xFF, outPathLength)
            assertEquals("00010203", outPathHex.take(8))
            assertEquals("3E3F", outPathHex.takeLast(4))
            assertEquals(0x8000_0001L, lastAdvertSeconds)
            assertEquals(-12.345678, latitude, 0.0)
            assertEquals(98.765432, longitude, 0.0)
            assertEquals(0xFFFF_FFFEL, lastModifiedSeconds)
        }
    }

    @Test
    fun listCountIsLittleEndianAndEndIsRequired() {
        val transfer = ContactListTransfer()
        transfer.acknowledgeWrite()
        transfer.receive(listFrame(2, 300))
        repeat(300) { i ->
            transfer.receive(contactFrame(i.toString(16).padStart(4, '0'), "Node $i", 2))
        }
        assertNull(transfer.result)
        transfer.receive(listFrame(4, 1))
        val complete = transfer.result as ContactListResult.Complete
        assertEquals(300, complete.contacts.size)
        assertEquals("Node 299", complete.contacts.last().name)
    }

    @Test
    fun emptyListRequiresStartAndEnd() {
        val transfer = ContactListTransfer()
        transfer.acknowledgeWrite()
        transfer.receive(listFrame(2, 0))
        assertNull(transfer.result)
        transfer.receive(listFrame(4, 0))
        assertEquals(ContactListResult.Complete(emptyList(), 0), transfer.result)
    }

    @Test
    fun missingOrMalformedFramesAndDuplicateKeysRejectTheWholeList() {
        val start = listFrame(2, 1)
        val end = listFrame(4, 0)
        val contact = contactFrame("AB", "Node", 1)
        val invalidLists = listOf(
            listOf(start, end),
            listOf(contact, end),
            listOf(start, contact.copyOf(147), end),
            listOf(start.copyOf(4), contact, end),
            listOf(start, contact, end.copyOf(4)),
            listOf(start, start, contact, end),
            listOf(listFrame(2, 2), contact, contact, end),
            listOf(listFrame(2, 0), contact, end),
        )
        for (frames in invalidLists) {
            val transfer = ContactListTransfer()
            transfer.acknowledgeWrite()
            frames.forEach { assertTrue(transfer.receive(it)) }
            assertTrue(transfer.result is ContactListResult.Failed)
        }
    }

    @Test
    fun commandErrorIsTerminalAndWaitsForWriteAcknowledgment() {
        val transfer = ContactListTransfer()
        assertTrue(transfer.receive(byteArrayOf(0x01, 0x04)))
        assertNull(transfer.result)
        assertFalse(transfer.receive(listFrame(2, 0)))
        transfer.acknowledgeWrite()
        assertTrue((transfer.result as ContactListResult.Failed).message.contains("Code 4"))
    }
}

internal fun listFrame(code: Int, value: Long): ByteArray =
    ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).put(code.toByte()).putInt(value.toInt()).array()

internal fun contactFrame(prefix: String, name: String, type: Int): ByteArray =
    ByteBuffer.allocate(148).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(0x03)
        put(prefix.padEnd(64, '0').hexToByteArray())
        put(type.toByte())
        put(0xA5.toByte())
        put(0xFF.toByte())
        put(ByteArray(64) { it.toByte() })
        put(name.encodeToByteArray())
        put(0) // terminator followed by nonzero padding
        while (position() < 132) put(0x7F)
        putInt(0x8000_0001.toInt())
        putInt(-12_345_678)
        putInt(98_765_432)
        putInt(0xFFFF_FFFE.toInt())
    }.array()
