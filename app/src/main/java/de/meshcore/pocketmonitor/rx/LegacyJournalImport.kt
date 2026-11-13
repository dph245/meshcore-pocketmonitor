package de.meshcore.pocketmonitor.rx

import de.meshcore.pocketmonitor.protocol.ChannelDecoding
import de.meshcore.pocketmonitor.protocol.ChannelQuery
import de.meshcore.pocketmonitor.protocol.ChannelSlot
import de.meshcore.pocketmonitor.protocol.CompanionProtocol
import de.meshcore.pocketmonitor.protocol.RadioChannel
import java.io.File
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class LegacyJournalImport(private val history: CompanionHistory, private val directory: File) {
    suspend fun run(now: Long) {
        val cutoff = now - HISTORY_AGE_MS
        for (file in directory.listFiles().orEmpty().filter { journalName.matches(it.name) }.sortedBy { it.name }) {
            val session = file.name
            var identity: String? = null
            var lastSeen = 0L
            val channels = linkedMapOf<String, RadioChannel>()
            val slots = mutableMapOf<Int, ChannelSlot>()
            var ambiguous = false
            read(file) { row ->
                if (row.frame.code == CompanionProtocol.Command.APP_START.expectedReply) {
                    CompanionProtocol.selfIdentity(row.frame.bytes)?.let { key ->
                        ambiguous = ambiguous || (identity != null && identity != key)
                        identity = key
                    }
                }
                lastSeen = maxOf(lastSeen, row.frame.timeMs)
                ChannelQuery.configuredSlot(row.frame.bytes)?.let { slot ->
                    channels[slot.channel.identity] = slot.channel
                    slots[slot.index] = slot
                }
            }
            val key = identity
            if (key != null && !ambiguous) {
                val companion = KnownCompanion(key, "Companion ${key.take(8)}")
                val decoding = ChannelDecoding(channels.values.toList())
                val committed = history.importedOrdinal(session)
                val batch = ArrayList<Received>(128)
                if (lastSeen >= cutoff) read(file) { row ->
                    if (row.ordinal > committed && row.frame.timeMs >= cutoff && row.frame.isJournalable()) {
                        batch.add(row)
                        if (batch.size == 128) {
                            history.importBatch(companion, session, lastSeen, row.ordinal, batch, decoding)
                            batch.clear()
                        }
                    }
                }
                if (batch.isNotEmpty()) history.importBatch(companion, session, lastSeen, batch.last().ordinal, batch, decoding)
                history.importConfiguration(companion, lastSeen, slots.values.toList())
            }
            if (!file.delete() && file.exists()) throw IOException("Altes RX-Journal konnte nicht entfernt werden.")
            history.finishImport(session)
        }
    }

    private suspend inline fun read(file: File, consume: (Received) -> Unit) {
        file.bufferedReader(Charsets.US_ASCII).useLines { lines ->
            val context = currentCoroutineContext()
            for (line in lines.drop(1)) {
                context.ensureActive()
                val columns = line.split('\t', limit = 3)
                if (columns.size != 3) continue
                val ordinal = columns[0].toLongOrNull() ?: continue
                val time = columns[1].toLongOrNull() ?: continue
                val bytes = runCatching { columns[2].hexToByteArray() }.getOrNull() ?: continue
                consume(Received(ordinal, RxFrame(time, bytes)))
            }
        }
    }

    private companion object {
        val journalName = Regex("""rx-\d+-[0-9a-f-]{36}\.tsv""")
    }
}
