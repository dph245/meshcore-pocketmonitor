package de.meshcore.pocketmonitor.tcp

import java.io.DataInputStream
import java.io.IOException

internal object CompanionTcpFrames {
    const val MAX_PAYLOAD = 176
    private const val TO_COMPANION = 0x3c
    private const val FROM_COMPANION = 0x3e

    fun encode(payload: ByteArray): ByteArray {
        require(payload.size in 1..MAX_PAYLOAD)
        return ByteArray(3 + payload.size).apply {
            this[0] = TO_COMPANION.toByte()
            this[1] = payload.size.toByte()
            this[2] = (payload.size ushr 8).toByte()
            payload.copyInto(this, destinationOffset = 3)
        }
    }

    fun read(input: DataInputStream): ByteArray? {
        val marker = input.read()
        if (marker == -1) return null
        if (marker != FROM_COMPANION) throw InvalidFrame()
        val low = input.readUnsignedByte()
        val length = low or (input.readUnsignedByte() shl 8)
        if (length !in 1..MAX_PAYLOAD) throw InvalidFrame()
        return ByteArray(length).also { input.readFully(it) }
    }

    class InvalidFrame : IOException()
}
