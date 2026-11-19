package de.meshcore.pocketmonitor.ui

import de.meshcore.pocketmonitor.protocol.PrivateSendRoute
import de.meshcore.pocketmonitor.protocol.PrivateSendStatus
import de.meshcore.pocketmonitor.rx.OutgoingGroup
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
    val outgoing: OutgoingGroup? = null,
    val privateSend: PrivateSendStatus? = null,
    val unread: Boolean = false,
    val privateAttempts: Int? = null,
    val privateRoute: PrivateSendRoute? = null,
) {
    val senderLabel: String get() = sender ?: "Unbekannter Absender"
    val privateProgressLabel: String? get() = privateAttempts?.let { count ->
        val attempts = if (count == 1) "1 Versuch" else "$count Versuche"
        privateRoute?.let { "$attempts · zuletzt ${it.label}" } ?: attempts
    }

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
