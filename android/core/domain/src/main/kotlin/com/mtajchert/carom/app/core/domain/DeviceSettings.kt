package com.mtajchert.carom.app.core.domain

import com.mtajchert.carom.app.core.data.repository.UserPreferencesRepository
import com.mtajchert.carom.app.core.data.sync.CaromSync
import com.mtajchert.carom.app.core.data.sync.DirectoryEntry
import com.mtajchert.carom.app.core.data.sync.DirectoryStore
import com.mtajchert.carom.app.core.data.sync.LocalDeviceProfileProvider
import com.mtajchert.carom.app.core.data.sync.ReceiveRulesStore
import com.mtajchert.carom.app.core.data.sync.RevokeResult
import com.mtajchert.carom.app.core.model.ActionsFromMac
import com.mtajchert.carom.app.core.protocol.DeviceIds
import com.mtajchert.carom.app.core.protocol.MessageTypes
import com.mtajchert.carom.app.core.protocol.ReceiveRule
import com.mtajchert.carom.app.core.protocol.Routing
import com.mtajchert.carom.app.core.security.SecretVault
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

/** Why a rename was refused. */
enum class RenameOutcome { RENAMED, SAVED_OFFLINE, EMPTY, TOO_LONG }

/** Renames this device (docs/CLIENTS.md "Rename: this device only") and republishes its record. */
class RenameThisDeviceUseCase @Inject constructor(
    private val preferences: UserPreferencesRepository,
    private val sync: CaromSync,
) {
    suspend operator fun invoke(name: String): RenameOutcome {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return RenameOutcome.EMPTY
        if (trimmed.length > MAX_NAME_LENGTH) return RenameOutcome.TOO_LONG
        preferences.setDeviceName(trimmed)
        // Offline, the record is republished on the next directory refresh with the new name.
        return if (sync.publishOwnRecord()) RenameOutcome.RENAMED else RenameOutcome.SAVED_OFFLINE
    }

    companion object {
        const val MAX_NAME_LENGTH = 64
    }
}

/**
 * Sets "Actions from Mac" and republishes this device's record, because Off removes the action
 * types from `caps` (spec §3.6). Returns false when the record could not be published now; the
 * next directory refresh notices the difference and retries.
 */
class SetActionsFromMacUseCase @Inject constructor(
    private val preferences: UserPreferencesRepository,
    private val sync: CaromSync,
) {
    suspend operator fun invoke(mode: ActionsFromMac): Boolean {
        preferences.setActionsFromMac(mode)
        return sync.publishOwnRecord()
    }
}

/** What removing a device did, for a short message. */
enum class RemoveOutcome { REMOVED, REMOVED_REKEY_PENDING, LEFT, FAILED }

/** Removes any device (SPEC §5.4): revoke, then rekey. Removing this device leaves the Hub. */
class RemoveDeviceUseCase @Inject constructor(private val sync: CaromSync) {
    suspend operator fun invoke(deviceId: String): RemoveOutcome = when (sync.revoke(deviceId)) {
        RevokeResult.Revoked -> RemoveOutcome.REMOVED
        RevokeResult.RevokedRekeyPending -> RemoveOutcome.REMOVED_REKEY_PENDING
        RevokeResult.RevokedSelf -> RemoveOutcome.LEFT
        RevokeResult.NotPaired -> RemoveOutcome.LEFT
        is RevokeResult.Failed -> RemoveOutcome.FAILED
    }
}

/** A device that may send to us, as a column of the receive matrix. */
data class ReceiveSender(val id: String, val name: String) {
    val fingerprint: String get() = DeviceIds.fingerprint(id)
}

/**
 * This device's `receive` rules as a matrix (docs/CLIENTS.md "Receive from: a matrix per type"):
 * rows are the types this device can receive, columns the other trusted devices.
 */
data class ReceiveMatrix(
    val types: List<String>,
    val senders: List<ReceiveSender>,
    /** type to the sender ids allowed to send it. */
    val allowed: Map<String, Set<String>>,
) {
    fun isAllowed(type: String, senderId: String): Boolean = senderId in allowed[type].orEmpty()

    companion object {
        val EMPTY = ReceiveMatrix(emptyList(), emptyList(), emptyMap())
    }
}

