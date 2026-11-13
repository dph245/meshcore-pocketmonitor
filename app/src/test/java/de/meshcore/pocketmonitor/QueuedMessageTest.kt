package de.meshcore.pocketmonitor

import de.meshcore.pocketmonitor.protocol.MessageAddress
import de.meshcore.pocketmonitor.protocol.MessageSyncStep
import de.meshcore.pocketmonitor.protocol.QueuedMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuedMessageTest {
    @Test
    fun decodesBothFirmwareLayouts() {
        val channel = QueuedMessage(MessageAddress.Channel(3), 0xf2345678, "Ada: Grüße", null)
        val text = channel.text.encodeToByteArray()
        assertEquals(channel, QueuedMessage.decode("0803ff00785634f2".hexToByteArray() + text))
        assertEquals(channel, QueuedMessage.decode("11f40000034000785634f2".hexToByteArray() + text))

        val pm = QueuedMessage(MessageAddress.Contact("ABCDEF012345"), 1, "Text: mit Doppelpunkt", null)
        val body = pm.text.encodeToByteArray()
        assertEquals(pm, QueuedMessage.decode("07abcdef012345ff0001000000".hexToByteArray() + body))
        assertEquals(pm, QueuedMessage.decode("10f40000abcdef012345400001000000".hexToByteArray() + body))
    }

    @Test
    fun preservesRoomSenderPrefixAndSkipsNonChatOrDamagedFrames() {
        val signed = "07abcdef012345ff0201000000c001cafe".hexToByteArray()
        assertEquals("C001CAFE", QueuedMessage.decode(signed + "Room text".encodeToByteArray())?.signedSenderPrefix)
        listOf(signed.copyOf(16), "0803ff00785634".hexToByteArray(),
            "07abcdef012345ff010100000054".hexToByteArray(),
            "0803ff0001000000ff".hexToByteArray(), byteArrayOf(0x1b)).forEach {
            assertNull(QueuedMessage.decode(it))
        }
    }

    @Test
    fun waitsForGattAndCommitInEitherOrder() {
        for (ackFirst in listOf(true, false)) {
            val step = MessageSyncStep()
            if (ackFirst) step.acknowledgeWrite()
            assertFalse(step.receive("83".hexToByteArray()))
            assertFalse(step.receive("880001".hexToByteArray()))
            assertTrue(step.receive("0803ff0001000000".hexToByteArray()))
            assertFalse(step.receive(byteArrayOf(0x0a)))
            assertNull(step.result)
            step.entryCommitted()
            if (!ackFirst) {
                assertNull(step.result)
                step.acknowledgeWrite()
            }
            assertEquals(MessageSyncStep.Result.NEXT, step.result)
        }
    }

    @Test
    fun newStepCanDrainAfterAnInterruptedStepAndHandlesTermination() {
        val interrupted = MessageSyncStep()
        interrupted.acknowledgeWrite()
        assertTrue(interrupted.receive(byteArrayOf(0x1b)))
        assertNull(interrupted.result)

        val nextConnection = MessageSyncStep()
        assertFalse(nextConnection.receive(byteArrayOf(0x0a)))
        assertNull(nextConnection.result)
        nextConnection.acknowledgeWrite()
        assertEquals(MessageSyncStep.Result.EMPTY, nextConnection.result)

        val rejected = MessageSyncStep()
        rejected.acknowledgeWrite()
        assertFalse(rejected.receive(byteArrayOf(1, 1)))
        assertEquals(MessageSyncStep.Result.REJECTED, rejected.result)

        val malformed = MessageSyncStep()
        malformed.acknowledgeWrite()
        assertFalse(malformed.receive(byteArrayOf(0x0a, 0)))
        assertEquals(MessageSyncStep.Result.MALFORMED, malformed.result)
    }
}
