package com.mtajchert.carom.app.core.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mtajchert.carom.app.core.datastore.UserPreferencesDataSource
import com.mtajchert.carom.app.core.protocol.ReceiveRule
import com.mtajchert.carom.app.core.protocol.parseJsonOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Engine state the UI shows (Home banners, "last sync"). */
interface SyncStatusRepository {
    /** When messages were last fetched or received. */
    val lastSyncAt: Flow<Long?>

    /** True after `401`: this device must pair again (SPEC §5.4). */
    val rejected: Flow<Boolean>

    /** Notices up to this recent-item id were dismissed from Home's banners. */
    val dismissedNoticeId: Flow<Long>
    suspend fun dismissNotices(upToId: Long)
}

/**
 * This device's `receive` rules as chosen by the user (docs/CLIENTS.md "Receive from"). The one
 * source of truth for both the Devices matrix and the clipboard settings; the published record
 * is built from it ([AndroidDeviceProfileProvider]). Written only by `SetReceiveRuleUseCase`.
 */
interface ReceiveRulesStore {
    /** Null until the user changes something; the record then keeps its published rules. */
    val rules: Flow<Map<String, ReceiveRule>?>
    suspend fun receiveRules(): Map<String, ReceiveRule>?
    suspend fun setReceiveRules(rules: Map<String, ReceiveRule>)
}

/**
 * [SyncStateStore], [EnrollmentStore], [SyncStatusRepository] and [ReceiveRulesStore] on the app's
 * Preferences DataStore. Its keys are prefixed `sync_` and cleared on wipe; the shared preferences
 * (Hub URL, device name, toggles) stay in [UserPreferencesDataSource].
 */
@Singleton
class DataStoreSyncStateStore @Inject constructor(
    private val preferences: UserPreferencesDataSource,
    private val dataStore: DataStore<Preferences>,
) : SyncStateStore, EnrollmentStore, SyncStatusRepository, ReceiveRulesStore {

    private val data: Flow<Preferences> = dataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    private suspend fun current(): Preferences = data.first()

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        dataStore.edit { block(it) }
    }

    // SyncStateStore ---------------------------------------------------------

    override suspend fun lastClipboardTs(): Long? = preferences.lastClipboardTs()
    override suspend fun setLastClipboardTs(ts: Long) = preferences.setLastClipboardTs(ts)

    override suspend fun verifiedKeyring(): VerifiedKeyring? =
        current()[KEYRING]?.let { decode(KeyringDto.serializer(), it) }?.let { VerifiedKeyring(it.kv, it.by, it.members.toSet()) }

    override suspend fun setVerifiedKeyring(value: VerifiedKeyring) = edit {
        it[KEYRING] = json.encodeToString(KeyringDto.serializer(), KeyringDto(value.kv, value.by, value.members.sorted()))
    }

    override suspend fun pendingRemovals(): List<PendingRemoval> =
        current()[REMOVALS]?.let { decode(ListSerializer(RemovalDto.serializer()), it) }
            ?.map { PendingRemoval(it.id, it.name, it.at) }.orEmpty()

    override suspend fun setPendingRemovals(value: List<PendingRemoval>) = edit {
        if (value.isEmpty()) {
            it.remove(REMOVALS)
        } else {
            it[REMOVALS] = json.encodeToString(ListSerializer(RemovalDto.serializer()), value.map { p -> RemovalDto(p.deviceId, p.deviceName, p.detectedAt) })
        }
    }

    override suspend fun pendingRekey(): PendingRekey? =
        current()[REKEY]?.let { decode(RekeyDto.serializer(), it) }?.let { PendingRekey(it.kv, it.excluding.toSet()) }

    override suspend fun setPendingRekey(value: PendingRekey?) = edit {
        if (value == null) {
            it.remove(REKEY)
        } else {
            it[REKEY] = json.encodeToString(RekeyDto.serializer(), RekeyDto(value.kvAtRevoke, value.excluding.sorted()))
        }
    }

    override suspend fun isRejected(): Boolean = current()[REJECTED] ?: false
    override suspend fun setRejected(rejected: Boolean) = edit { it[REJECTED] = rejected }

    override suspend fun setLastSyncAt(at: Long) = edit { it[LAST_SYNC] = at }

    override suspend fun clear() = edit { prefs ->
        listOf(KEYRING, REMOVALS, REKEY, RECEIVE).forEach { prefs.remove(it) }
        prefs.remove(REJECTED)
        prefs.remove(LAST_SYNC)
        prefs.remove(DISMISSED)
    }

    // EnrollmentStore --------------------------------------------------------

    override suspend fun saveEnrollment(hubUrl: String, deviceId: String, deviceName: String) {
        preferences.setEnrollment(hubUrl, deviceId)
        preferences.setDeviceName(deviceName)
        setRejected(false)
    }

    // SyncStatusRepository ---------------------------------------------------

    override val lastSyncAt: Flow<Long?> = data.map { it[LAST_SYNC] }.distinctUntilChanged()
    override val rejected: Flow<Boolean> = data.map { it[REJECTED] ?: false }.distinctUntilChanged()
    override val dismissedNoticeId: Flow<Long> = data.map { it[DISMISSED] ?: 0L }.distinctUntilChanged()
    override suspend fun dismissNotices(upToId: Long) = edit { it[DISMISSED] = maxOf(it[DISMISSED] ?: 0L, upToId) }

    // ReceiveRulesStore ------------------------------------------------------

    override val rules: Flow<Map<String, ReceiveRule>?> = data.map { decodeRules(it[RECEIVE]) }.distinctUntilChanged()

    override suspend fun receiveRules(): Map<String, ReceiveRule>? = decodeRules(current()[RECEIVE])

    private fun decodeRules(text: String?): Map<String, ReceiveRule>? {
        val obj = text?.let { parseJsonOrNull(it) } as? JsonObject ?: return null
        return obj.mapNotNull { (type, rule) -> ReceiveRule.fromJson(rule)?.let { type to it } }.toMap()
    }

    override suspend fun setReceiveRules(rules: Map<String, ReceiveRule>) = edit {
        it[RECEIVE] = JsonObject(rules.mapValues { (_, rule) -> rule.toJson() }).toString()
    }

    private fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, text: String): T? =
        runCatching { json.decodeFromString(serializer, text) }.getOrNull()

    @Serializable
    private data class KeyringDto(val kv: Long, val by: String, val members: List<String>)

    @Serializable
    private data class RemovalDto(val id: String, val name: String, val at: Long)

    @Serializable
    private data class RekeyDto(val kv: Long, val excluding: List<String>)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val KEYRING = stringPreferencesKey("sync_verified_keyring")
        val REMOVALS = stringPreferencesKey("sync_pending_removals")
        val REKEY = stringPreferencesKey("sync_pending_rekey")
        val RECEIVE = stringPreferencesKey("sync_receive_rules")
        val REJECTED = booleanPreferencesKey("sync_rejected")
        val LAST_SYNC = longPreferencesKey("sync_last_sync_at")
        val DISMISSED = longPreferencesKey("sync_dismissed_notice_id")
    }
}
