package de.meshcore.pocketmonitor

import de.meshcore.pocketmonitor.protocol.CompanionContact
import de.meshcore.pocketmonitor.protocol.CompanionRead
import de.meshcore.pocketmonitor.protocol.PrivateRetry
import de.meshcore.pocketmonitor.protocol.PrivateRetryOptions
import de.meshcore.pocketmonitor.protocol.PrivateSend
import de.meshcore.pocketmonitor.protocol.PrivateSendReceipt
import de.meshcore.pocketmonitor.protocol.PrivateSendStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateSendTest {
    private val key = "012345ABCDEF" + "00".repeat(26)
    private val contact = CompanionContact(key, "Ada", 1, 0, 0, "", 0, 0.0, 0.0, 0)

    @Test fun `wire encoding uses six bytes for text and the full key for reset`() {
        assertEquals("02000578563412012345abcdefc3a4",
            PrivateSend.command(key, 0x12345678, 5, "ä".encodeToByteArray()).toHexString())
        assertEquals("0d" + key.lowercase(), PrivateSend.resetPath(key).toHexString())
        assertEquals(135, PrivateSend.textLimit(151))
        assertEquals(158, PrivateSend.textLimit(247))
    }

    @Test fun `recipient key must match and prefix must be unambiguous`() {
        val prefix = key.take(12)
        val collision = contact.copy(publicKey = prefix + "ff".repeat(26))
        assertEquals(contact, PrivateSend.contact(key, mapOf(prefix to listOf(contact))))
        assertNull(PrivateSend.contact(key, mapOf(prefix to listOf(collision))))
        assertNull(PrivateSend.contact(key, mapOf(prefix to listOf(contact, collision))))
    }

    @Test fun `send receipt can precede GATT write callback and ACK fields are little endian`() {
        val reply = CompanionRead(byteArrayOf(2), PrivateSend.SENT)
        val ack = "827856349210000000".hexToByteArray()
        reply.receive(ack)
        reply.receive("060178563492983a0000".hexToByteArray())
        assertNull(reply.result)
        reply.acknowledgeWrite()
        val receipt = checkNotNull(PrivateSendReceipt.decode(checkNotNull(reply.result)))
        assertTrue(receipt.flood)
        assertEquals(0x92345678L, receipt.token)
        assertEquals(15_000L, receipt.estimateMs)
        assertEquals(20_000L, receipt.waitMs)
        assertEquals(receipt.token, PrivateSend.ack(ack))
        assertNull(PrivateSend.ack(ack.copyOf(8)))
        assertNull(PrivateSend.ack("820000000010000000".hexToByteArray()))
        assertNull(PrivateSendReceipt.decode("060278563492983a0000".hexToByteArray()))
    }

    @Test fun `three path attempts then reset and three flood attempts`() {
        val tx = PrivateRetry(PrivateRetryOptions(resetPathForFlood = true))
        repeat(3) { attempt ->
            assertEquals(PrivateSendStep.SEND, tx.next())
            assertEquals(attempt, tx.attempt)
            tx.receipt(PrivateSendReceipt(false, 10L + attempt, 500))
        }
        assertEquals(PrivateSendStep.RESET_PATH, tx.next())
        tx.pathReset()
        repeat(3) { attempt ->
            assertEquals(PrivateSendStep.SEND, tx.next())
            assertEquals(3 + attempt, tx.attempt)
            tx.receipt(PrivateSendReceipt(true, 20L + attempt, 1000))
        }
        assertEquals(PrivateSendStep.DONE, tx.next())
        assertFalse(tx.acknowledged)
    }

    @Test fun `unrelated tokens do not stop retries and a late token from an earlier try does`() {
        val tx = PrivateRetry(PrivateRetryOptions(resetPathForFlood = true))
        tx.receipt(PrivateSendReceipt(false, 1, 500))
        tx.acknowledge(0)
        tx.acknowledge(2)
        assertEquals(PrivateSendStep.SEND, tx.next())
        tx.receipt(PrivateSendReceipt(false, 2, 500))
        tx.acknowledge(1)
        assertTrue(tx.acknowledged)
        assertEquals(PrivateSendStep.DONE, tx.next())
    }

    @Test fun `path reset is opt in and fresh path after a flood stops fallback`() {
        val tx = PrivateRetry(PrivateRetryOptions(pathAttempts = 1))
        tx.receipt(PrivateSendReceipt(false, 1, 500))
        assertEquals(PrivateSendStep.DONE, tx.next())

        val flood = PrivateRetry(PrivateRetryOptions(floodAttempts = 2))
        flood.receipt(PrivateSendReceipt(true, 1, 500))
        assertEquals(PrivateSendStep.SEND, flood.next())
        flood.pathUpdated()
        assertEquals(PrivateSendStep.DONE, flood.next())
    }

    @Test fun `flood selected by firmware spends the configured flood budget directly`() {
        val tx = PrivateRetry(PrivateRetryOptions(floodAttempts = 1, resetPathForFlood = true))
        tx.receipt(PrivateSendReceipt(true, 1, 500))
        assertEquals(PrivateSendStep.DONE, tx.next())
        val changed = PrivateRetry(PrivateRetryOptions(pathAttempts = 1, resetPathForFlood = true))
        changed.receipt(PrivateSendReceipt(false, 1, 500))
        assertEquals(PrivateSendStep.RESET_PATH, changed.next())
        changed.pathReset()
        changed.receipt(PrivateSendReceipt(false, 2, 500))
        assertEquals(PrivateSendStep.DONE, changed.next())
    }
}
