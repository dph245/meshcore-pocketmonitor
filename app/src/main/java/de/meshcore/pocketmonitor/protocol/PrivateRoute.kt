package de.meshcore.pocketmonitor.protocol

internal class PrivateRoute private constructor(val width: Int, val hops: List<String>) {
    val encodedLength: Int get() = ((width - 1) shl 6) or hops.size
    val signature: String get() = "$encodedLength:${hops.joinToString("")}"

    companion object {
        const val MULTIBYTE_MIN_VERSION = 10

        fun maxHops(width: Int): Int = minOf(63, 64 / width)

        fun normalizeHop(input: String, width: Int): String? {
            val hash = input.trim()
            if (hash.length != width * 2 || hash.any { it !in "0123456789abcdefABCDEF" }) return null
            return hash.uppercase()
        }

        fun create(width: Int, hashes: List<String>): PrivateRoute? {
            if (width !in 1..3 || hashes.size !in 1..maxHops(width)) return null
            return PrivateRoute(width, hashes.map { normalizeHop(it, width) ?: return null })
        }
    }
}
