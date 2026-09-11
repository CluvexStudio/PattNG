package com.v2ray.ang.core

import com.v2ray.ang.enums.AetherProtocol
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AetherIdentityManagerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val quotes = "\"\"\""

    private fun keyFile(deviceId: String, ipv4: String = "172.16.0.2", ipv6: String = "2606:4700:110:8a36::1") =
        listOf(
            "device_id = \"$deviceId\"",
            "access_token = \"secret-token\"",
            "cert_pem = $quotes",
            "-----BEGIN CERTIFICATE-----",
            "device_id = \"hidden-in-pem\"",
            "-----END CERTIFICATE-----",
            quotes,
            "cert_issued_at = 1757580000",
            "ipv4 = \"$ipv4\"",
            "ipv6 = \"$ipv6\"",
            "wg_private_key = \"c2VjcmV0\"",
        ).joinToString("\n")

    private fun workDir(vararg files: Pair<String, String>): File =
        folder.newFolder("aether").apply {
            files.forEach { (name, text) -> File(this, name).writeText(text) }
        }

    @Test
    fun onlyTheIdentityFieldsAreReadFromAKeyFile() {
        val identity = AetherIdentityManager.parse(keyFile("a1b2c3d4-e5f6"))
        assertEquals(AetherIdentity("a1b2c3d4-e5f6", "172.16.0.2", "2606:4700:110:8a36::1"), identity)
    }

    @Test
    fun aFileWithoutADeviceIsNotAKey() {
        assertNull(AetherIdentityManager.parse("ipv4 = \"172.16.0.2\""))
        assertNull(AetherIdentityManager.parse("device_id = \"\""))
        assertNull(AetherIdentityManager.parse(""))
    }

    @Test
    fun eachProtocolReadsItsOwnKeyFiles() {
        val dir = workDir(
            AetherIdentityManager.MASQUE_FILE to keyFile("masque"),
            AetherIdentityManager.WIREGUARD_FILE to keyFile("outer"),
            AetherIdentityManager.WIREGUARD_INNER_FILE to keyFile("inner"),
        )

        assertEquals("masque", AetherIdentityManager.status(dir, AetherProtocol.MASQUE).primary?.deviceId)
        assertEquals("outer", AetherIdentityManager.status(dir, AetherProtocol.WIREGUARD).primary?.deviceId)

        val gool = AetherIdentityManager.status(dir, AetherProtocol.GOOL)
        assertEquals("outer", gool.primary?.deviceId)
        assertEquals("inner", gool.secondary?.deviceId)
    }

    @Test
    fun aMissingKeyFileMeansNoKey() {
        val dir = workDir(AetherIdentityManager.WIREGUARD_FILE to keyFile("outer"))

        assertNull(AetherIdentityManager.status(dir, AetherProtocol.MASQUE).primary)
        assertNull(AetherIdentityManager.status(dir, AetherProtocol.GOOL).secondary)
        assertNull(AetherIdentityManager.status(File(folder.root, "absent"), AetherProtocol.WIREGUARD).primary)
    }

    @Test
    fun wireguardAndGoolShareAKeyWhileMasqueHasItsOwn() {
        assertTrue(AetherIdentityManager.sharesIdentity(AetherProtocol.MASQUE, AetherProtocol.MASQUE))
        assertTrue(AetherIdentityManager.sharesIdentity(AetherProtocol.WIREGUARD, AetherProtocol.GOOL))
        assertTrue(AetherIdentityManager.sharesIdentity(AetherProtocol.GOOL, AetherProtocol.GOOL))
        assertFalse(AetherIdentityManager.sharesIdentity(AetherProtocol.MASQUE, AetherProtocol.WIREGUARD))
        assertFalse(AetherIdentityManager.sharesIdentity(AetherProtocol.GOOL, AetherProtocol.MASQUE))
    }

    @Test
    fun theCoreSaysWhenANewKeyIsReady() {
        val single = "[2026-09-11T10:00:00.000Z INFO  aether] [+] identity ready: device=a1b2 ipv4=172.16.0.2 ipv6=2606::1"
        val pair = "[2026-09-11T10:00:00.000Z INFO  aether] [+] outer device=a1b2 ipv4=172.16.0.2 | inner device=c3d4 ipv4=172.16.0.2"

        assertTrue(AetherIdentityManager.isReady(AetherProtocol.MASQUE, single))
        assertTrue(AetherIdentityManager.isReady(AetherProtocol.WIREGUARD, single))
        assertTrue(AetherIdentityManager.isReady(AetherProtocol.GOOL, pair))
        assertFalse(AetherIdentityManager.isReady(AetherProtocol.GOOL, single))
        assertFalse(AetherIdentityManager.isReady(AetherProtocol.MASQUE, "[+] no masque identity found; provisioning"))
    }

    @Test
    fun aSuccessfulRenewalReplacesTheOldKeys() = runBlocking {
        val dir = workDir(AetherIdentityManager.MASQUE_FILE to keyFile("old"))
        val previous = File(folder.root, "aether-previous")

        val renewed = AetherIdentityManager.replaceIdentities(dir, previous) {
            File(dir, AetherIdentityManager.MASQUE_FILE).writeText(keyFile("new"))
            true
        }

        assertTrue(renewed)
        assertEquals("new", AetherIdentityManager.status(dir, AetherProtocol.MASQUE).primary?.deviceId)
        assertFalse(previous.exists())
    }

    @Test
    fun aFailedRenewalRestoresTheOldKeys() = runBlocking {
        val dir = workDir(AetherIdentityManager.MASQUE_FILE to keyFile("old"))
        val previous = File(folder.root, "aether-previous")

        val renewed = AetherIdentityManager.replaceIdentities(dir, previous) {
            File(dir, AetherIdentityManager.WIREGUARD_FILE).writeText(keyFile("partial"))
            false
        }

        assertFalse(renewed)
        assertEquals("old", AetherIdentityManager.status(dir, AetherProtocol.MASQUE).primary?.deviceId)
        assertFalse(File(dir, AetherIdentityManager.WIREGUARD_FILE).exists())
        assertFalse(previous.exists())
    }

    @Test
    fun aCancelledRenewalRestoresTheOldKeys() = runBlocking {
        val dir = workDir(AetherIdentityManager.MASQUE_FILE to keyFile("old"))
        val previous = File(folder.root, "aether-previous")

        val job = launch {
            AetherIdentityManager.replaceIdentities(dir, previous) {
                File(dir, AetherIdentityManager.MASQUE_FILE).writeText(keyFile("half"))
                awaitCancellation()
            }
        }
        while (!File(dir, AetherIdentityManager.MASQUE_FILE).exists() || previous.listFiles().isNullOrEmpty()) yield()
        job.cancelAndJoin()

        assertEquals("old", AetherIdentityManager.status(dir, AetherProtocol.MASQUE).primary?.deviceId)
        assertFalse(previous.exists())
    }

    @Test
    fun aFailedFirstRegistrationLeavesNoKeyBehind() = runBlocking {
        val dir = File(folder.root, "aether")
        val previous = File(folder.root, "aether-previous")

        val renewed = AetherIdentityManager.replaceIdentities(dir, previous) {
            File(dir, AetherIdentityManager.MASQUE_FILE).writeText(keyFile("partial"))
            false
        }

        assertFalse(renewed)
        assertNull(AetherIdentityManager.status(dir, AetherProtocol.MASQUE).primary)
    }
}