class ObserveReceiveMatrixUseCase @Inject constructor(
    private val directory: DirectoryStore,
    private val vault: SecretVault,
    private val rulesStore: ReceiveRulesStore,
) {
    operator fun invoke(): Flow<ReceiveMatrix> = combine(
        directory.observe(),
        vault.contents.onStart { vault.load() },
        rulesStore.rules,
    ) { entries, contents, rules ->
        val selfId = contents?.identity?.deviceId ?: return@combine ReceiveMatrix.EMPTY
        matrixOf(entries, selfId, rules)
    }

    companion object {
        /** [rules] are the user's stored choice; null means the published record's rules. */
        fun matrixOf(entries: List<DirectoryEntry>, selfId: String, rules: Map<String, ReceiveRule>? = null): ReceiveMatrix {
            val self = entries.firstOrNull { it.id == selfId }?.record ?: return ReceiveMatrix.EMPTY
            val receive = rules ?: self.receive
            val senders = entries.filter { it.trusted && it.id != selfId }
                .map { ReceiveSender(it.id, it.record!!.name) }
            val types = (self.caps + receive.keys).distinct()
                .sortedBy { type -> MessageTypes.ALL.indexOf(type).let { if (it < 0) Int.MAX_VALUE else it } }
            val ids = senders.map { it.id }
            val allowed = types.associateWith { type ->
                when (val rule = receive[type]) {
                    ReceiveRule.Everyone -> ids.toSet()
                    is ReceiveRule.Only -> rule.senders.filter { it in ids }.toSet()
                    null -> emptySet()
                }
            }
            return ReceiveMatrix(types, senders, allowed)
        }
    }
}

/**
 * The single writer of this device's `receive` rules (SPEC §5.1): the Devices matrix and the
 * clipboard "Receive clipboard from" setting both go through here. The rules are stored in
 * [ReceiveRulesStore] (which the published record is built from) and the record is republished once.
 */
class SetReceiveRuleUseCase @Inject constructor(
    private val directory: DirectoryStore,
    private val vault: SecretVault,
    private val rulesStore: ReceiveRulesStore,
    private val profile: LocalDeviceProfileProvider,
    private val sync: CaromSync,
) {
    /**
     * Changes one cell of the receive matrix. Allowing every known sender becomes `"*"`, which
     * also lets future devices send, like the pairing default.
     * Returns false when the record could not be published now (it is retried on the next refresh).
     */
    suspend operator fun invoke(type: String, senderId: String, allow: Boolean): Boolean {
        val selfId = vault.load().identity?.deviceId ?: return false
        val entries = directory.all()
        val senders = entries.filter { it.trusted && it.id != selfId }.map { it.id }.toSet()
        val now = when (val rule = currentRules(entries, selfId)[type]) {
            ReceiveRule.Everyone -> senders
            is ReceiveRule.Only -> rule.senders.toSet()
            null -> emptySet()
        }
        val next = if (allow) now + senderId else now - senderId
        val rule = if (senders.isNotEmpty() && next.containsAll(senders)) ReceiveRule.Everyone else ReceiveRule.Only(next.toList())
        return setRule(type, rule)
    }

    /**
     * Sets `receive[type]` to [rule] ("Receive clipboard from": `"*"` or an explicit list) and
     * republishes the record. An empty list removes the type, which means "do not send me this".
     */
    suspend fun setRule(type: String, rule: ReceiveRule): Boolean {
        val selfId = vault.load().identity?.deviceId ?: return false
        val rules = currentRules(directory.all(), selfId).toMutableMap()
        when {
            rule is ReceiveRule.Only && rule.senders.isEmpty() -> rules.remove(type)
            rule is ReceiveRule.Only -> rules[type] = ReceiveRule.Only(DeviceIds.sorted(rule.senders.toSet()))
            else -> rules[type] = rule
        }
        rulesStore.setReceiveRules(rules)
        return sync.publishOwnRecord()
    }

    /** The stored choice, else what the record published, else the SPEC §5.1 defaults. */
    private suspend fun currentRules(entries: List<DirectoryEntry>, selfId: String): Map<String, ReceiveRule> =
        rulesStore.receiveRules()
            ?: entries.firstOrNull { it.id == selfId }?.record?.receive
            ?: Routing.defaultReceive(profile.profile().kind)
}
