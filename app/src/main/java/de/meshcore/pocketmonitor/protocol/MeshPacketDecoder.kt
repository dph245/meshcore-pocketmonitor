package de.meshcore.pocketmonitor.protocol

import java.security.MessageDigest

/** Decodes MeshCore v1 wire packets. RF metadata and journal bytes are handled separately. */
internal object MeshPacketDecoder {
    private const val MAX_PATH_BYTES = 64
    private const val MAX_PAYLOAD_BYTES = 184
    private const val CIPHER_BLOCK_BYTES = 16
    private const val ADVERT_FIXED_BYTES = 100
    private const val MAX_ADVERT_APP_BYTES = 32
    private const val TRACE_FIXED_BYTES = 9

    fun decode(bytes: ByteArray): MeshPacket {
        var packet = MeshPacket(bytes.size)
        if (bytes.isEmpty()) return packet.malformed("Funkpaket-Header fehlt.")

        val header = MeshHeader(bytes.u8(0))
        packet = packet.copy(header = header)
        if (header.version != 1) return packet.unsupported("Payload-Version nicht unterstützt.")

        val pathOffset = if (header.route.hasTransportCodes) 5 else 1
        if (header.route.hasTransportCodes) {
            if (bytes.size < pathOffset) return packet.malformed("Transportcodes abgeschnitten.")
            packet = packet.copy(transport = TransportCodes(bytes.u16(1), bytes.u16(3)))
        }
        if (bytes.size <= pathOffset) return packet.malformed("Pfadlängenbyte fehlt.")

        val pathLength = bytes.u8(pathOffset)
        packet = packet.copy(pathLengthByte = pathLength)
        val prefixSize = (pathLength shr 6) + 1
        val count = pathLength and 0x3f
        val pathBytes = count * prefixSize
        val pathStart = pathOffset + 1
        val payloadStart = pathStart + pathBytes
        if (prefixSize == 4) return packet.unsupported("Reservierter Pfadmodus.")
        if (pathBytes > MAX_PATH_BYTES) return packet.malformed("Pfad überschreitet 64 Bytes.")
        if (payloadStart > bytes.size) return packet.malformed("Pfad abgeschnitten.")
        if (header.type != PacketType.TRACE) {
            packet = packet.copy(path = MeshPath(pathLength, prefixSize, bytes.prefixes(pathStart, payloadStart, prefixSize)))
        }
        val payloadSize = bytes.size - payloadStart
        packet = packet.copy(payloadSize = payloadSize)
        if (payloadSize !in 1..MAX_PAYLOAD_BYTES) return packet.malformed("Ungültige Payload-Länge.")
        val payload = bytes.copyOfRange(payloadStart, bytes.size)

        val decoded = when (header.type) {
            PacketType.REQ, PacketType.RESPONSE, PacketType.TXT_MSG, PacketType.PATH ->
                encrypted(packet, payload, 2)
            PacketType.GRP_TXT, PacketType.GRP_DATA -> encrypted(packet, payload, 1)
            PacketType.ANON_REQ -> encrypted(packet, payload, 33)
            PacketType.ADVERT -> advert(packet, payload)
            PacketType.ACK -> ack(packet, payload)
            PacketType.TRACE -> trace(packet, bytes.copyOfRange(pathStart, payloadStart), payload)
            PacketType.MULTIPART -> multipart(packet, payload)
            PacketType.CONTROL -> control(packet, payload)
            PacketType.RAW_CUSTOM -> packet.unsupported("Anwendungsspezifisches Format und Verschlüsselung unbekannt.")
            null -> packet.unsupported("Unbekannter Pakettyp.")
        }

        if (decoded.status == DecodeStatus.MALFORMED) return decoded
        // Packet::calculatePacketHash: type + (TRACE only: uint16 path_len, LE) + payload.
        val hash = MessageDigest.getInstance("SHA-256")
        hash.update(header.typeCode.toByte())
        if (header.type == PacketType.TRACE) {
            hash.update(pathLength.toByte())
            hash.update(0.toByte())
        }
        return decoded.copy(comparisonId = hash.digest(payload).hex(0, 8))
    }

    private fun encrypted(packet: MeshPacket, bytes: ByteArray, macOffset: Int): MeshPacket {
        val cipherSize = bytes.size - macOffset - 2
        if (cipherSize < 0) return packet.malformed("Verschlüsselter Payload-Header abgeschnitten.")
        val header = packet.header
        val group = macOffset == 1
        val anonymous = header?.type == PacketType.ANON_REQ
        val decoded = packet.copy(
            status = DecodeStatus.ENCRYPTED,
            payload = MeshPayload.Encrypted(
                macHex = bytes.hex(macOffset, macOffset + 2),
                ciphertextSize = cipherSize,
                senderPrefix = if (group || anonymous) null else bytes.u8(1),
                senderKey = if (anonymous) bytes.hex(1, 33) else null,
                recipientPrefix = if (group) null else bytes.u8(0),
                channelHash = if (group) bytes.u8(0) else null,
            ),
        )
        return if (cipherSize == 0 || cipherSize % CIPHER_BLOCK_BYTES != 0)
            decoded.malformed("Verschlüsselte Daten fehlen oder sind nicht blockausgerichtet.")
        else decoded
    }

