package de.meshcore.pocketmonitor.tcp

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

// Socket I/O runs off the session handler. All callbacks are serialized on it.
internal class CompanionTcpTransport(
    private val endpoint: TcpEndpoint,
    private val callbacks: Executor,
    private val onConnected: () -> Unit,
    private val onWritten: () -> Unit,
    private val onFrame: (ByteArray) -> Unit,
    private val onClosed: (String, Boolean) -> Unit,
) {
    private val socket = Socket()
    private val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "Companion-TCP-write") }
    private val receiveSlots = Semaphore(32)
    private var closed = false
    private var output: OutputStream? = null

    private val reader = Thread({
        try {
            socket.connect(InetSocketAddress(endpoint.address, endpoint.port), 10_000)
            socket.keepAlive = true
            socket.tcpNoDelay = true
            val connectedOutput = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream().buffered())
            dispatch {
                output = connectedOutput
                onConnected()
            }
            while (true) {
                val frame = CompanionTcpFrames.read(input) ?: break
                receiveSlots.acquire()
                callbacks.execute {
                    try {
                        if (!closed) onFrame(frame)
                    } finally {
                        receiveSlots.release()
                    }
                }
            }
            dispatch { onClosed("TCP-Companion hat die Verbindung getrennt.", true) }
        } catch (_: InterruptedException) {
            // close() unblocks a reader waiting for the callback queue.
        } catch (_: CompanionTcpFrames.InvalidFrame) {
            dispatch { onClosed("Ungültiges MeshCore-TCP-Frame empfangen.", false) }
        } catch (_: IOException) {
            dispatch { onClosed("TCP-Verbindung zu $endpoint fehlgeschlagen oder unterbrochen.", true) }
        } catch (_: SecurityException) {
            dispatch { onClosed("TCP-Verbindung zu $endpoint ist nicht erlaubt.", false) }
        }
    }, "Companion-TCP-read")

    fun start() = reader.start()

    fun write(payload: ByteArray): Boolean {
        val stream = output ?: return false
        if (payload.size !in 1..CompanionTcpFrames.MAX_PAYLOAD) return false
        val frame = CompanionTcpFrames.encode(payload)
        writer.execute {
            try {
                stream.write(frame)
                // Local socket write completion. The firmware reply is processed separately.
                dispatch(onWritten)
            } catch (_: IOException) {
                dispatch { onClosed("TCP-Schreibvorgang fehlgeschlagen.", true) }
            }
        }
        return true
    }

    fun close() {
        closed = true
        output = null
        reader.interrupt()
        runCatching { socket.close() }
        writer.shutdownNow()
    }

    private fun dispatch(action: () -> Unit) {
        callbacks.execute { if (!closed) action() }
    }
}
