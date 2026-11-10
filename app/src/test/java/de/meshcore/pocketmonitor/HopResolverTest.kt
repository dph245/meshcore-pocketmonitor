package de.meshcore.pocketmonitor

import de.meshcore.pocketmonitor.ble.ContactStatus
import de.meshcore.pocketmonitor.protocol.CompanionContact
import de.meshcore.pocketmonitor.protocol.ContactListResult
import de.meshcore.pocketmonitor.protocol.ContactListTransfer
import de.meshcore.pocketmonitor.protocol.DecodeStatus
import de.meshcore.pocketmonitor.protocol.HopResolver
import de.meshcore.pocketmonitor.protocol.HopStatus
import de.meshcore.pocketmonitor.protocol.MeshPacketDecoder
import de.meshcore.pocketmonitor.protocol.MeshPath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HopResolverTest {
    private val repeater = contact("AB123456789ABCDE", "Repeater West", 2)
    private val secondRepeater = contact("AB12355678FFFFFF", "Repeater Ost", 2)
    private val client = contact("AB12345678EEEEEE", "Client", 1)
    private val room = contact("AB9876543210FFFF", "RoomServer", 3)
    private val resolver = HopResolver(listOf(repeater, secondRepeater, client, room))

    @Test
    fun ordinaryRfPathsUseOneTwoOrThreeBytesAndOnlyCountRepeaterCollisions() {
        val cases = listOf(
            Triple("AB", "FF", listOf(repeater, secondRepeater)),
            Triple("AB12", "AB98", listOf(repeater, secondRepeater)),
            Triple("AB1234", "AB9876", listOf(repeater)),
        )
        cases.forEachIndexed { index, (known, unknown, matches) ->
            val bytes = ackPath(index + 1, known + unknown)
            val original = bytes.copyOf()
            val path = requireNotNull(MeshPacketDecoder.decode(bytes).path)
            val (first, second) = resolver.resolve(path)
            assertEquals(index + 1, path.prefixSize)
            assertEquals(listOf(known, unknown), path.prefixes)
            assertEquals(matches, first.matches)
            assertEquals(if (matches.size == 1) HopStatus.UNIQUE else HopStatus.AMBIGUOUS, first.status)
            assertEquals(HopStatus.UNKNOWN, second.status)
            assertEquals(unknown, second.prefix)
            assertArrayEquals(original, bytes)
        }
        val reserved = MeshPacketDecoder.decode(ackPath(4, "AB12345600000000"))
        assertEquals(DecodeStatus.UNSUPPORTED, reserved.status)
        assertNull(reserved.path)
    }

    @Test
    fun traceResolvesPlannedPrefixesAtAllWidthsAndDoesNotUseSnrBytesAsKeys() {
        for (mode in 0..3) {
            val width = 1 shl mode
            val prefix = repeater.publicKey.take(width * 2)
            // Direct TRACE, one measured SNR byte, tag, auth, flags and two planned hops.
            val bytes = ("2601AB" + "0100000002000000" + "0$mode" + prefix + "FF".repeat(width)).hexToByteArray()
            val path = requireNotNull(MeshPacketDecoder.decode(bytes).path)
            val (first, second) = resolver.resolve(path)
            assertEquals(width, path.prefixSize)
            assertEquals(listOf(-21.25), path.traceSnrDb)
            assertEquals(prefix, first.prefix)
            assertEquals(if (width >= 4) HopStatus.UNIQUE else HopStatus.AMBIGUOUS, first.status)
            assertEquals("FF".repeat(width), second.prefix)
            assertEquals(HopStatus.UNKNOWN, second.status)
        }
    }

    @Test
    fun existingPathResolvesAgainAfterFullRefreshAndFailedRefreshKeepsLastCompleteBook() {
        val path = requireNotNull(MeshPacketDecoder.decode(ackPath(2, "AB12FFFF")).path)
        val session = ContactStatus(journalName = "first.tsv", canRefresh = true)
        assertEquals(HopStatus.UNKNOWN, session.resolverFor("first.tsv").resolve(path).first().status)

        val initial = session.completed(download(repeater))
        assertEquals("Repeater West", initial.resolverFor("first.tsv").resolve(path).first().matches.single().name)
        val loading = initial.copy(canRefresh = false, loading = true)
        val refresh = ContactListTransfer().apply {
            acknowledgeWrite()
            receive(listFrame(2, 2))
            receive(contactFrame("AB12", "Umbenannt", 2))
        }
        assertNull(refresh.result)
        assertSame(initial.book, loading.book)
        refresh.receive(listFrame(4, 1))
        val failed = loading.completed(requireNotNull(refresh.result))
        assertSame(initial.book, failed.book)

        val renamed = contact("AB123456789ABCDE", "Umbenannt", 2)
        val updated = failed.completed(download(renamed, client))
        val hop = updated.resolverFor("first.tsv").resolve(path).first()
        assertEquals(HopStatus.UNIQUE, hop.status)
        assertEquals("Umbenannt", hop.matches.single().name)
        val empty = updated.completed(download())
        assertEquals(HopStatus.UNKNOWN, empty.resolverFor("first.tsv").resolve(path).first().status)
        assertEquals(listOf("AB12", "FFFF"), path.prefixes)
    }

    @Test
    fun connectionSwitchNeverUsesAnotherJournalsContactsEvenWhenOldViewIsPaused() {
        val path = MeshPath(0x41, 2, listOf("AB12"))
        val first = ContactStatus(journalName = "first.tsv").completed(download(repeater))
        val disconnected = ContactStatus()
        val second = ContactStatus(journalName = "second.tsv").completed(download(secondRepeater))
        assertEquals(HopStatus.UNKNOWN, disconnected.resolverFor("first.tsv").resolve(path).single().status)
        assertEquals(HopStatus.UNKNOWN, second.resolverFor("first.tsv").resolve(path).single().status)
        assertEquals(HopStatus.UNKNOWN, first.resolverFor("second.tsv").resolve(path).single().status)
        assertEquals(secondRepeater, second.resolverFor("second.tsv").resolve(path).single().matches.single())
    }

    private fun ackPath(width: Int, prefixes: String): ByteArray =
        byteArrayOf(0x0D, (((width - 1) shl 6) or 2).toByte()) + prefixes.hexToByteArray() + ByteArray(4)

    private fun contact(prefix: String, name: String, type: Int): CompanionContact =
        requireNotNull(CompanionContact.decode(contactFrame(prefix, name, type)))

    private fun download(vararg contacts: CompanionContact): ContactListResult {
        val transfer = ContactListTransfer()
        transfer.acknowledgeWrite()
        transfer.receive(listFrame(2, contacts.size.toLong()))
        for (contact in contacts) transfer.receive(contactFrame(contact.publicKey, contact.name, contact.type))
        transfer.receive(listFrame(4, 0))
        return requireNotNull(transfer.result)
    }
}
