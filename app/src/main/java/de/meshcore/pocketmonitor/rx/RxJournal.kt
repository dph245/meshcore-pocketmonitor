package de.meshcore.pocketmonitor.rx

import de.meshcore.pocketmonitor.protocol.CompanionProtocol
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive

internal data class RxFrame(val timeMs: Long, val bytes: ByteArray) {
    val code: Int? get() = bytes.firstOrNull()?.toInt()?.and(0xff)
}

internal data class RxRow(
    val sequence: Long,
    val frame: RxFrame,
) {
    val timeMs: Long get() = frame.timeMs
    val code: Int? get() = frame.code
}

internal data class JournalSnapshot(
    val fileName: String,
    val count: Long,
    val newestFirst: List<RxRow>,
    val newestRadioFirst: List<RxRow>,
    val error: String?,
)

internal data class JournalCursor(val sequence: Long = 0, val offset: Long = 0)

internal class RxJournal(directory: File, private val onError: (String) -> Unit) {
    val name: String get() = file.name
    internal val file = File(directory, "rx-${System.currentTimeMillis()}-${UUID.randomUUID()}.tsv")
    private val queue = Channel<RxFrame>(8192)
    private val lock = Any()
    private val recent = ArrayDeque<RxRow>()
    private val recentRadio = ArrayDeque<RxRow>()
    private var count = 0L
    private var error: String? = null

    // Outlives the BLE session long enough to drain frames after a normal disconnect.
    internal val completion = CoroutineScope(Dispatchers.IO).launch {
        try {
            if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                throw IOException("Aufzeichnungsverzeichnis fehlt.")
            }
            file.bufferedWriter().use { writer ->
                writer.write("sequence\tepoch_ms\tframe_hex\n")
                writer.flush()
                val batch = ArrayList<RxRow>(128)
                var sequence = 0L
                for (first in queue) {
                    var frame = first
                    while (true) {
                        val row = RxRow(++sequence, frame)
                        val hex = frame.bytes.toHexString(HexFormat.UpperCase)
                        writer.write("${row.sequence}\t${row.timeMs}\t$hex\n")
                        batch.add(row)
                        if (batch.size == 128) break
                        frame = queue.tryReceive().getOrNull() ?: break
                    }
                    writer.flush()
                    synchronized(lock) {
                        for (row in batch) {
                            recent.appendRecent(row)
                            if (row.code == CompanionProtocol.RF_LOG) recentRadio.appendRecent(row)
                        }
                        count = sequence
                    }
                    batch.clear()
                }
            }
        } catch (e: IOException) {
            val message = "RX-Journal konnte nicht geschrieben werden: ${e.message}"
            synchronized(lock) { error = message }
            queue.cancel()
            onError(message)
        }
    }

    // Called by the BLE callback thread. No disk access or UI formatting here.
    fun offer(frame: RxFrame): Boolean = queue.trySend(frame).isSuccess

    fun close() {
        queue.close()
    }

    fun snapshot(previous: JournalSnapshot?): JournalSnapshot = synchronized(lock) {
        if (previous?.fileName == file.name && previous.count == count && previous.error == error) {
            previous
        } else {
            JournalSnapshot(file.name, count, recent.reversed(), recentRadio.reversed(), error)
        }
    }

    suspend fun readRadioAfter(cursor: JournalCursor, onRow: (RxRow) -> Unit): JournalCursor {
        val committed = synchronized(lock) { count }
        if (committed == cursor.sequence) return cursor
        return file.inputStream().use { input ->
            input.channel.position(cursor.offset)
            val reader = input.bufferedReader(Charsets.US_ASCII)
            var offset = cursor.offset
            if (offset == 0L) {
                val header = reader.readLine() ?: throw IOException("Unvollständiges RX-Journal.")
                offset = header.length + 1L
            }
            for (sequence in cursor.sequence + 1..committed) {
                coroutineContext.ensureActive()
                val line = reader.readLine() ?: throw IOException("Unvollständiges RX-Journal.")
                offset += line.length + 1L
                val firstTab = line.indexOf('\t')
                val secondTab = line.indexOf('\t', firstTab + 1)
                if (line.startsWith(RADIO_LOG_HEX, secondTab + 1)) {
                    val timeMs = line.substring(firstTab + 1, secondTab).toLong()
                    val bytes = line.substring(secondTab + 1).hexToByteArray()
                    onRow(RxRow(sequence, RxFrame(timeMs, bytes)))
                }
            }
            JournalCursor(committed, offset)
        }
    }

    private fun ArrayDeque<RxRow>.appendRecent(row: RxRow) {
        if (size == 500) removeFirst()
        addLast(row)
    }
}

private val RADIO_LOG_HEX = CompanionProtocol.RF_LOG.toString(16).uppercase()
