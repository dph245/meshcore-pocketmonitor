package de.meshcore.pocketmonitor.protocol

internal enum class HopStatus { UNKNOWN, UNIQUE, AMBIGUOUS }

internal data class ResolvedHop(val prefix: String, val matches: List<CompanionContact>) {
    val status: HopStatus get() = when (matches.size) {
        0 -> HopStatus.UNKNOWN
        1 -> HopStatus.UNIQUE
        else -> HopStatus.AMBIGUOUS
    }
}

internal class HopResolver(contacts: List<CompanionContact>) {
    private val byPrefix = buildMap {
        val repeaters = contacts.filter { it.type == CompanionContact.TYPE_REPEATER }
        // RF paths use 1/2/3 bytes; TRACE uses 1/2/4/8 bytes. Keys here are hex.
        // DISCOVER also sends full 32-byte keys.
        for (width in listOf(2, 4, 6, 8, 16, 64)) {
            putAll(repeaters.groupBy { it.publicKey.take(width) })
        }
    }

    fun resolve(path: MeshPath): List<ResolvedHop> = path.prefixes.map(::resolve)

    fun resolve(prefix: String): ResolvedHop =
        ResolvedHop(prefix, byPrefix[prefix.uppercase()].orEmpty())

    companion object {
        val EMPTY = HopResolver(emptyList())
    }
}
