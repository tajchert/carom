package com.mtajchert.carom.app.core.security

import com.mtajchert.carom.app.core.protocol.Identity
import com.mtajchert.carom.app.core.protocol.Protocol
import kotlinx.coroutines.flow.StateFlow

/** A group key with the time it was stored, so old keys can be pruned after 7 days (SPEC §5.5). */
class StoredGroupKey(val kv: Long, val key: ByteArray, val addedAt: Long) {
    init {
        require(key.size == Protocol.KEY_LENGTH) { "group key must be 32 bytes" }
    }

    override fun equals(other: Any?): Boolean =
        other is StoredGroupKey && kv == other.kv && addedAt == other.addedAt && key.contentEquals(other.key)

    override fun hashCode(): Int = 31 * kv.hashCode() + key.contentHashCode()

    override fun toString(): String = "StoredGroupKey(kv=$kv)" // never print keys
}

/**
 * Everything secret this device holds. Immutable; change it with [SecretVault.update].
 * [toString] never prints secret material.
 */
data class VaultContents(
    val identity: Identity? = null,
    /** Bearer token for the Hub (SPEC §6). */
    val deviceToken: String? = null,
    val groupKeys: List<StoredGroupKey> = emptyList(),
) {
    /** The newest group key; new messages are always sealed with it (SPEC §5.5). */
    val currentKey: StoredGroupKey? get() = groupKeys.maxByOrNull { it.kv }

    /** Group keys by kv, for opening envelopes. */
    val keysByKv: Map<Long, ByteArray> get() = groupKeys.associate { it.kv to it.key }

    /** True when this device can talk to its group: identity, token and a group key exist. */
    val isComplete: Boolean get() = identity != null && deviceToken != null && groupKeys.isNotEmpty()

    /** Adds or replaces the key for [kv]. */
    fun withGroupKey(kv: Long, key: ByteArray, now: Long): VaultContents =
        copy(groupKeys = groupKeys.filter { it.kv != kv } + StoredGroupKey(kv, key, now))

    /**
     * Drops keys older than [Protocol.Limits.OLD_KEY_RETENTION_MS] once a newer key exists.
     * The current key is always kept.
     */
    fun prunedGroupKeys(now: Long): VaultContents {
        val current = currentKey ?: return this
        val keep = groupKeys.filter { it.kv == current.kv || now - current.addedAt <= Protocol.Limits.OLD_KEY_RETENTION_MS }
        return copy(groupKeys = keep)
    }

    override fun toString(): String =
        "VaultContents(identity=${identity?.deviceId}, token=${if (deviceToken != null) "set" else "none"}, kvs=${groupKeys.map { it.kv }})"
}

/**
 * Storage for [VaultContents]. Implementations persist atomically and never back up or log
 * their contents.
 */
interface SecretVault {
    /** The last loaded or written contents; null until the first [load]. */
    val contents: StateFlow<VaultContents?>

    /**
     * Reads the vault. Returns empty contents when nothing is stored, and also when the stored
     * data cannot be opened (restored backup, lost Keystore key): the app then onboards again.
     */
    suspend fun load(): VaultContents

    /** Atomically replaces the contents with [transform] of the current ones and returns the result. */
    suspend fun update(transform: (VaultContents) -> VaultContents): VaultContents

    /** Deletes every secret, for revocation and "remove this device". */
    suspend fun wipe()
}
