package de.meshcore.pocketmonitor.ui

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
    val synchronized: Boolean = false,
) {
    val senderLabel: String get() = sender ?: "Unbekannter Absender"
    val receptionLabel: String get() {
        if (receptionCount == 0L && synchronized) return "Vom Companion abgeholt"
        val radio = if (receptionCount == 1L) "1 Empfang" else "$receptionCount Empfänge"
        return if (synchronized) "$radio · vom Companion abgeholt" else radio
    }
}

private val channelTime = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss")

internal fun channelClock(zone: ZoneId = ZoneId.systemDefault()): DateTimeFormatter = channelTime.withZone(zone)

internal val channelMessageOrder = Comparator<ChannelMessage> { left, right ->
    val time = left.timestampSeconds.compareTo(right.timestampSeconds)
    if (time != 0) time else left.sequence.compareTo(right.sequence)
}
