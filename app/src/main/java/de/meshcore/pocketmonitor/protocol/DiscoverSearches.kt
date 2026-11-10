package de.meshcore.pocketmonitor.protocol

import de.meshcore.pocketmonitor.rx.RxFrame
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class DiscoverPhase { AWAITING_REPLY, LISTENING, COMPLETE, FAILED, DISCONNECTED }

internal data class DiscoverReception(
    val response: MeshPayload.Discover.Response,
    val receivedAtMs: Long,
    val latencyMs: Long,
    val rxSnrDb: Double,
    val rssiDbm: Int,
    val duplicate: Boolean,
    val late: Boolean,
)

internal data class DiscoverRun(
    val tag: UInt,
    val journalName: String,
    val startedAtMs: Long,
    val startedElapsedMs: Long,
    val resolver: HopResolver,
    val phase: DiscoverPhase = DiscoverPhase.AWAITING_REPLY,
    val listenUntilMs: Long? = null,
    val message: String? = null,
    val receptions: List<DiscoverReception> = emptyList(),
) {
    val busy: Boolean get() = phase == DiscoverPhase.AWAITING_REPLY || phase == DiscoverPhase.LISTENING
}

internal data class DiscoverHistory(
    val runs: List<DiscoverRun> = emptyList(),
    val coolingDown: Boolean = false,
) {
    val busy: Boolean get() = runs.lastOrNull()?.busy == true
}

/** Updated on the GATT handler. The published history survives BLE reconnects. */
internal class DiscoverSearches(private val nextTag: () -> UInt = { Random.nextInt().toUInt() }) {
    private val mutableHistory = MutableStateFlow(DiscoverHistory())
    val history = mutableHistory.asStateFlow()
    private val indexByTag = mutableMapOf<UInt, Int>()
    private var cooldownUntilMs = 0L
    private var receivingJournal: String? = null

    fun start(journalName: String, timeMs: Long, elapsedMs: Long, resolver: HopResolver): DiscoverRun? {
        if (history.value.busy || elapsedMs < cooldownUntilMs) return null
        var tag = nextTag()
        while (tag in indexByTag) tag = nextTag()
        val run = DiscoverRun(tag, journalName, timeMs, elapsedMs, resolver)
        val runs = history.value.runs
        indexByTag[tag] = runs.size
        cooldownUntilMs = elapsedMs + COOLDOWN_MS
        receivingJournal = journalName
        mutableHistory.value = DiscoverHistory(runs + run, coolingDown = true)
        return run
    }

    fun endCooldown(elapsedMs: Long) {
        if (elapsedMs >= cooldownUntilMs) mutableHistory.value = history.value.copy(coolingDown = false)
    }

    fun accept(elapsedMs: Long) = updateLatest { run ->
        run.copy(phase = DiscoverPhase.LISTENING, listenUntilMs = elapsedMs + LISTEN_MS)
    }

    fun complete() = updateLatest { it.copy(phase = DiscoverPhase.COMPLETE) }

    fun fail(message: String) = updateLatest {
        it.copy(phase = DiscoverPhase.FAILED, message = message, receptions = emptyList())
    }

    fun disconnect(journalName: String, message: String) {
        if (receivingJournal != journalName) return
        receivingJournal = null
        updateLatest { run ->
            if (!run.busy) run else run.copy(
                phase = DiscoverPhase.DISCONNECTED,
                message = message,
                receptions = if (run.listenUntilMs != null) run.receptions else emptyList(),
            )
        }
    }

    fun updateResolver(journalName: String, resolver: HopResolver) {
        val state = history.value
        mutableHistory.value = state.copy(runs = state.runs.map {
            if (it.journalName == journalName) it.copy(resolver = resolver) else it
        })
    }

    fun receive(journalName: String, frame: RxFrame, elapsedMs: Long) {
        if (receivingJournal != journalName || frame.code != CompanionProtocol.RF_LOG) return
        val header = frame.bytes.getOrNull(3)?.let { MeshHeader(it.toInt() and 0xff) } ?: return
        if (header.type != PacketType.CONTROL || !header.route.isDirect) return
        val event = CompanionEventDecoder.decode(frame.bytes) as? CompanionEvent.RadioRx ?: return
        val packet = MeshPacketDecoder.decode(event.radioBytes ?: return)
        val response = packet.payload as? MeshPayload.Discover.Response ?: return
        if (packet.status != DecodeStatus.DECODED || packet.pathLengthByte != 0 ||
            response.nodeType != CompanionContact.TYPE_REPEATER
        ) return

        val index = indexByTag[response.tag.toUInt()] ?: return
        val run = history.value.runs[index]
        if (run.journalName != journalName || run.phase == DiscoverPhase.FAILED ||
            run.phase == DiscoverPhase.DISCONNECTED || elapsedMs < run.startedElapsedMs
        ) return

        val reception = DiscoverReception(
            response = response,
            receivedAtMs = frame.timeMs,
            latencyMs = elapsedMs - run.startedElapsedMs,
            rxSnrDb = event.snrDb ?: return,
            rssiDbm = event.rssiDbm ?: return,
            duplicate = run.receptions.any { it.response == response },
            late = run.listenUntilMs?.let { elapsedMs >= it } ?: false,
        )
        replace(index, run.copy(receptions = run.receptions + reception))
    }

    private fun updateLatest(update: (DiscoverRun) -> DiscoverRun) {
        val runs = history.value.runs
        runs.lastOrNull()?.let { replace(runs.lastIndex, update(it)) }
    }

    private fun replace(index: Int, run: DiscoverRun) {
        val state = history.value
        val runs = state.runs.toMutableList()
        runs[index] = run
        mutableHistory.value = state.copy(runs = runs)
    }

    companion object {
        const val COOLDOWN_MS = 2_000L
        const val LISTEN_MS = 8_000L
        const val REPLY_TIMEOUT_MS = 5_000L
    }
}
