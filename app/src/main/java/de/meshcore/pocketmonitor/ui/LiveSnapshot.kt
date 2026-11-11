package de.meshcore.pocketmonitor.ui

import de.meshcore.pocketmonitor.protocol.ChannelDecoding
import de.meshcore.pocketmonitor.protocol.CompanionEvent
import de.meshcore.pocketmonitor.protocol.CompanionEventDecoder
import de.meshcore.pocketmonitor.protocol.MeshPacket
import de.meshcore.pocketmonitor.protocol.MeshPacketDecoder
import de.meshcore.pocketmonitor.rx.JournalSnapshot
import de.meshcore.pocketmonitor.rx.RxRow
import kotlinx.coroutines.CancellationException

internal data class LiveRow(
    val original: RxRow,
    val event: CompanionEvent.RadioRx?,
    val packet: MeshPacket?,
    val decoderError: String? = null,
)

internal class LiveSnapshot(
    val journal: JournalSnapshot,
    val channels: ChannelDecoding,
    val newestRadioFirst: List<LiveRow>,
) {
    companion object {
        fun update(journal: JournalSnapshot, previous: LiveSnapshot?, channels: ChannelDecoding = ChannelDecoding.default): LiveSnapshot {
            if (journal === previous?.journal && channels === previous.channels) return previous
            val cached = previous?.takeIf { it.journal.fileName == journal.fileName && it.channels === channels }
                ?.newestRadioFirst.orEmpty().associateBy { it.original.sequence }
            return LiveSnapshot(journal, channels, journal.newestRadioFirst.map {
                cached[it.sequence] ?: decode(it, channels)
            })
        }

        private fun decode(row: RxRow, channels: ChannelDecoding): LiveRow {
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
    }
}
