package de.meshcore.pocketmonitor.ui

import de.meshcore.pocketmonitor.protocol.ChannelDecoding
import de.meshcore.pocketmonitor.protocol.CompanionEvent
import de.meshcore.pocketmonitor.protocol.CompanionEventDecoder
import de.meshcore.pocketmonitor.protocol.HopResolver
import de.meshcore.pocketmonitor.protocol.MeshPacket
import de.meshcore.pocketmonitor.protocol.MeshPacketDecoder
import de.meshcore.pocketmonitor.rx.JournalSnapshot
import de.meshcore.pocketmonitor.rx.RxRow
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

internal data class LiveRow(
    val original: RxRow,
    val event: CompanionEvent.RadioRx?,
    val packet: MeshPacket?,
    val decoderError: String? = null,
)

internal class LiveRadioRow(val row: LiveRow, val display: RadioDisplay) {
    // Reuse item compositions with the same optional text blocks.
    val layout = (if (display.messageByline != null) 1 else 0) +
        (if (display.scope != null) 2 else 0) + (if (display.hops != null) 4 else 0)
}

internal class LiveSnapshot(
    val journal: JournalSnapshot,
    val channels: ChannelDecoding,
    val resolver: HopResolver,
    private val zone: ZoneId,
    val radioRows: List<LiveRadioRow>,
) {
    fun radioRow(sequence: Long): LiveRadioRow? {
        val index = radioRows.binarySearch { sequence.compareTo(it.row.original.sequence) }
        return radioRows.getOrNull(index)
    }

    companion object {
        fun update(
            journal: JournalSnapshot,
            previous: LiveSnapshot?,
            channels: ChannelDecoding = ChannelDecoding.default,
            resolver: HopResolver = HopResolver.EMPTY,
            zone: ZoneId = ZoneId.systemDefault(),
        ): LiveSnapshot {
            if (journal === previous?.journal && channels === previous.channels &&
                resolver === previous.resolver && zone == previous.zone
            ) return previous

            val cached = previous?.takeIf { it.journal.fileName == journal.fileName && it.channels === channels }
            val incoming = journal.newestRadioFirst
            val oldHead = cached?.radioRows?.firstOrNull()?.row?.original?.sequence
            val added = oldHead?.let { sequence -> incoming.binarySearch { sequence.compareTo(it.sequence) } } ?: -1
            // A journal's newest-first window only prepends new rows and drops rows at the tail.
            val samePresentation = cached != null && resolver === cached.resolver && zone == cached.zone
            val rows = if (incoming.isEmpty()) {
                emptyList()
            } else if (cached != null && samePresentation && added == 0 && incoming.size == cached.radioRows.size) {
                cached.radioRows
            } else {
                val clock = radioClock(zone)
                incoming.mapIndexed { index, original ->
                    val old = if (added >= 0 && index >= added) cached?.radioRows?.getOrNull(index - added) else null
                    when {
                        old == null -> {
                            val row = decodeRadioRow(original, channels)
                            LiveRadioRow(row, row.formatRadio(resolver, clock))
                        }
                        samePresentation -> old
                        zone == cached?.zone ->
                            LiveRadioRow(old.row, old.display.copy(hops = old.row.formatHops(resolver)))
                        else -> LiveRadioRow(old.row, old.row.formatRadio(resolver, clock))
                    }
                }
            }
            return LiveSnapshot(journal, channels, resolver, zone, rows)
        }
    }
}

internal fun decodeRadioRow(row: RxRow, channels: ChannelDecoding): LiveRow {
    var event: CompanionEvent.RadioRx? = null
    return try {
        event = CompanionEventDecoder.decode(row.frame.bytes) as? CompanionEvent.RadioRx
        val packet = event?.radioBytes?.takeIf { it.isNotEmpty() }?.let { MeshPacketDecoder.decode(it, channels) }
        LiveRow(row, event, packet)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The frame is already in the journal. Keep the row and its original bytes visible.
        LiveRow(row, event, null, "Interner Decoderfehler (${e.javaClass.simpleName}).")
    }
}