    private fun advert(packet: MeshPacket, bytes: ByteArray): MeshPacket {
        if (bytes.size !in ADVERT_FIXED_BYTES..(ADVERT_FIXED_BYTES + MAX_ADVERT_APP_BYTES)) {
            return packet.malformed("Ungültige ADVERT-Payload-Länge.")
        }
        val flags = bytes.getOrNull(ADVERT_FIXED_BYTES)?.toInt()?.and(0xff)
        var offset = ADVERT_FIXED_BYTES + 1
        val hasLocation = flags != null && flags and 0x10 != 0
        if (hasLocation) offset += 8
        if (flags != null && flags and 0x20 != 0) offset += 2
        if (flags != null && flags and 0x40 != 0) offset += 2
        if (flags != null && bytes.size < offset) return packet.malformed("ADVERT-Appdaten abgeschnitten.")
        val name = if (flags != null && flags and 0x80 != 0) {
            try {
                bytes.decodeToString(offset, bytes.size, throwOnInvalidSequence = true)
                    .takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
                    ?: return packet.malformed("ADVERT-Name leer oder enthält Steuerzeichen.")
            } catch (_: java.nio.charset.CharacterCodingException) {
                return packet.malformed("ADVERT-Name ist kein gültiges UTF-8.")
            }
        } else null
        return packet.copy(
            payload = MeshPayload.Advert(
                publicKey = bytes.hex(0, 32),
                timestampSeconds = bytes.u32(32),
                signature = bytes.hex(36, ADVERT_FIXED_BYTES),
                flags = flags,
                name = name,
                latitude = if (hasLocation) bytes.u32(101).toInt() / 1e6 else null,
                longitude = if (hasLocation) bytes.u32(105).toInt() / 1e6 else null,
            ),
        )
    }

    private fun trace(packet: MeshPacket, snrs: ByteArray, bytes: ByteArray): MeshPacket {
        val pathLength = requireNotNull(packet.pathLengthByte)
        if (packet.header?.route?.isDirect != true || pathLength >= MAX_PATH_BYTES || bytes.size < TRACE_FIXED_BYTES) {
            return packet.malformed("Ungültiger TRACE-Header oder abgeschnittene TRACE-Payload.")
        }
        // Mesh::onRecvPacket uses 1 << (flags & 3) for TRACE prefixes, independent of the header.
        val flags = bytes.u8(8)
        val prefixSize = 1 shl (flags and 3)
        val plannedBytes = bytes.size - TRACE_FIXED_BYTES
        if (plannedBytes % prefixSize != 0 || snrs.size > plannedBytes / prefixSize) {
            return packet.malformed("TRACE-Pfad und SNR-Messwerte passen nicht zusammen.")
        }
        return packet.copy(
            path = MeshPath(pathLength, prefixSize, bytes.prefixes(TRACE_FIXED_BYTES, bytes.size, prefixSize),
                traceSnrDb = snrs.map { it.toInt() / 4.0 }),
            payload = MeshPayload.Trace(bytes.u32(0), bytes.u32(4), flags),
        )
    }

    private fun multipart(packet: MeshPacket, bytes: ByteArray): MeshPacket {
        val type = bytes.u8(0) and 0x0f
        val remaining = bytes.u8(0) shr 4
        val multipart = MeshPayload.Multipart(remaining, type, null)
        if (type != PacketType.ACK.code) {
            return packet.copy(payload = multipart).unsupported("Multipart-Untertyp nicht unterstützt.")
        }
        val decoded = ack(packet, bytes, offset = 1)
        return decoded.copy(payload = multipart.copy(ack = decoded.payload as? MeshPayload.Ack))
    }

    private fun ack(packet: MeshPacket, bytes: ByteArray, offset: Int = 0): MeshPacket {
        val size = bytes.size - offset
        if (size < 4) return packet.malformed("ACK-Kennung benötigt 4 Bytes.")
        // BaseChatMesh adds an extended attempt byte and a random byte to ordinary text ACKs.
        val decoded = packet.copy(payload = MeshPayload.Ack(
            token = bytes.u32(offset),
            attemptByte = if (size == 6) bytes.u8(offset + 4) else null,
            randomByte = if (size == 6) bytes.u8(offset + 5) else null,
        ))
        return if (size == 4 || size == 6) decoded else decoded.unsupported("Unbekannte ACK-Erweiterung.")
    }

    private fun control(packet: MeshPacket, bytes: ByteArray): MeshPacket {
        val flags = bytes.u8(0)
        val control = packet.copy(payload = MeshPayload.Control(flags))
        return when (flags shr 4) {
            8 -> {
                if (bytes.size != 6 && bytes.size < 10) return control.malformed("DISCOVER_REQ abgeschnitten.")
                control.copy(payload = MeshPayload.Discover.Request(
                    bytes.u32(2), flags, bytes.u8(1), if (bytes.size >= 10) bytes.u32(6) else null,
                ))
            }
            9 -> {
                if (bytes.size != 14 && bytes.size != 38) return control.malformed("DISCOVER_RESP benötigt 8 oder 32 Schlüsselbytes.")
                control.copy(payload = MeshPayload.Discover.Response(
                    bytes.u32(2), flags, bytes[1].toInt() / 4.0, bytes.hex(6, bytes.size),
                ))
            }
            else -> control.unsupported("Unbekannter CONTROL-Untertyp.")
        }
    }

    private fun MeshPacket.malformed(issue: String) = copy(status = DecodeStatus.MALFORMED, issue = issue)
    private fun MeshPacket.unsupported(issue: String) = copy(status = DecodeStatus.UNSUPPORTED, issue = issue)
    private fun ByteArray.u8(offset: Int) = this[offset].toInt() and 0xff
    private fun ByteArray.u16(offset: Int) = u8(offset) or (u8(offset + 1) shl 8)
    private fun ByteArray.u32(offset: Int) = u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)
    private fun ByteArray.hex(from: Int, until: Int) = toHexString(from, until, HexFormat.UpperCase)
    private fun ByteArray.prefixes(from: Int, until: Int, width: Int) =
        (from until until step width).map { hex(it, it + width) }
}
