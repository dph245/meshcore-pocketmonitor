package de.meshcore.pocketmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import de.meshcore.pocketmonitor.protocol.CompanionProtocol
import de.meshcore.pocketmonitor.protocol.DecodeStatus
import de.meshcore.pocketmonitor.protocol.HopResolver
import de.meshcore.pocketmonitor.protocol.HopStatus
import de.meshcore.pocketmonitor.protocol.MeshPacket
import de.meshcore.pocketmonitor.protocol.MeshPayload
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val UNKNOWN = "unbekannt"
private val detailTime = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS XXX")

internal val LiveRow.typeLabel: String
    get() = when (packet?.payload) {
        is MeshPayload.Discover.Request -> "DISCOVER_REQ"
        is MeshPayload.Discover.Response -> "DISCOVER_RESP"
        else -> packet?.header?.let { it.type?.name ?: "Typ ${it.typeCode.hex(1)}" }
            ?: if (original.code == CompanionProtocol.RF_LOG) "Funk-RX" else "Companion-RX"
    }

@Composable
internal fun PacketDetails(row: LiveRow, resolver: HopResolver, onDismiss: () -> Unit) {
    val original = row.original
    val packet = row.packet
    val header = packet?.header
    val path = packet?.path
    val hops = remember(path, resolver) { path?.let(resolver::resolve).orEmpty() }
    val event = row.event
    val isRadio = original.code == CompanionProtocol.RF_LOG
    val time = remember(original.timeMs) {
        Instant.ofEpochMilli(original.timeMs).atZone(ZoneId.systemDefault()).format(detailTime)
    }
    val hex = remember(original) {
        original.frame.bytes.toHexString(HexFormat.UpperCase).chunked(2).chunked(8)
            .joinToString("\n") { it.joinToString(" ") }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${row.typeLabel} · #${original.sequence}") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
        text = {
            SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Field("Empfang am Telefon", time)
                    Field("Companion-Eventcode", original.code?.hex(2) ?: UNKNOWN)
                    Field("Notification", "${original.frame.bytes.size} B")
                    if (isRadio) {
                        Field("Funkpaket", event?.radioBytes?.let { "${it.size} B" } ?: UNKNOWN)
                        Field("RSSI", event?.rssiDbm?.let { "$it dBm" } ?: UNKNOWN)
                        Field("SNR", event?.snrDb?.let { "${it.dbText()} dB" } ?: UNKNOWN)
                        Field("Status", when {
                            row.decoderError != null -> "Interner Decoderfehler"
                            event?.issue != null -> "Unvollständig"
                            else -> when (packet?.status) {
                                DecodeStatus.DECODED -> "Felder dekodiert"
                                DecodeStatus.ENCRYPTED -> "Verschlüsselt"
                                DecodeStatus.MALFORMED -> "Fehlerhaft oder unvollständig"
                                DecodeStatus.UNSUPPORTED -> "Format unbekannt oder nicht unterstützt"
                                null -> UNKNOWN
                            }
                        })
                        (row.decoderError ?: event?.issue ?: packet?.issue)?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }
                        Field("Header", header?.raw?.hex(2) ?: UNKNOWN)
                        Field("Payload-Typ", header?.let { "${it.type?.name ?: UNKNOWN} (${it.typeCode.hex(1)})" } ?: UNKNOWN)
                        Field("Payload-Version", header?.let { "v${it.version}" } ?: UNKNOWN)
                        Field("Routing", header?.route?.name ?: UNKNOWN)
                        packet?.scopeLabel()?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (header?.route?.hasTransportCodes == true) {
                            Field("Scope-Code", packet.transport?.scope?.hex(4) ?: UNKNOWN)
                            Field("Transportcode 2", packet.transport?.reserved?.hex(4) ?: UNKNOWN)
                        }
                        Field("Pfadlängenbyte", packet?.pathLengthByte?.hex(2) ?: UNKNOWN)
                        if (path == null) {
                            Field("Pfad", UNKNOWN)
                        } else {
                            val label = when {
                                path.traceSnrDb != null -> "Gemessene / geplante TRACE-Hops"
                                header?.route?.isDirect == true -> "Verbleibende Hops"
                                else -> "Aufgezeichnete Hops"
                            }
                            Field(label, path.traceSnrDb?.let { "${it.size} / ${path.prefixes.size}" }
                                ?: path.hopCount.toString())
                            Field("Präfixbreite", "${path.prefixSize} B")
                            if (hops.isEmpty()) Field("Hop-Präfixe", "Keine")
                            hops.forEachIndexed { index, hop ->
                                val status = when (hop.status) {
                                    HopStatus.UNKNOWN -> "Unbekannt"
                                    HopStatus.UNIQUE -> "Eindeutig"
                                    HopStatus.AMBIGUOUS -> "Mehrdeutig (${hop.matches.size} Treffer)"
                                }
                                Field("Hop ${index + 1} · ${hop.prefix}", status)
                                for (contact in hop.matches) {
                                    Field(contact.displayName, contact.typeLabel)
                                    if (hop.status == HopStatus.AMBIGUOUS) Field("Public Key", contact.publicKey)
                                }
                            }
                            path.traceSnrDb?.let {
                                Field("TRACE-SNR", it.joinToString(" → ") { snr -> "${snr.dbText()} dB" }.ifEmpty { "Keine" })
                            }
                        }
                        Field("Payload-Länge", packet?.payloadSize?.let { "$it B" } ?: UNKNOWN)
                        Field("Vergleichskennung", packet?.comparisonId ?: UNKNOWN)
                        PayloadDetails(packet?.payload)
                    } else {
                        Field("Status", if (original.code == null) "Leere Notification" else "Companion-Ereignis ohne Funkdecoder")
                    }
                    Text("Vollständige Notification (Hex)", style = MaterialTheme.typography.labelLarge)
                    Text(hex.ifEmpty { "Keine Bytes" }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        },
    )
}

