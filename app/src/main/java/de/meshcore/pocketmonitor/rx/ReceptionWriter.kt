package de.meshcore.pocketmonitor.rx

import de.meshcore.pocketmonitor.protocol.ChannelDecoding
import de.meshcore.pocketmonitor.protocol.ChannelSlot
import de.meshcore.pocketmonitor.protocol.CompanionContact
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal class ReceptionWriter(
    private val history: CompanionHistory,
    previousWriter: Job? = null,
    private val onIdentified: (KnownCompanion) -> Unit,
    private val onError: (String) -> Unit,
) {
    private sealed interface Event {
        data class Frame(val frame: RxFrame) : Event
        data class Queued(
            val frame: RxFrame,
            val slots: List<ChannelSlot>,
            val contactsByPrefix: Map<String, List<CompanionContact>>,
            val committed: () -> Unit,
        ) : Event
        data class Identity(val companion: KnownCompanion) : Event
        data class Configuration(val slots: List<ChannelSlot>, val complete: Boolean) : Event
    }

    private val queue = Channel<Event>(8192)
    private val session = UUID.randomUUID().toString()

    // Drain accepted notifications on normal disconnect, independently of the GATT handler.
    internal val completion = CoroutineScope(Dispatchers.IO).launch {
        try {
            var companion: KnownCompanion? = null
            var decoding = ChannelDecoding.default
            var backfill = launch {}
            var decodedIdentities: Set<String>? = null
            val beforeIdentity = ArrayList<Received>()
            val batch = ArrayList<Received>(128)
            var ordinal = 0L
            var event = queue.receiveCatching().getOrNull()
            while (true) {
                when (val current = event ?: break) {
                    is Event.Identity -> {
                        previousWriter?.join()
                        val identified = current.companion
                        history.register(identified.id, identified.name, System.currentTimeMillis())
                        companion = identified
                        beforeIdentity.chunked(128).forEach { history.append(identified.id, session, it, decoding) }
                        beforeIdentity.clear()
                        onIdentified(identified)
                        event = queue.receiveCatching().getOrNull()
                    }
                    is Event.Configuration -> {
                        val id = companion?.id
                        if (id != null) {
                            history.configure(id, current.slots, current.complete)
                            val channels = current.slots.map { it.channel }
                            decoding = ChannelDecoding(channels)
                            val identities = channels.map { it.identity }.toSet()
                            if (identities != decodedIdentities) {
                                decodedIdentities = identities
                                val decoder = decoding
                                val through = history.newestId(id)
                                val previous = backfill
                                backfill = launch {
                                    previous.cancel()
                                    previous.join()
                                    var after = 0L
                                    while (after < through) {
                                        ensureActive()
                                        val rows = history.pendingRadio(id, after, through)
                                        if (rows.isEmpty()) break
                                        history.linkDecoded(id, rows.mapNotNull { row ->
                                            decodeMessage(row.frame, decoder)?.let { row.sequence to it }
                                        })
                                        after = rows.last().sequence
                                    }
                                }
                            }
                        }
                        event = queue.receiveCatching().getOrNull()
                    }
                    is Event.Queued -> {
                        val id = checkNotNull(companion) { "Companion-Identität fehlt." }
                        history.appendQueued(id.id, session, Received(++ordinal, current.frame), current.slots,
                            current.contactsByPrefix)
                        current.committed()
                        event = queue.receiveCatching().getOrNull()
                    }
                    is Event.Frame -> {
                        do {
                            val frame = (event as Event.Frame).frame
                            batch.add(Received(++ordinal, frame))
                            event = queue.tryReceive().getOrNull()
                        } while (event is Event.Frame && batch.size < 128)
                        val id = companion?.id
                        if (id != null) {
                            history.append(id, session, batch, decoding)
                        } else {
                            check(beforeIdentity.size + batch.size <= 8192) { "Companion-Identität fehlt." }
                            beforeIdentity.addAll(batch)
                        }
                        batch.clear()
                        if (event == null) event = queue.receiveCatching().getOrNull()
                    }
                }
            }
            backfill.join()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            queue.cancel()
            onError("RX-Historie konnte nicht geschrieben werden: ${e.message}")
        }
    }

    fun identify(companion: KnownCompanion) = queue.trySend(Event.Identity(companion)).isSuccess

    fun configure(slots: List<ChannelSlot>, complete: Boolean) =
        queue.trySend(Event.Configuration(slots, complete)).isSuccess

    fun offer(frame: RxFrame) = !frame.isJournalable() || queue.trySend(Event.Frame(frame)).isSuccess

    fun offerQueued(frame: RxFrame, slots: List<ChannelSlot>,
                    contactsByPrefix: Map<String, List<CompanionContact>>, committed: () -> Unit) =
        queue.trySend(Event.Queued(frame, slots, contactsByPrefix, committed)).isSuccess

    fun close() {
        queue.close()
    }
}
