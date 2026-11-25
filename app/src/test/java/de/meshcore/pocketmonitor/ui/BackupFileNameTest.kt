package de.meshcore.pocketmonitor.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupFileNameTest {
    private val publicKey = "0123456789abcdef".repeat(4)

    @Test
    fun usesFileSafeCompanionName() {
        assertEquals(
            "PocketMonitor-Companion-München-Nord-Test.pmbackup",
            companionBackupFileName(" München/Nord:\tTest\\ ", publicKey),
        )
    }

    @Test
    fun fallsBackToPublicKeyPrefixForMissingOrUnusableName() {
        val expected = "PocketMonitor-Companion-0123456789ab.pmbackup"
        assertEquals(expected, companionBackupFileName(null, publicKey))
        assertEquals(expected, companionBackupFileName(" ../:*? ", publicKey))
        assertEquals("PocketMonitor-Companion.pmbackup", companionBackupFileName(null, null))
    }
}
