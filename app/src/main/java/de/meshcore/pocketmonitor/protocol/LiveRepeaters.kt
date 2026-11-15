package de.meshcore.pocketmonitor.protocol

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

internal data class HeardRepeater(
    val id: String,
    val name: String,
    val receivedAt: Long,
    val elapsedMs: Long,
    val rssi: Int,
    val snr: Double,
    val count: Int = 1,
)

internal enum class RepeaterSort(val label: String, val order: Comparator<HeardRepeater>) {
    RECENT("Zuletzt gehört", compareByDescending { it.elapsedMs }),
    NAME("Name", compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
    RSSI("RSSI", compareByDescending { it.rssi }),
    SNR("SNR", compareByDescending { it.snr }),
    COUNT("Empfangsanzahl", compareByDescending { it.count }),
}

internal class RepeaterAttribution(contacts: List<CompanionContact>) {
    // Include other node types in collision checks before using a contact name.
    private val byPrefix = (1..3).flatMap { width ->
        contacts.groupBy { it.publicKey.take(width * 2) }.entries
    }.associate { it.key to it.value }
    private val names = contacts.associate { it.publicKey to it.name }

    fun receive(frame: ByteArray, receivedAt: Long, elapsedMs: Long): HeardRepeater? {
        val event = CompanionEventDecoder.decode(frame) as? CompanionEvent.RadioRx ?: return null
        val radio = event.radioBytes ?: return null
        val rssi = event.rssiDbm ?: return null
        val snr = event.snrDb ?: return null
        val packet = MeshPacketDecoder.decode(radio, noKeys, calculateComparison = false)
        if (packet.status != DecodeStatus.DECODED && packet.status != DecodeStatus.ENCRYPTED) return null
        val path = packet.path ?: return null
        val last = path.prefixes.lastOrNull()
        if (last != null) {
            if (packet.header?.route?.isDirect != false || packet.header.type == PacketType.TRACE) return null
            val matches = byPrefix[last].orEmpty()
            val contact = matches.singleOrNull()?.takeIf { it.type == CompanionContact.TYPE_REPEATER }
            val id = if (last.length == 2) last else contact?.publicKey ?: last
            val name = when {
                matches.size > 1 -> "$last (${matches.size}?)"
                last.length == 2 -> contact?.name?.takeIf { it.isNotBlank() }?.let { "$it ($last?)" } ?: "$last?"
                else -> contact?.name?.ifBlank { last } ?: last
            }
            return HeardRepeater(id, name, receivedAt, elapsedMs, rssi, snr)
        }
        val advert = packet.payload as? MeshPayload.Advert ?: return null
        if (advert.flags?.and(0x0f) != CompanionContact.TYPE_REPEATER) return null
        val start = if (packet.header?.route?.hasTransportCodes == true) 6 else 2
        if (!verifyAdvert(radio, start)) return null
        val name = names[advert.publicKey]?.takeIf { it.isNotBlank() } ?: advert.name ?: advert.publicKey.take(12)
        return HeardRepeater(advert.publicKey, name, receivedAt, elapsedMs, rssi, snr)
    }

    companion object {
        private val noKeys = ChannelDecoding(includeBuiltIn = false, diagnoseScope = false)
        private val ed25519Prefix = "302a300506032b6570032100".hexToByteArray()

        private fun verifyAdvert(radio: ByteArray, start: Int): Boolean = try {
            val key = KeyFactory.getInstance("Ed25519").generatePublic(
                X509EncodedKeySpec(ed25519Prefix + radio.copyOfRange(start, start + 32)),
            )
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(radio, start, 36)
                update(radio, start + 100, radio.size - start - 100)
                verify(radio, start + 36, 64)
            }
        } catch (_: GeneralSecurityException) {
            // Older platforms without an Ed25519 provider still resolve flooded path prefixes.
            false
        }
    }
}

internal class RepeaterWindow {
    private val receptions = ArrayDeque<HeardRepeater>()
    private val latest = mutableMapOf<String, HeardRepeater>()

    fun add(event: HeardRepeater) {
        val count = (latest[event.id]?.count ?: 0) + 1
        latest[event.id] = event.copy(count = count)
        receptions.addLast(event)
    }

    fun snapshot(now: Long): List<HeardRepeater> {
        while (receptions.firstOrNull()?.elapsedMs?.let { now - it >= WINDOW_MS } == true) {
            val event = receptions.removeFirst()
            val current = latest.getValue(event.id)
            if (current.count == 1) latest.remove(event.id)
            else latest[event.id] = current.copy(count = current.count - 1)
        }
        return latest.values.toList()
    }

    fun nextExpiry(): Long? = receptions.firstOrNull()?.let { it.elapsedMs + WINDOW_MS }

    companion object {
        const val WINDOW_MS = 30_000L
    }
}
