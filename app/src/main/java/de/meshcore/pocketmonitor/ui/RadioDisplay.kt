package de.meshcore.pocketmonitor.ui

import de.meshcore.pocketmonitor.protocol.DecodeStatus
import de.meshcore.pocketmonitor.protocol.HopResolver
import de.meshcore.pocketmonitor.protocol.HopStatus
import de.meshcore.pocketmonitor.protocol.MeshPayload
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class RadioDisplay(
    val time: String,
    val type: String,
    val rssi: String,
    val snr: String,
    val messageByline: String?,
    val messageText: String?,
    val summary: String,
    val scope: String?,
    val hops: String?,
) {
    val signal = "$rssi $snr"
}

private val radioTime = DateTimeFormatter.ofPattern("HH:mm:ss")

internal fun LiveRow.formatRadio(resolver: HopResolver, clock: DateTimeFormatter): RadioDisplay {
    val path = packet?.path
    val message = packet?.payload as? MeshPayload.GroupText
    val summary = buildList {
        add(event?.radioBytes?.size?.let { "$it B" } ?: "? B")
        if (path != null) {
            val hops = if (path.hopCount == 1) "1 Hop" else "${path.hopCount} Hops"
            add(when {
                path.traceSnrDb != null -> "${path.hopCount}/${path.prefixes.size} TRACE-Hops"
                packet.header?.route?.isDirect == true -> "$hops übrig"
                else -> hops
            })
        }
        when (val payload = packet?.payload) {
            is MeshPayload.Encrypted -> if (payload.channelHash != null) {
                add("Kanal-Hash ${payload.channelHash.hex(2)}")
            } else if (payload.recipientPrefix != null) {
                add("${payload.senderPrefix?.hex(2) ?: payload.senderKey?.take(8) ?: "?"} → ${payload.recipientPrefix.hex(2)}")
            }
            is MeshPayload.Advert -> add(payload.name?.let { "$it (unverifiziert)" } ?: "Key ${payload.publicKey.take(8)}…")
            is MeshPayload.Discover.Response -> add("ID ${payload.publicKeyOrPrefix.take(16)}…")
            else -> Unit
        }
        when {
            decoderError != null || event?.issue != null || packet?.status == DecodeStatus.MALFORMED ->
                add("Fehler")
            packet?.status == DecodeStatus.ENCRYPTED -> add("verschlüsselt")
            packet?.status == DecodeStatus.UNSUPPORTED -> add("unbekannt")
        }
    }.joinToString(" · ")
    return RadioDisplay(
        clock.format(Instant.ofEpochMilli(original.timeMs)),
        typeLabel,
        event?.rssiDbm?.let { "$it dBm" } ?: "— dBm",
        event?.snrDb?.let { "${it.dbText()} dB" } ?: "— dB",
        message?.let { "${it.channel} · ${it.sender ?: "Absender unbekannt"}" },
        message?.text,
        summary,
        packet?.takeIf { it.transport != null }?.scopeLabel(),
        formatHops(resolver),
    )
}

internal fun radioClock(zone: ZoneId): DateTimeFormatter = radioTime.withZone(zone)

internal fun LiveRow.formatHops(resolver: HopResolver): String? =
    packet?.path?.takeIf { it.prefixes.isNotEmpty() }?.let { path ->
        resolver.resolve(path).joinToString(" → ") { hop ->
            when (hop.status) {
                HopStatus.UNKNOWN -> hop.prefix
                HopStatus.AMBIGUOUS -> "${hop.prefix} (${hop.matches.size}?)"
                HopStatus.UNIQUE -> hop.matches.single().displayName.compactHopName()
            }
        }
    }

private fun String.compactHopName(): String {
    val singleLine = replace('\n', ' ').replace('\r', ' ')
    val limit = 18
    return if (singleLine.codePointCount(0, singleLine.length) > limit) {
        singleLine.substring(0, singleLine.offsetByCodePoints(0, limit)) + "…"
    } else singleLine
}
