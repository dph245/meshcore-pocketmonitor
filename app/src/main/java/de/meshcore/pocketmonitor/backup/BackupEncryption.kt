package de.meshcore.pocketmonitor.backup

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Version 1 uses independent authenticated blocks so large histories have bounded memory use. */
internal object BackupEncryption {
    private val magic = "PMBACKUP\u0001".encodeToByteArray()
    private const val BLOCK = 64 * 1024
    private const val TAG = 16
    private const val MAX_BYTES = 16L * 1024 * 1024 * 1024
    private const val PBKDF_ROUNDS = 600_000

    fun encrypt(password: CharArray, input: InputStream, output: OutputStream) {
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val noncePrefix = ByteArray(8).also(random::nextBytes)
        val header = magic + salt + noncePrefix
        val key = key(password, salt)
        val target = DataOutputStream(output)
        target.write(header)
        val block = ByteArray(BLOCK)
        var index = 0
        var total = 0L
        try {
            while (true) {
                val count = input.readNBytesCompat(block)
                total += count
                check(total <= MAX_BYTES) { "Die Sicherung überschreitet 16 GiB." }
                target.writeInt(count)
                target.write(cipher(Cipher.ENCRYPT_MODE, key, header, index++, count).doFinal(block, 0, count))
                if (count == 0) break
            }
            target.flush()
        } finally {
            block.fill(0)
        }
    }

    fun decrypt(password: CharArray, input: InputStream): InputStream {
        val source = DataInputStream(input)
        val header = ByteArray(magic.size + 24).also(source::readFully)
        check(header.copyOfRange(0, magic.size).contentEquals(magic)) { "Unbekanntes Sicherungsformat." }
        val key = key(password, header.copyOfRange(magic.size, magic.size + 16))
        return object : InputStream() {
            private var index = 0
            private var total = 0L
            private var block = ByteArray(0)
            private var offset = 0
            private var ended = false

            override fun read(): Int = if (nextBlock()) block[offset++].toInt() and 0xff else -1

            override fun read(bytes: ByteArray, start: Int, length: Int): Int {
                if (length == 0) return 0
                if (!nextBlock()) return -1
                val count = minOf(length, block.size - offset)
                block.copyInto(bytes, start, offset, offset + count)
                offset += count
                return count
            }

            private fun nextBlock(): Boolean {
                if (offset < block.size) return true
                if (ended) return false
                block.fill(0)
                val count = source.readInt()
                total += count
                check(count in 0..BLOCK && total <= MAX_BYTES && index <= MAX_BYTES / BLOCK + 1) {
                    "Ungültige Größe der Sicherung."
                }
                val sealed = ByteArray(count + TAG).also(source::readFully)
                block = cipher(Cipher.DECRYPT_MODE, key, header, index++, count).doFinal(sealed)
                offset = 0
                if (count == 0) {
                    check(source.read() == -1) { "Die Sicherungsdatei enthält unerwartete Daten." }
                    ended = true
                }
                return !ended
            }

            override fun close() {
                block.fill(0)
                source.close()
            }
        }
    }

    private fun key(password: CharArray, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password, salt, PBKDF_ROUNDS, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return try {
                SecretKeySpec(bytes, "AES")
            } finally {
                bytes.fill(0)
            }
        } finally {
            spec.clearPassword()
        }
    }

    private fun cipher(mode: Int, key: SecretKey, header: ByteArray, index: Int, count: Int): Cipher {
        val nonce = ByteBuffer.allocate(12).put(header, header.size - 8, 8).putInt(index).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG * 8, nonce))
            updateAAD(header)
            updateAAD(ByteBuffer.allocate(8).putInt(index).putInt(count).array())
        }
    }

    private fun InputStream.readNBytesCompat(block: ByteArray): Int {
        var offset = 0
        while (offset < block.size) {
            val read = read(block, offset, block.size - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }
}
