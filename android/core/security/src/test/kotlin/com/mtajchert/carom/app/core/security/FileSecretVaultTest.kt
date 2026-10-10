package com.mtajchert.carom.app.core.security

import com.mtajchert.carom.app.core.protocol.Identity
import com.mtajchert.carom.app.core.protocol.Protocol
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class FileSecretVaultTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val cipher = SoftwareCipher()
    private val file by lazy { File(temp.root, "carom/vault.bin") }
    private fun vault(c: VaultCipher = cipher) = FileSecretVault(file, c, dispatcher)

    @Test
    fun `an empty vault loads as empty contents`() = scope.runTest {
        val contents = vault().load()
        assertNull(contents.identity)
        assertFalse(contents.isComplete)
    }

    @Test
    fun `contents survive a new instance and are encrypted at rest`() = scope.runTest {
        val identity = Identity.generate()
        val key = ByteArray(32) { 7 }
        vault().update { it.copy(identity = identity, deviceToken = "ct1.secret-token").withGroupKey(1, key, now = 10) }

        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("secret-token"))
        val reloaded = vault().load()
        assertEquals(identity.deviceId, reloaded.identity!!.deviceId)
        assertTrue(identity.signSk.contentEquals(reloaded.identity!!.signSk))
        assertEquals("ct1.secret-token", reloaded.deviceToken)
        assertTrue(key.contentEquals(reloaded.keysByKv.getValue(1)))
        assertTrue(reloaded.isComplete)
        assertFalse(reloaded.toString().contains("secret-token"))
    }

    @Test
    fun `a vault that cannot be decrypted is discarded so the app onboards again`() = scope.runTest {
        vault().update { it.copy(deviceToken = "t") }
        val restored = vault(SoftwareCipher()) // a different key, as after a restore
        assertNull(restored.load().deviceToken)
        assertFalse(file.exists())
    }

    @Test
    fun `wipe deletes the file and the key`() = scope.runTest {
        val v = vault()
        v.update { it.copy(deviceToken = "t") }
        v.wipe()
        assertFalse(file.exists())
        assertTrue(cipher.destroyed)
        assertNull(v.contents.value!!.deviceToken)
    }

    @Test
    fun `old group keys are pruned seven days after a newer key arrives`() {
        val day = 24 * 60 * 60 * 1000L
        val contents = VaultContents()
            .withGroupKey(1, ByteArray(32), now = 0)
            .withGroupKey(2, ByteArray(32) { 1 }, now = day)
        assertEquals(listOf(1L, 2L), contents.prunedGroupKeys(now = day + Protocol.Limits.OLD_KEY_RETENTION_MS).groupKeys.map { it.kv })
        assertEquals(listOf(2L), contents.prunedGroupKeys(now = day + Protocol.Limits.OLD_KEY_RETENTION_MS + 1).groupKeys.map { it.kv })
        assertEquals(2L, contents.currentKey!!.kv)
    }

    /** AES-GCM with an in-memory key: the same file format as the Keystore cipher. */
    private class SoftwareCipher : VaultCipher {
        private val key = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES")
        var destroyed = false

        override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray {
            val iv = ByteArray(12).also(SecureRandom()::nextBytes)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            c.updateAAD(aad)
            return iv + c.doFinal(plaintext)
        }

        override fun decrypt(ciphertext: ByteArray, aad: ByteArray): ByteArray {
            if (destroyed) throw GeneralSecurityException("destroyed")
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext, 0, 12))
            c.updateAAD(aad)
            return c.doFinal(ciphertext, 12, ciphertext.size - 12)
        }

        override fun destroyKey() {
            destroyed = true
        }
    }
}
