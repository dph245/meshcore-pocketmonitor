package de.meshcore.pocketmonitor.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class TraceRoute(val width: Int, val entries: List<String>, val mirror: Boolean, val turn: Int? = null) {
    val prefixes: List<String> get() = entries.map { entry ->
        val fullKey = entry.length == 64 && entry.all { it in '0'..'9' || it.uppercaseChar() in 'A'..'F' }
        if (fullKey) entry.take(width * 2) else entry
    }
    val path: TracePath? get() = TracePath.create(width, prefixes, mirror)

    // One-based index of the last outward hop, if the editor supplies a turn.
    val outwardHops: Int? get() = if (mirror) entries.size else turn?.takeIf { it in 1..entries.size }

    fun hopLabel(index: Int): String {
        val outward = outwardHops ?: return "Hop ${index + 1} · Richtung unbekannt"
        return if (index < outward) "Hinweg ${index + 1}" else "Rückweg ${index - outward + 1}"
    }
}

internal data class TraceSeriesOptions(
    val count: Int,
    val intervalSeconds: Int = 30,
    val timeoutSeconds: Int = 30,
) {
    init {
        require(count in counts && intervalSeconds in 1..3600 && timeoutSeconds in 1..3600)
    }

    companion object {
        val counts = listOf(1, 2, 3, 5, 10)
    }
}

internal data class TraceRadio(
    val frequencyKhz: Long? = null,
    val bandwidthHz: Long? = null,
    val sf: Int? = null,
    val cr: Int? = null,
    val powerDbm: Int? = null,
    val firmware: String? = null,
    val protocol: Int? = null,
    val radioIc: String? = null,
) {
    fun withSelfInfo(frame: ByteArray, identity: String): TraceRadio {
        if (CompanionProtocol.selfIdentity(frame) != identity) return this
        val radio = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        fun positiveInt(offset: Int) = if (frame.size >= offset + 4) {
            radio.getInt(offset).toUInt().toLong().takeIf { it > 0 }
        } else null
        fun byte(offset: Int) = frame.getOrNull(offset)?.toInt()?.and(0xff)
        return copy(
            frequencyKhz = positiveInt(48), bandwidthHz = positiveInt(52),
            sf = byte(56)?.takeIf { it in 5..12 }, cr = byte(57)?.takeIf { it in 5..8 },
            powerDbm = frame[2].toInt(),
        )
    }

    companion object {
        fun fromDeviceInfo(frame: ByteArray): TraceRadio = TraceRadio(
            firmware = if (frame.size >= 80) {
                frame.decodeToString(60, 80).substringBefore('\u0000').trim()
                    .takeIf { it.isNotEmpty() && it.all { ch -> ch in ' '..'~' } }
            } else null,
            protocol = frame.getOrNull(1)?.toInt()?.and(0xff)?.takeIf { it > 0 },
        )
    }
}

internal data class TracePreset(val id: Long, val name: String, val route: TraceRoute)

internal enum class TraceSeriesStatus { RUNNING, COMPLETED, ABORTED, DISCONNECTED, FAILED }

internal data class TraceSeries(
    val id: Long,
    val companion: String,
    val name: String?,
    val route: TraceRoute,
    val options: TraceSeriesOptions,
    val startedAtMs: Long,
    val radio: TraceRadio,
    val status: TraceSeriesStatus,
    val message: String?,
)

internal data class TraceMeasurement(
    val number: Int,
    val startedAtMs: Long,
    val phase: TracePhase,
    val accepted: Boolean,
    val responseMs: Long?,
    val snr: List<Double>,
    val message: String?,
)

internal data class TraceSnrStats(val min: Double, val median: Double, val max: Double)

internal class TraceSummary(measurements: List<TraceMeasurement>, hopCount: Int) {
    private val answeredMeasurements = measurements.filter { it.phase == TracePhase.ANSWERED }
    val attempts = measurements.size
    val answered = answeredMeasurements.size
    val timeouts = measurements.count { it.phase == TracePhase.TIMED_OUT }
    val errors = measurements.count { it.phase == TracePhase.FAILED }
    val aborted = measurements.count { it.phase == TracePhase.CANCELLED || it.phase == TracePhase.DISCONNECTED }
    val rate: Double? = if (attempts == 0) null else answered * 100.0 / attempts
    val snr = List(hopCount + 1) { index ->
        val values = answeredMeasurements.mapNotNull { it.snr.getOrNull(index) }.sorted()
        if (values.isEmpty()) null else TraceSnrStats(
            values.first(), (values[(values.size - 1) / 2] + values[values.size / 2]) / 2, values.last(),
        )
    }
}

internal object TraceSchedule {
    fun continuesAfter(phase: TracePhase) = phase == TracePhase.ANSWERED || phase == TracePhase.TIMED_OUT

    fun nextStartMs(started: Long, intervalMs: Long, phase: TracePhase, firmwareWindowEnd: Long): Long =
        maxOf(started + intervalMs, if (phase == TracePhase.TIMED_OUT) firmwareWindowEnd else started)
}

internal data class TraceSeriesProgress(
    val companion: String,
    val count: Int,
    val number: Int = 0,
    val running: Boolean = true,
    val message: String? = null,
    val seriesId: Long? = null,
)
