package de.meshcore.pocketmonitor.ui

import de.meshcore.pocketmonitor.ble.ChannelCatalog
import de.meshcore.pocketmonitor.protocol.ChannelDecoding
import de.meshcore.pocketmonitor.protocol.ChannelSlot
import de.meshcore.pocketmonitor.protocol.MeshPayload
import de.meshcore.pocketmonitor.rx.JournalCursor
import de.meshcore.pocketmonitor.rx.RxJournal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class ChannelMessage(
    val sequence: Long,
    val sender: String?,
    val timestampSeconds: Long,
    val text: String,
    val timestampLabel: String,
    val comparisonId: String? = null,
    val receptionCount: Long = 1,
) {
    val senderLabel: String get() = sender ?: "Unbekannter Absender"
    val receptionLabel: String get() = if (receptionCount == 1L) "1 Empfang" else "$receptionCount Empfänge"
}

private val channelTime = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss")

internal fun channelClock(zone: ZoneId = ZoneId.systemDefault()): DateTimeFormatter = channelTime.withZone(zone)

internal val channelMessageOrder = Comparator<ChannelMessage> { left, right ->
    val time = left.timestampSeconds.compareTo(right.timestampSeconds)
    if (time != 0) time else left.sequence.compareTo(right.sequence)
}

private fun mergeMessages(
    existing: List<ChannelMessage>,
    incoming: MutableMap<String, ChannelMessage>,
): List<ChannelMessage> {
    val updated = existing.map { message ->
        val repeated = incoming.remove(message.comparisonId)
        if (repeated == null) message else message.copy(receptionCount = message.receptionCount + repeated.receptionCount)
    }
    if (incoming.isEmpty()) return updated
    val additions = incoming.values.toMutableList()
    additions.sortWith(channelMessageOrder)
    if (updated.isEmpty()) return additions
    if (channelMessageOrder.compare(updated.last(), additions.first()) <= 0) return updated + additions

    return buildList(updated.size + additions.size) {
        var next = 0
        for (message in updated) {
            while (next < additions.size && channelMessageOrder.compare(additions[next], message) < 0) {
                add(additions[next++])
            }
            add(message)
        }
        addAll(additions.subList(next, additions.size))
    }
}

internal class ChannelContent(
    val catalog: ChannelCatalog,
    internal val messages: Map<String, List<ChannelMessage>>,
) {
    fun messagesFor(slot: ChannelSlot): List<ChannelMessage> = messages[slot.channel.identity].orEmpty()
}

internal class ChannelHistory private constructor(
    val content: ChannelContent,
    private val identities: Set<String>,
    private val decoding: ChannelDecoding,
    private val cursor: JournalCursor,
    private val zone: ZoneId,
) {
    val catalog: ChannelCatalog get() = content.catalog

    companion object {
        suspend fun update(journal: RxJournal, catalog: ChannelCatalog, previous: ChannelHistory?): ChannelHistory {
            require(journal.name == catalog.journalName)
            val identities = catalog.slots.map { it.channel.identity }.toSet()
            val cached = previous?.takeIf { it.catalog.journalName == catalog.journalName && it.identities == identities }
            val decoding = cached?.decoding ?: ChannelDecoding(
                catalog.slots.map { it.channel }, includeBuiltIn = false,
            )
            val start = cached?.cursor ?: JournalCursor()
            val zone = ZoneId.systemDefault()
            val clock = channelClock(zone)
            val incoming = mutableMapOf<String, MutableMap<String, ChannelMessage>>()
            val cursor = if (identities.isEmpty()) start else journal.readRadioAfter(start) { row ->
                val packet = decodeRadioRow(row, decoding).packet
                val payload = packet?.payload as? MeshPayload.GroupText
                val comparisonId = packet?.comparisonId
                if (payload != null && comparisonId != null && payload.channelIdentity in identities) {
                    val messages = incoming.getOrPut(payload.channelIdentity) { mutableMapOf() }
                    val first = messages[comparisonId]
                    messages[comparisonId] = if (first == null) {
                        ChannelMessage(
                            row.sequence, payload.sender, payload.timestampSeconds, payload.text,
                            clock.format(Instant.ofEpochSecond(payload.timestampSeconds)),
                            comparisonId,
                        )
                    } else first.copy(receptionCount = first.receptionCount + 1)
                }
            }
            if (cached != null && cached.catalog === catalog && cursor == start && zone == cached.zone) return cached
            val existing = if (cached != null && zone != cached.zone) {
                cached.content.messages.mapValues { (_, rows) ->
                    rows.map { it.copy(timestampLabel = clock.format(Instant.ofEpochSecond(it.timestampSeconds))) }
                }
            } else cached?.content?.messages.orEmpty()
            val messages = if (incoming.isEmpty()) existing else existing.toMutableMap().apply {
                for ((identity, additions) in incoming) {
                    this[identity] = mergeMessages(this[identity].orEmpty(), additions)
                }
            }
            val content = if (cached?.catalog === catalog && cached.content.messages === messages) {
                cached.content
            } else ChannelContent(catalog, messages)
            return ChannelHistory(content, identities, decoding, cursor, zone)
        }
    }
}

internal data class ChannelSelection(val slot: Int, val identity: String) {
    fun resolve(slots: List<ChannelSlot>): ChannelSlot? {
        val matching = slots.filter { it.channel.identity == identity }
        return matching.firstOrNull { it.index == slot } ?: matching.firstOrNull()
    }
}
