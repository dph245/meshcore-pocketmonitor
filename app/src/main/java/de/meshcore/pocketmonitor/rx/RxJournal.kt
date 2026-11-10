package de.meshcore.pocketmonitor.rx

import de.meshcore.pocketmonitor.protocol.CompanionProtocol
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

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

internal class RxJournal(directory: File, private val onError: (String) -> Unit) {
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

    private fun ArrayDeque<RxRow>.appendRecent(row: RxRow) {
        if (size == 500) removeFirst()
        addLast(row)
    }
}
