package de.meshcore.pocketmonitor

import de.meshcore.pocketmonitor.protocol.CompanionContact
import de.meshcore.pocketmonitor.protocol.CompanionRead
import de.meshcore.pocketmonitor.protocol.PrivateRetry
import de.meshcore.pocketmonitor.protocol.PrivateRetryOptions
import de.meshcore.pocketmonitor.protocol.PrivateRoute
import de.meshcore.pocketmonitor.protocol.PrivateSend
import de.meshcore.pocketmonitor.protocol.PrivateSendReceipt
import de.meshcore.pocketmonitor.protocol.PrivateSendStep
import org.junit.Assert.assertArrayEquals
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

    @Test fun `four retries after the first attempt in each phase`() {
        val tx = PrivateRetry(PrivateRetryOptions(pathAttempts = 5, floodAttempts = 5, resetPathForFlood = true))
        repeat(5) { attempt ->
            assertEquals(PrivateSendStep.SEND, tx.next())
            assertEquals(attempt, tx.attempt)
            tx.receipt(PrivateSendReceipt(false, 1L + attempt, 500))
        }
        assertEquals(PrivateSendStep.RESET_PATH, tx.next())
        assertFalse(tx.floodPhase)
        tx.pathReset()
        repeat(5) { attempt ->
            assertTrue(tx.floodPhase)
            assertEquals(PrivateSendStep.SEND, tx.next())
            assertEquals(5 + attempt, tx.attempt)
            tx.receipt(PrivateSendReceipt(true, 6L + attempt, 500))
        }
        assertEquals(PrivateSendStep.DONE, tx.next())
    }

    @Test fun `manual path encodes hash width and preserves other contact fields`() {
        val original = ByteArray(148) { it.toByte() }.apply {
            this[0] = 3
            key.hexToByteArray().copyInto(this, 1)
        }
        val route = checkNotNull(PrivateRoute.create(2, listOf(" 123a ", "b456")))
        val set = PrivateSend.setPath(original, route)
        assertEquals(9, set[0].toInt())
        assertArrayEquals(original.copyOfRange(1, 35), set.copyOfRange(1, 35))
        assertEquals(0x42, set[35].toInt())
        assertEquals("123ab456", set.copyOfRange(36, 40).toHexString())
        assertArrayEquals(ByteArray(60), set.copyOfRange(40, 100))
        assertArrayEquals(original.copyOfRange(100, 148), set.copyOfRange(100, 148))

        val verified = checkNotNull(CompanionContact.decode(set.copyOf().apply { this[0] = 3 }))
        assertEquals(route.signature, PrivateSend.pathSignature(verified))
        assertNull(PrivateSend.pathSignature(verified.copy(outPathLength = 255)))
        assertArrayEquals(byteArrayOf(0x1e) + key.hexToByteArray(), PrivateSend.readContact(key))
        assertNull(PrivateRoute.create(2, listOf("ab", "1234")))
        assertNull(PrivateRoute.create(3, List(22) { "ab1234" }))
        assertEquals(21, PrivateRoute.maxHops(3))
    }

    @Test fun `path update identifies the contact and its outgoing record supplies the hops`() {
        val update = byteArrayOf(0x81.toByte()) + key.hexToByteArray()
        assertEquals(key, PrivateSend.pathUpdatedKey(update))
        assertNull(PrivateSend.pathUpdatedKey(update.copyOf(32)))
        assertNull(PrivateSend.pathUpdatedKey(update.apply { this[0] = 0x82.toByte() }))

        val path = checkNotNull(PrivateSend.outgoingPath(contact.copy(
            outPathLength = 0x82, outPathHex = "a1b2c3d4e5f6" + "00".repeat(58),
        )))
        assertEquals(3, path.prefixSize)
        assertEquals(listOf("A1B2C3", "D4E5F6"), path.prefixes)
        assertTrue(checkNotNull(PrivateSend.outgoingPath(contact)).prefixes.isEmpty())
        assertNull(PrivateSend.outgoingPath(contact.copy(outPathLength = 0xff)))
        assertNull(PrivateSend.outgoingPath(contact.copy(outPathLength = 0xc0)))
        assertNull(PrivateSend.outgoingPath(contact.copy(outPathLength = 0x96)))
        assertNull(PrivateSend.outgoingPath(contact.copy(outPathLength = 2, outPathHex = "AA")))
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
