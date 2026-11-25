package de.meshcore.pocketmonitor.backup

import java.io.ByteArrayOutputStream
import java.io.EOFException
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupEncryptionTest {
    private val password = "synthetisches-testpasswort".toCharArray()

    @Test
    fun multiBlockBackupRoundTripsAndRequiresAnAuthenticatedEnd() {
        val data = ByteArray(150_000) { (it % 113).toByte() }
        val encrypted = ByteArrayOutputStream().also {
            BackupEncryption.encrypt(password, data.inputStream(), it)
        }.toByteArray()
        fun read(bytes: ByteArray, pass: CharArray = password) =
            BackupEncryption.decrypt(pass, bytes.inputStream()).use { it.readBytes() }

        assertArrayEquals(data, read(encrypted))
        assertThrows(AEADBadTagException::class.java) { read(encrypted, "falsch".toCharArray()) }
        val tampered = encrypted.copyOf().apply { this[70_000] = (this[70_000].toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) { read(tampered) }
        assertThrows(EOFException::class.java) { read(encrypted.copyOf(encrypted.size - 20)) }
        assertThrows(IllegalStateException::class.java) { read(encrypted + byteArrayOf(0)) }
    }
}
