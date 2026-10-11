package com.mtajchert.carom.app.core.model

/** Whether this device belongs to a group (docs/CLIENTS.md "Identity and storage"). */
sealed interface SessionState {
    /** Still loading secrets and settings. */
    data object Loading : SessionState

    /** No usable keys: first launch, reinstall, restore or revocation. Onboarding starts. */
    data object NotPaired : SessionState

    data class Paired(
        val hubUrl: String,
        val deviceId: String,
        val deviceName: String?,
        /** Current group key version. */
        val kv: Long,
    ) : SessionState
}

/** What the sync engine is doing; Home shows it. */
enum class ConnectionStatus {
    /** Not paired yet. */
    NOT_SET_UP,

    /** Paired and nothing to do. The phone has no persistent socket, so this is the normal state. */
    IDLE,

    /** Talking to the Hub right now. */
    SYNCING,

    /** The last attempt could not reach the Hub; work is queued and retried. */
    OFFLINE,

    /** The Hub refused this device (revoked or token invalid). Pair again to continue. */
    REJECTED,
}

enum class DeviceKind { PHONE, DESKTOP, CLI }

/** A device as shown in the device list (from the directory cache). */
data class DeviceSummary(
    val id: String,
    /** Null when the device's record could not be opened (not a trusted member yet). */
    val name: String?,
    val kind: DeviceKind?,
    val platform: String?,
    val lastSeen: Long?,
    val isSelf: Boolean,
    val trusted: Boolean,
) {
    /** The first 8 characters of the id after `dev_`, shown when pairing. */
    val fingerprint: String get() = id.removePrefix("dev_").take(8)
}

/** Per-app notification forwarding (docs/CLIENTS.md "Per-app setting"). */
enum class AppForwardingMode { FORWARD, HIDE_CONTENT, OFF }

data class NotificationAppSetting(
    val packageName: String,
    val label: String,
    val mode: AppForwardingMode,
    val lastPostedAt: Long,
    /** "Ongoing: Forward": also forward ongoing notifications that are not Live Updates, progress or calls. Default off. */
    val forwardOngoing: Boolean = false,
)

/** A line in Home's recent activity. Never holds full clipboard or notification content. */
data class RecentItem(
    val id: Long,
    val kind: RecentItemKind,
    /** Short, already-truncated text safe to show; for [RecentItemKind.DEVICE_REMOVED] the device name. */
    val summary: String,
    val deviceId: String?,
    val at: Long,
    /** For [RecentItemKind.DEVICE_REMOVED]: the name of the device that removed it, when known. */
    val actor: String? = null,
)

enum class RecentItemKind {
    CLIPBOARD_SENT,
    CLIPBOARD_RECEIVED,

    /** "<revoker> removed <name>" (SPEC §5.4); [RecentItem.actor] is the revoker. */
    DEVICE_REMOVED,

    /** "<name> was removed (no rekey)": it left the directory and no rekey followed within 2 minutes. */
    DEVICE_REMOVED_NO_REKEY,
}
