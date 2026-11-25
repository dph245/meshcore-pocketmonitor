package de.meshcore.pocketmonitor.tcp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class CompanionTcpTest {
    @Test
    fun endpointAcceptsNumericAddressesAndValidPorts() {
        assertEquals("192.168.1.10:5000", TcpEndpoint.parse(" 192.168.1.10 ", 5000).toString())
        assertEquals("[2001:db8::1]:65535", TcpEndpoint.parse("[2001:db8::1]", 65535).toString())
        assertEquals("[::1]:1", TcpEndpoint.parse("::1", 1).toString())
        for (invalid in listOf("", "192.168.1", "192.168.1.256", "mesh.local", "http://127.0.0.1", "1::2::3")) {
            assertNull(invalid, TcpEndpoint.parse(invalid, 5000))
        }
        for (invalid in listOf(null, -1, 0, 65536)) {
            assertNull(TcpEndpoint.parse("127.0.0.1", invalid))
        }
    }

    @Test
    fun commandUsesMeshCoreMarkerAndLength() {
        val payload = byteArrayOf(0x01, 0x3e, 0x00, 0x3c)
        assertArrayEquals(byteArrayOf(0x3c, 4, 0) + payload, CompanionTcpFrames.encode(payload))

        val maxPayload = ByteArray(176) { it.toByte() }
        assertArrayEquals(
            byteArrayOf(0x3c, 176.toByte(), 0) + maxPayload,
            CompanionTcpFrames.encode(maxPayload),
        )
        assertThrows(IllegalArgumentException::class.java) { CompanionTcpFrames.encode(byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { CompanionTcpFrames.encode(ByteArray(177)) }
    }

    @Test
    fun malformedAndTruncatedFramesAreNeverDelivered() {
        for (invalid in listOf(
            byteArrayOf(0x3c, 1, 0, 0),
            byteArrayOf(0x3e, 0, 0),
            byteArrayOf(0x3e, 177.toByte(), 0),
            byteArrayOf(0x3e, 1, 1),
        )) {
            assertThrows(CompanionTcpFrames.InvalidFrame::class.java) { read(invalid) }
        }
        assertThrows(EOFException::class.java) { read(byteArrayOf(0x3e, 1)) }
        assertThrows(EOFException::class.java) { read(byteArrayOf(0x3e, 2, 0, 1)) }
        assertNull(read(byteArrayOf()))
    }

    @Test
    fun fragmentedPayloadAndFollowingFrameAreReadInOrder() {
        val payload = ByteArray(176) { it.toByte() }
        val frames = byteArrayOf(0x3e, 176.toByte(), 0) + payload + byteArrayOf(0x3e, 1, 0, 0x0a)
        val fragmented = object : ByteArrayInputStream(frames) {
            override fun read(buffer: ByteArray, offset: Int, length: Int) =
                super.read(buffer, offset, minOf(length, 1))
        }
        val input = DataInputStream(fragmented)
        assertArrayEquals(payload, CompanionTcpFrames.read(input))
        assertArrayEquals(byteArrayOf(0x0a), CompanionTcpFrames.read(input))
        assertNull(CompanionTcpFrames.read(input))
    }

    @Test
    fun socketDeliversSplitAndCoalescedFramesThenReportsEof() {
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        ServerSocket(0, 1, loopback).use { server ->
            server.soTimeout = 5_000
            val endpoint = checkNotNull(TcpEndpoint.parse("127.0.0.1", server.localPort))
            val callbacks = LinkedBlockingQueue<Runnable>()
            val received = mutableListOf<List<Byte>>()
            var connected = false
            var written = false
            var retry: Boolean? = null
            val transport = CompanionTcpTransport(
                endpoint,
                callbacks::add,
                onConnected = { connected = true },
                onWritten = { written = true },
                onFrame = { received.add(it.toList()) },
                onClosed = { _, retryable -> retry = retryable },
            )
            fun handleCallback() = checkNotNull(callbacks.poll(5, TimeUnit.SECONDS)).run()

            try {
                transport.start()
                server.accept().use { peer ->
                    peer.soTimeout = 5_000
                    handleCallback()
                    assertTrue(connected)
                    assertTrue(transport.write(byteArrayOf(1, 3)))
                    val command = ByteArray(5)
                    DataInputStream(peer.getInputStream()).readFully(command)
                    assertArrayEquals(byteArrayOf(0x3c, 2, 0, 1, 3), command)
                    handleCallback()
                    assertTrue(written)

                    peer.getOutputStream().write(byteArrayOf(0x3e, 3))
                    peer.getOutputStream().write(byteArrayOf(0, 0x05, 0x3e, 0x3c, 0x3e, 1, 0, 0x0a))
                }
                repeat(3) { handleCallback() }
                assertEquals(listOf(byteArrayOf(0x05, 0x3e, 0x3c).toList(), listOf(0x0a.toByte())), received)
                assertEquals(true, retry)
            } finally {
                transport.close()
            }
            assertFalse(transport.write(byteArrayOf(1)))
        }
    }

    @Test
    fun intentionalCloseShutsDownSocketAndSuppressesQueuedCallbacks() {
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        ServerSocket(0, 1, loopback).use { server ->
            server.soTimeout = 5_000
            val endpoint = checkNotNull(TcpEndpoint.parse("127.0.0.1", server.localPort))
            val callbacks = LinkedBlockingQueue<Runnable>()
            val transport = CompanionTcpTransport(
                endpoint,
                callbacks::add,
                onConnected = {},
                onWritten = { error("Unexpected write callback") },
                onFrame = { error("Frame delivered after disconnect") },
                onClosed = { _, _ -> error("Manual disconnect reported as transport failure") },
            )
            try {
                transport.start()
                server.accept().use { peer ->
                    peer.soTimeout = 5_000
                    checkNotNull(callbacks.poll(5, TimeUnit.SECONDS)).run()
                    peer.getOutputStream().write(byteArrayOf(0x3e, 1, 0, 0x0a))
                    val pendingFrame = checkNotNull(callbacks.poll(5, TimeUnit.SECONDS))
                    transport.close()
                    pendingFrame.run()
                    assertEquals(-1, peer.getInputStream().read())
                    callbacks.poll(200, TimeUnit.MILLISECONDS)?.run()
                }
            } finally {
                transport.close()
            }
        }
    }

    private fun read(bytes: ByteArray) = CompanionTcpFrames.read(DataInputStream(ByteArrayInputStream(bytes)))
}
