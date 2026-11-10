package de.meshcore.pocketmonitor

import de.meshcore.pocketmonitor.protocol.CompanionProtocol
import de.meshcore.pocketmonitor.protocol.CompanionProtocol.Command
import de.meshcore.pocketmonitor.protocol.DiscoverCommandReply
import de.meshcore.pocketmonitor.protocol.DiscoverReply
import de.meshcore.pocketmonitor.protocol.PendingReply
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionProtocolTest {
    @Test
    fun startupWritesOnlyDocumentedCommands() {
        assertArrayEquals(byteArrayOf(0x01, 0, 0, 0, 0, 0, 0, 0) + "PocketMonitor".encodeToByteArray(),
            Command.APP_START.encode())
        assertArrayEquals(byteArrayOf(0x16, 0x03), Command.DEVICE_QUERY.encode())
    }

    @Test
    fun unsolicitedNotificationsDoNotAdvanceHandshake() {
        val pending = PendingReply(Command.APP_START)
        assertFalse(pending.acknowledgeWrite())
        assertFalse(pending.receive(CompanionProtocol.RF_LOG))
        assertFalse(pending.receive(0x0d))
        assertFalse(pending.receive(null))
        assertTrue(pending.receive(0x05))
    }

    @Test
    fun earlyCompanionReplyStillWaitsForWriteCompletion() {
        val pending = PendingReply(Command.DEVICE_QUERY)
        assertFalse(pending.receive(0x0d))
        assertTrue(pending.acknowledgeWrite())
    }

    @Test
    fun discoverCommandRequestsFullRepeaterKeysWithLittleEndianTag() {
        assertArrayEquals("378004785634f2".hexToByteArray(), CompanionProtocol.encodeDiscover(0xf2345678u))
    }

    @Test
    fun discoverWaitsForGattAndFirmwareInEitherOrderAndIgnoresPushes() {
        val early = DiscoverCommandReply()
        early.receive(byteArrayOf(0))
        assertNull(early.result)
        early.acknowledgeWrite()
        assertEquals(DiscoverReply.Accepted, early.result)

        val late = DiscoverCommandReply()
        late.acknowledgeWrite()
        for (hex in listOf("", "88ff", "8e010203", "0200000000", "0d08")) {
            late.receive(hex.hexToByteArray())
            assertNull(late.result)
        }
        late.receive(byteArrayOf(0))
        assertEquals(DiscoverReply.Accepted, late.result)
    }

    @Test
    fun discoverDistinguishesErrorsDisabledAndMalformedReplies() {
        fun reply(hex: String) = DiscoverCommandReply().apply {
            receive(hex.hexToByteArray())
            acknowledgeWrite()
        }.result

        assertEquals(DiscoverReply.Rejected("Paketpuffer voll (Companion-Code 3)."), reply("0103"))
        assertEquals(DiscoverReply.Rejected("Befehl nicht unterstützt (Companion-Code 1).", true), reply("0101"))
        assertEquals(DiscoverReply.Rejected("Unbekannter Fehler (Companion-Code 255)."), reply("01ff"))
        assertTrue((reply("0f") as DiscoverReply.Rejected).unsupported)
        for (hex in listOf("0000", "01", "010300", "0f00")) assertEquals(DiscoverReply.Malformed, reply(hex))
    }
}
