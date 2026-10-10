package com.mtajchert.carom.app.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a non-exportable Android Keystore key (docs/CLIENTS.md "Identity and storage").
 *
 * The key never leaves the Keystore and is not part of any backup, so a restored vault file
 * cannot be decrypted on another device. No user authentication is required: the notification listener
 * must work while the device is locked.
 *
 * File layout: `iv(12) || ciphertext||tag`.
 */
class AndroidKeystoreCipher(private val alias: String = DEFAULT_ALIAS) : VaultCipher {

    override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(aad)
        val ct = cipher.doFinal(plaintext)
        val iv = cipher.iv
        check(iv.size == IV_LENGTH) { "unexpected IV length" }
        return ByteBuffer.allocate(iv.size + ct.size).put(iv).put(ct).array()
    }

    override fun decrypt(ciphertext: ByteArray, aad: ByteArray): ByteArray {
        if (ciphertext.size <= IV_LENGTH) throw GeneralSecurityException("vault file too short")
        val key = key(create = false) ?: throw GeneralSecurityException("vault key missing")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, ciphertext, 0, IV_LENGTH))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext, IV_LENGTH, ciphertext.size - IV_LENGTH)
    }

    override fun destroyKey() {
        keyStore().deleteEntry(alias)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun key(create: Boolean): SecretKey? {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS = "carom.vault.v1"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
        private const val IV_LENGTH = 12
    }
}
