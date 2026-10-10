package com.mtajchert.carom.app.core.security

import com.mtajchert.carom.app.core.protocol.B64
import com.mtajchert.carom.app.core.protocol.Identity
import com.mtajchert.carom.app.core.protocol.toB64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException

/** Encrypts the vault file. [AndroidKeystoreCipher] in the app; a software cipher in tests. */
interface VaultCipher {
    fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray

    /** @throws GeneralSecurityException when the data cannot be authenticated or the key is gone. */
    fun decrypt(ciphertext: ByteArray, aad: ByteArray): ByteArray

    /** Deletes the key so a wiped vault cannot be recovered from a stale copy. */
    fun destroyKey()
}

/**
 * [SecretVault] in one encrypted file. Writes go to a temporary file that is then renamed, so
 * a crash never leaves a half-written vault. Contents are cached in memory after the first load.
 */
class FileSecretVault(
    private val file: File,
    private val cipher: VaultCipher,
    private val ioDispatcher: CoroutineDispatcher,
) : SecretVault {
    private val mutex = Mutex()
    private val state = MutableStateFlow<VaultContents?>(null)
    override val contents: StateFlow<VaultContents?> = state.asStateFlow()

    override suspend fun load(): VaultContents = mutex.withLock { loadLocked() }

    override suspend fun update(transform: (VaultContents) -> VaultContents): VaultContents = mutex.withLock {
        val next = transform(loadLocked())
        withContext(ioDispatcher) { write(next) }
        state.value = next
        next
    }

    override suspend fun wipe() = mutex.withLock {
        withContext(ioDispatcher) {
            file.delete()
            File(file.path + TMP_SUFFIX).delete()
            runCatching { cipher.destroyKey() }
        }
        state.value = VaultContents()
    }

    private suspend fun loadLocked(): VaultContents {
        state.value?.let { return it }
        val loaded = withContext(ioDispatcher) { read() }
        state.value = loaded
        return loaded
    }

    private fun read(): VaultContents {
        if (!file.exists()) return VaultContents()
        return try {
            val plaintext = cipher.decrypt(file.readBytes(), AAD)
            decode(plaintext.decodeToString())
        } catch (_: GeneralSecurityException) {
            unusable()
        } catch (_: IOException) {
            unusable()
        } catch (_: IllegalArgumentException) {
            unusable()
        }
    }

    /** The data cannot be opened (restored copy, lost Keystore key, corruption): start over. */
    private fun unusable(): VaultContents {
        file.delete()
        return VaultContents()
    }

    private fun write(contents: VaultContents) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + TMP_SUFFIX)
        tmp.writeBytes(cipher.encrypt(encode(contents).encodeToByteArray(), AAD))
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("cannot replace the vault file")
        }
    }

    @Serializable
    private data class StoredKey(val kv: Long, val k: String, @SerialName("added_at") val addedAt: Long)

    @Serializable
    private data class Stored(
        val v: Int = 1,
        @SerialName("sign_sk") val signSk: String? = null,
        @SerialName("kx_sk") val kxSk: String? = null,
        val token: String? = null,
        val keys: List<StoredKey> = emptyList(),
    )

    private fun encode(c: VaultContents): String = JSON.encodeToString(
        Stored.serializer(),
        Stored(
            signSk = c.identity?.signSk?.toB64(),
            kxSk = c.identity?.kxSk?.toB64(),
            token = c.deviceToken,
            keys = c.groupKeys.map { StoredKey(it.kv, it.key.toB64(), it.addedAt) },
        ),
    )

    private fun decode(text: String): VaultContents {
        val s = JSON.decodeFromString(Stored.serializer(), text)
        require(s.v == 1) { "unknown vault version" }
        val identity = if (s.signSk != null && s.kxSk != null) Identity(B64.decode(s.signSk), B64.decode(s.kxSk)) else null
        return VaultContents(
            identity = identity,
            deviceToken = s.token,
            groupKeys = s.keys.map { StoredGroupKey(it.kv, B64.decode(it.k), it.addedAt) },
        )
    }

    private companion object {
        const val TMP_SUFFIX = ".tmp"
        val AAD = "carom/android/vault/v1".encodeToByteArray()
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
