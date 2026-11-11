package de.meshcore.pocketmonitor.protocol

internal enum class TelemetryMetric(
    val command: ByteArray,
    val responseCode: Int,
    val responseSubtype: Int?,
    val minimumVersion: Int,
) {
    BATTERY(byteArrayOf(0x14), 0x0c, null, 0),
    NOISE_FLOOR(byteArrayOf(0x38, 0x01), 0x18, 1, 8),
}

internal class TelemetryQuery(val metric: TelemetryMetric) : CompanionRead(
    metric.command, metric.responseCode, metric.responseSubtype,
) {
    fun value(frame: ByteArray): Int? {
        val offset = if (metric.responseSubtype == null) 1 else 2
        if (frame.size < offset + 2 || frame[0].toInt() != metric.responseCode) return null
        if (metric.responseSubtype != null && frame[1].toInt() != metric.responseSubtype) return null
        val raw = (frame[offset].toInt() and 0xff) or ((frame[offset + 1].toInt() and 0xff) shl 8)
        return when (metric) {
            TelemetryMetric.BATTERY -> raw.takeUnless { it == 0 }
            TelemetryMetric.NOISE_FLOOR -> raw.toShort().toInt().takeUnless { it == 0 }
        }
    }
}
