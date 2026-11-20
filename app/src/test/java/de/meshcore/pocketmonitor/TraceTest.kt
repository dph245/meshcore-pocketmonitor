package de.meshcore.pocketmonitor

import de.meshcore.pocketmonitor.protocol.HopResolver
import de.meshcore.pocketmonitor.protocol.TraceCommand
import de.meshcore.pocketmonitor.protocol.TracePath
import de.meshcore.pocketmonitor.protocol.TraceResponse
import de.meshcore.pocketmonitor.protocol.TraceRun
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceTest {
    @Test
    fun encodesTraceWidthsAsFlagsWithLittleEndianTagAndAuth() {
        val tag = 0xf2345678u
        val auth = 0x01020304u
        for ((width, hash, suffix) in listOf(
            Triple(1, "AB", "00ab"),
            Triple(2, "abcd", "01abcd"),
            Triple(4, "abcdef01", "02abcdef01"),
        )) {
            val command = TracePath.create(width, listOf(hash), false)!!.command(tag, auth)
            assertArrayEquals(("24785634f204030201" + suffix).hexToByteArray(), command)
        }
    }

    @Test
    fun mirrorsWithoutRepeatingTheTurningPointAndChecksFinalRouteLength() {
        assertEquals(listOf("AA", "BB", "CC", "BB", "AA"),
            TracePath.create(1, listOf("aa", "BB", " CC "), true)?.hashes)
        assertEquals(listOf("AA"), TracePath.create(1, listOf("AA"), true)?.hashes)
        assertEquals(63, TracePath.create(1, List(32) { "AA" }, true)?.hashes?.size)
        assertNull(TracePath.create(1, List(33) { "AA" }, true))
        assertNull(TracePath.create(2, listOf("AA", "ABCD"), false))
        assertNull(TracePath.create(4, listOf("AABBCCGG"), false))
        assertNull(TracePath.create(1, emptyList(), false))
        assertEquals(2, TracePath.maxHops(2, 23))
        assertEquals(1, TracePath.maxHops(4, 23))
        assertEquals(32, TracePath.maxHops(4, 512))
    }

    @Test
    fun decodesSignedQuarterDbAndMatchesAllFieldsAndHopOrder() {
        val path = TracePath.create(4, listOf("AABBCCDD", "12345678"), false)!!
        val run = TraceRun("session", 0xf2345678u, 0x01020304u, path, 0, HopResolver.EMPTY)
        val frame = "89000802785634f204030201aabbccdd12345678ff80f6".hexToByteArray()
        val response = TraceResponse.decode(frame)!!
        assertEquals(path.hashes, response.hashes)
        assertEquals(listOf(-0.25, -32.0), response.hopSnrDb)
        assertEquals(-2.5, response.finalSnrDb, 0.0)
        assertTrue(run.matches(response))
        assertFalse(run.matches(response.copy(tag = 1u)))
        assertFalse(run.matches(response.copy(auth = 1u)))
        assertFalse(run.matches(response.copy(flags = 1)))
        assertFalse(run.matches(response.copy(hashes = response.hashes.reversed())))
        assertNull(TraceResponse.decode(frame.copyOf(frame.size - 1)))
        assertNull(TraceResponse.decode(frame + 0.toByte()))
        assertNull(TraceResponse.decode(frame.copyOf().apply { this[2] = 7 }))
    }

    @Test
    fun waitsForGattAndTheMatchingTaggedReplyInEitherOrder() {
        val tag = 0xf2345678u
        val path = TracePath.create(1, listOf("AB"), false)!!
        val reply = "0600785634f288130000".hexToByteArray()
        val early = TraceCommand(path, tag, 0u)
        early.receive(reply)
        assertNull(early.result)
        early.acknowledgeWrite()
        assertEquals(5_000L, TraceCommand.estimateMs(early.result!!))

        val late = TraceCommand(path, tag, 0u)
        late.acknowledgeWrite()
        late.receive(reply.copyOf().apply { this[2] = 0 })
        assertNull(late.result)
        late.receive(reply)
        assertArrayEquals(reply, late.result)
    }
}
