package de.meshcore.pocketmonitor

import de.meshcore.pocketmonitor.protocol.CompanionAction
import de.meshcore.pocketmonitor.protocol.CompanionContact
import de.meshcore.pocketmonitor.protocol.CompanionManagement
import de.meshcore.pocketmonitor.protocol.ContactCategory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionManagementTest {
    @Test
    fun defaultScopeUsesFixedUtf8FieldAndVerifiesReturnedNameAndKey() {
        val set = checkNotNull(CompanionManagement.scopeCommand(" grüße "))
        val name = "#grüße".encodeToByteArray()
        assertArrayEquals(byteArrayOf(0x3f) + name.copyOf(31) +
            "6ee2ba1397d3b2785eeecb3efe1889f3".hexToByteArray(), set)

        val readBack = set.copyOf().apply {
            this[0] = 0x1c
            this[2 + name.size] = 0x7f // stale bytes beyond the terminator
        }
        assertTrue(CompanionManagement.scopeMatches(set, readBack))
        assertFalse(CompanionManagement.scopeMatches(set, readBack.copyOf().apply { this[47] = 0 }))
        assertFalse(CompanionManagement.scopeMatches(set, byteArrayOf(1, 2)))
        assertNull(CompanionManagement.scopeCommand("ä".repeat(15)))
        assertNull(CompanionManagement.scopeCommand("#de ni"))

        val clear = checkNotNull(CompanionManagement.scopeCommand(""))
        assertArrayEquals(byteArrayOf(0x3f), clear)
        assertTrue(CompanionManagement.scopeMatches(clear, byteArrayOf(0x1c)))
        assertFalse(CompanionManagement.scopeMatches(clear, readBack))
    }

    @Test
    fun contactCommandsUseFullKeyAndInitializeNewContactWithoutLearnedPath() {
        val key = "ab".repeat(32)
        for (category in listOf(ContactCategory.CHAT, ContactCategory.REPEATER, ContactCategory.ROOMSERVER)) {
            val action = CompanionAction.AddContact(key, "Möwe", category)
            val add = checkNotNull(CompanionManagement.addContact(action))
            assertEquals(0x09, add[0].toInt())
            assertEquals(144, add.size)
            val nameEnd = 100 + "Möwe".encodeToByteArray().size
            assertArrayEquals(ByteArray(144 - nameEnd), add.copyOfRange(nameEnd, 144))
            val contact = checkNotNull(CompanionContact.decode(add.copyOf(148).apply { this[0] = 3 }))
            assertEquals(key.uppercase(), contact.publicKey)
            assertEquals(255, contact.outPathLength)
            assertEquals("Möwe", contact.name)
            assertTrue(category.contains(contact))
            assertFalse(ContactCategory.OTHER.contains(contact))
        }
        assertArrayEquals(byteArrayOf(0x0f) + key.hexToByteArray(), CompanionManagement.removeContact(key))
        assertNull(CompanionManagement.removeContact(key.dropLast(1)))
        assertNull(CompanionManagement.addContact(CompanionAction.AddContact(key, "ä".repeat(16), ContactCategory.CHAT)))
    }

    @Test
    fun cliReservesFrameOverheadAndAcceptsOnlyCorrelatedReplies() {
        val limit = CompanionManagement.cliTextLimit(23)
        assertEquals(16, limit)
        assertArrayEquals(byteArrayOf(0x42) + "01|x ".encodeToByteArray(),
            CompanionManagement.cliCommand("x", "01|", limit))
        assertNull(CompanionManagement.cliCommand("get name\nreboot", "01|", 160))
        assertNull(CompanionManagement.cliCommand("a".repeat(17), "01|", limit))
        assertEquals("OK", CompanionManagement.cliReply(byteArrayOf(0x1d) + "01|OK".encodeToByteArray(), "01|"))
        assertEquals("", CompanionManagement.cliReply(byteArrayOf(0x1d) + "01|".encodeToByteArray(), "01|"))
        assertNull(CompanionManagement.cliReply(byteArrayOf(0x1d) + "00|OK".encodeToByteArray(), "01|"))
    }
}
