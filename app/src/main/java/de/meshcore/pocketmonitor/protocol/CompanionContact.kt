package de.meshcore.pocketmonitor.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class CompanionContact(
    val publicKey: String,
    val name: String,
    val type: Int,
    val flags: Int,
    val outPathLength: Int,
    val outPathHex: String,
    val lastAdvertSeconds: Long,
    val latitude: Double,
    val longitude: Double,
    val lastModifiedSeconds: Long,
) {
    val displayName: String get() = name.ifBlank { "Ohne Namen" }
    val typeLabel: String get() = when (type) {
        0 -> "Unbekannt"
        1 -> "Client"
        TYPE_REPEATER -> "Repeater"
        3 -> "RoomServer"
        4 -> "Sensor"
        else -> "Typ 0x${type.toString(16).uppercase().padStart(2, '0')}"
    }

    companion object {
        const val TYPE_REPEATER = 2

        // MyMesh::writeContactRespFrame, including the response code.
        fun decode(frame: ByteArray): CompanionContact? {
            if (frame.size != 148) return null
            val fields = ByteBuffer.wrap(frame, 132, 16).order(ByteOrder.LITTLE_ENDIAN)
            val nameEnd = (100 until 132).firstOrNull { frame[it] == 0.toByte() } ?: 132
            return CompanionContact(
                publicKey = frame.toHexString(1, 33, HexFormat.UpperCase),
                name = frame.decodeToString(100, nameEnd),
                type = frame[33].toInt() and 0xff,
                flags = frame[34].toInt() and 0xff,
                outPathLength = frame[35].toInt() and 0xff,
                outPathHex = frame.toHexString(36, 100, HexFormat.UpperCase),
                lastAdvertSeconds = fields.int.toUInt().toLong(),
                latitude = fields.int / 1e6,
                longitude = fields.int / 1e6,
                lastModifiedSeconds = fields.int.toUInt().toLong(),
            )
        }
    }
}
