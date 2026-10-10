package com.mtajchert.carom.app.core.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.security.GeneralSecurityException

/** Runs against the real Android Keystore; the JVM tests cover FileSecretVault with a software cipher. */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreCipherTest {
    private val cipher = AndroidKeystoreCipher(alias = "carom.vault.test")

    @After
    fun tearDown() = cipher.destroyKey()

    @Test
    fun roundTripsAndRejectsTamperingAndDestroyedKeys() {
        val aad = "aad".encodeToByteArray()
        val ct = cipher.encrypt("secret".encodeToByteArray(), aad)
        assertArrayEquals("secret".encodeToByteArray(), cipher.decrypt(ct, aad))

        ct[ct.size - 1] = (ct[ct.size - 1].toInt() xor 1).toByte()
        expectFailure { cipher.decrypt(ct, aad) }

        val fresh = cipher.encrypt("x".encodeToByteArray(), aad)
        cipher.destroyKey()
        expectFailure { cipher.decrypt(fresh, aad) }
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
        } catch (_: GeneralSecurityException) {
            return
        }
        fail("expected GeneralSecurityException")
    }
}