@Composable
private fun PayloadDetails(payload: MeshPayload?) {
    val group = payload as? MeshPayload.GroupText
    val encrypted = group?.encrypted ?: payload as? MeshPayload.Encrypted
    val advert = payload as? MeshPayload.Advert
    val discover = payload as? MeshPayload.Discover.Response
    val sender = when {
        group?.sender != null -> "${group.sender} (unverifiziert)"
        advert != null -> "${advert.publicKey} (Signatur ungeprüft)"
        discover != null -> "${discover.publicKeyOrPrefix} (${if (discover.fullPublicKey) "Schlüssel" else "Präfix"}, ungeprüft)"
        encrypted?.senderKey != null -> "${encrypted.senderKey} (ungeprüft)"
        encrypted?.senderPrefix != null -> "${encrypted.senderPrefix.hex(2)} (Präfix)"
        else -> UNKNOWN
    }
    val recipient = when {
        encrypted?.recipientPrefix != null -> "${encrypted.recipientPrefix.hex(2)} (Präfix)"
        encrypted?.channelHash != null -> "Kanal-Hash ${encrypted.channelHash.hex(2)}"
        else -> UNKNOWN
    }
    Field("Absenderangabe", sender)
    Field("Empfängerangabe", recipient)
    when (payload) {
        is MeshPayload.Encrypted -> {
            Field("MAC (ungeprüft)", payload.macHex)
            Field("Verschlüsselte Daten", "${payload.ciphertextSize} B")
        }
        is MeshPayload.GroupText -> {
            Field("Kanal", payload.channel)
            Field("Nachricht", payload.text)
            Field("Nachrichtenzeit (Unix-s)", payload.timestampSeconds.toString())
            Field("Kanaltext-Flags", payload.flags.hex(2))
            Field("Kanal-MAC (geprüft)", payload.encrypted.macHex)
        }
        is MeshPayload.Advert -> {
            Field("Advert-Name (ungeprüft)", payload.name ?: UNKNOWN)
            Field("Advert-Zeit (Unix-s)", payload.timestampSeconds.toString())
            Field("Advert-Flags", payload.flags?.hex(2) ?: UNKNOWN)
            if (payload.latitude != null) Field("Advert-Position", "${payload.latitude}, ${payload.longitude}")
        }
        is MeshPayload.Ack -> AckDetails(payload)
        is MeshPayload.Multipart -> {
            Field("Multipart-Typ", payload.typeCode.hex(1))
            Field("Multipart · verbleibend", payload.remaining.toString())
            payload.ack?.let { AckDetails(it) }
        }
        is MeshPayload.Trace -> {
            Field("TRACE-Tag", payload.tag.hex(8))
            Field("TRACE-Auth-Code", payload.authCode.hex(8))
            Field("TRACE-Flags", payload.flags.hex(2))
        }
        is MeshPayload.Discover.Request -> {
            Field("DISCOVER-Tag", payload.tag.hex(8))
            Field("DISCOVER-Flags", payload.flags.hex(2))
            Field("Typfilter", payload.typeFilter.hex(2))
            Field("Nur Präfix anfordern", if (payload.prefixOnly) "Ja" else "Nein")
            Field("Seit (Unix-s)", payload.sinceSeconds?.toString() ?: "0 (Standard)")
        }
        is MeshPayload.Discover.Response -> {
            Field("DISCOVER-Tag", payload.tag.hex(8))
            Field("DISCOVER-Flags", payload.flags.hex(2))
            Field("Knotentyp", payload.nodeType.hex(1))
            Field("SNR am antwortenden Knoten", "${payload.requestSnrDb.dbText()} dB")
        }
        is MeshPayload.Control -> Field("CONTROL-Flags", payload.flags.hex(2))
        null -> Unit
    }
}

@Composable
private fun AckDetails(ack: MeshPayload.Ack) {
    Field("ACK-Kennung", ack.token.hex(8))
    ack.attemptByte?.let { Field("ACK-Versuchsbyte", it.hex(2)) }
    ack.randomByte?.let { Field("ACK-Zufallsbyte", it.hex(2)) }
}

@Composable
private fun Field(label: String, value: String) {
    Text("$label · $value", style = MaterialTheme.typography.bodySmall)
}

internal fun MeshPacket.scopeLabel(): String? {
    val code = transport?.scope
    return when {
        code == null -> if (header?.route?.isDirect == false && header.route.hasTransportCodes == false)
            "Ohne Scope" else null
        code == 0 -> "Ohne Scope (*)"
        code == 0xffff -> "Reservierter Scope-Code ${code.hex(4)}"
        scopeNames.isEmpty() -> "Unbekannter Scope ${code.hex(4)}"
        scopeNames.size == 1 -> "Scope ${scopeNames.single()}"
        else -> "Mehrdeutiger Scope (${scopeNames.joinToString(" / ")})"
    }
}

internal fun Int.hex(digits: Int) = toLong().hex(digits)
internal fun Long.hex(digits: Int) = "0x" + toString(16).uppercase().padStart(digits, '0')
internal fun Double.dbText() = toString().replace('.', ',')
