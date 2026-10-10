package com.mtajchert.carom.app.core.protocol

import com.google.crypto.tink.aead.internal.InsecureNonceChaCha20Poly1305
import com.google.crypto.tink.aead.internal.InsecureNonceChaCha20Poly1305Jce
import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Source of cryptographically secure random bytes. Injectable so tests and vectors are deterministic. */
fun interface Rng {
    fun bytes(length: Int): ByteArray
}

/** The default [Rng], backed by [SecureRandom]. */
object SecureRng : Rng {
    private val random = SecureRandom()
    override fun bytes(length: Int): ByteArray = ByteArray(length).also(random::nextBytes)
}

/**
 * SPEC §2 primitives, in one place so every module uses the same settings.
 *
 * Every function that handles untrusted input returns null/false instead of throwing.
 * Programmer errors (wrong key lengths) throw [IllegalArgumentException].
 */
object Crypto {
    private val ZERO_NONCE = ByteArray(12)

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        // An empty HMAC key is valid (it is zero-padded), but SecretKeySpec rejects it.
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** HKDF-SHA256 (RFC 5869). An empty salt is equivalent to 32 zero bytes. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int = 32): ByteArray =
        Hkdf.computeHkdf("HMACSHA256", ikm, salt, info.utf8(), length)

    /** ChaCha20-Poly1305 (RFC 8439) with the all-zero nonce. Keys MUST be single-use (SPEC §2). */
    fun aeadSeal(key: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == Protocol.KEY_LENGTH) { "AEAD key must be 32 bytes" }
        return if (InsecureNonceChaCha20Poly1305Jce.isSupported()) {
            InsecureNonceChaCha20Poly1305Jce.create(key).encrypt(ZERO_NONCE, plaintext, aad)
        } else {
            InsecureNonceChaCha20Poly1305(key).encrypt(ZERO_NONCE, plaintext, aad)
        }
    }

    /** Returns null when authentication fails. */
    fun aeadOpen(key: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray? {
        require(key.size == Protocol.KEY_LENGTH) { "AEAD key must be 32 bytes" }
        if (ciphertext.size < Protocol.AEAD_TAG_LENGTH) return null
        return try {
            if (InsecureNonceChaCha20Poly1305Jce.isSupported()) {
                InsecureNonceChaCha20Poly1305Jce.create(key).decrypt(ZERO_NONCE, ciphertext, aad)
            } else {
                InsecureNonceChaCha20Poly1305(key).decrypt(ZERO_NONCE, ciphertext, aad)
            }
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    /** Ed25519 public key for a 32-byte RFC 8032 seed. */
    fun ed25519PublicKey(signSk: ByteArray): ByteArray {
        require(signSk.size == 32) { "sign_sk must be 32 bytes" }
        return Ed25519Sign.KeyPair.newKeyPairFromSeed(signSk).publicKey
    }

    /** Deterministic RFC 8032 signature. */
    fun ed25519Sign(signSk: ByteArray, message: ByteArray): ByteArray {
        require(signSk.size == 32) { "sign_sk must be 32 bytes" }
        return Ed25519Sign(signSk).sign(message)
    }

    /**
     * RFC 8032 verification (canonical `S`, cofactorless), not ZIP-215, matching CryptoKit.
     * Never throws.
     */
    fun ed25519Verify(signPub: ByteArray, message: ByteArray, sig: ByteArray): Boolean {
        if (signPub.size != 32 || sig.size != 64) return false
        return try {
            Ed25519Verify(signPub).verify(sig, message)
            true
        } catch (_: GeneralSecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    fun x25519PublicKey(kxSk: ByteArray): ByteArray {
        require(kxSk.size == 32) { "kx_sk must be 32 bytes" }
        return X25519.publicFromPrivate(kxSk)
    }

    /** X25519 shared secret; null when the peer key is invalid or the result is all-zero. */
    fun x25519Shared(kxSk: ByteArray, peerPub: ByteArray): ByteArray? {
        require(kxSk.size == 32) { "kx_sk must be 32 bytes" }
        if (peerPub.size != 32) return null
        return try {
            X25519.computeSharedSecret(kxSk, peerPub).takeUnless { it.isAllZero() }
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
