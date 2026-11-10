package de.meshcore.pocketmonitor

import de.meshcore.pocketmonitor.protocol.CompanionProtocol
import de.meshcore.pocketmonitor.protocol.CompanionProtocol.Command
import de.meshcore.pocketmonitor.protocol.PendingReply
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
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
}
