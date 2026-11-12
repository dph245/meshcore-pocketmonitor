package de.meshcore.pocketmonitor.protocol

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal class RadioChannel(val name: String, key: ByteArray) {
    private val key = key.copyOf()
    private val digest = sha256(this.key)
    val identity = digest.toHexString()
    val hash = digest[0]

    fun forLiveDiagnostics(): RadioChannel? = when {
        identity == builtIn.identity -> builtIn
        name.startsWith('#') && name.length > 1 && key.contentEquals(nameKey(name)) -> this
        else -> null
    }

    fun decrypt(payload: ByteArray): ByteArray? {
        val expected = hmac(key, payload.copyOfRange(3, payload.size)).copyOf(2)
        if (!MessageDigest.isEqual(expected, payload.copyOfRange(1, 3))) return null
        return Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        }.doFinal(payload, 3, payload.size - 3)
    }

    companion object {
        private val publicKey = "8b3387e9c5cdea6ac9e5edbaa115cd72".hexToByteArray()
        val builtIn = RadioChannel("Public", publicKey)
    }
}

internal class FloodScope(val name: String, private val key: ByteArray) {
    fun transportCode(type: Byte, payload: ByteArray): Int {
        val digest = hmac(key, byteArrayOf(type) + payload)
        val code = (digest[0].toInt() and 0xff) or ((digest[1].toInt() and 0xff) shl 8)
        return code.coerceIn(1, 0xfffe)
    }

    companion object {
        fun public(name: String) = FloodScope(name, nameKey(name))
    }
}

internal class ChannelDecoding(
    channels: List<RadioChannel> = emptyList(),
    defaultScope: FloodScope? = null,
    includeBuiltIn: Boolean = true,
) {
    private val byHash = (if (includeBuiltIn) listOf(RadioChannel.builtIn) + channels else channels)
        .distinctBy { it.identity }.groupBy { it.hash }

    private val scopes = if (defaultScope == null) publicScopes else publicScopes + defaultScope

    fun decrypt(payload: ByteArray): Pair<RadioChannel, ByteArray>? {
        for (channel in byHash[payload[0]].orEmpty()) {
            channel.decrypt(payload)?.let { return channel to it }
        }
        return null
    }

    fun scopeNames(code: Int, type: Int, payload: ByteArray): List<String> {
        if (code == 0 || code == 0xffff) return emptyList()
        return scopes.filter { it.transportCode(type.toByte(), payload) == code }.map { it.name }.distinct()
    }

    companion object {
        // Public regions also recognized by MeshCore OnAir. Other names can come from the Companion.
        private val publicScopes = listOf(
            "#de", "#de-ni", "#de-ni-wf", "#bsmesh", "#de-mitte", "#de-nord", "#de-harz",
            "#de-ni-h", "#de-ni-hi", "#de-ni-he", "#de-ni-wob", "#elm-asse", "#de-st",
        ).map(FloodScope::public)

        val default = ChannelDecoding()
    }
}

private fun nameKey(name: String) = sha256(name.encodeToByteArray()).copyOf(16)
private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

// For a 16-byte channel key the firmware uses a zero-extended 32-byte HMAC key.
// HMAC-SHA256 pads either representation with zeros to the same 64-byte block.
private fun hmac(key: ByteArray, bytes: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
    init(SecretKeySpec(key, "HmacSHA256"))
    doFinal(bytes)
}
