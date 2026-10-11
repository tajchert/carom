package com.mtajchert.carom.app.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * A sealed envelope this device sends (docs/CLIENTS.md "Sending").
 *
 * Only the sealed envelope is stored, never the plaintext: a re-seal after `409 inactive` opens
 * our own envelope with the group key. Sent rows are kept until [expiresAt] so replies can be
 * checked against [recipients] (SPEC §3.3 rule 7).
 */
@Entity(
    tableName = "outbox",
    indices = [Index(value = ["state", "next_attempt_at"]), Index(value = ["expires_at"])],
)
data class OutboxEntity(
    @PrimaryKey
    @ColumnInfo(name = "msg_id")
    val msgId: String,
    val type: String,
    /** JSON array of recipient device ids, in the order sent. */
    val recipients: String,
    /** The sealed envelope as compact JSON. */
    @ColumnInfo(name = "envelope_json")
    val envelopeJson: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    /** createdAt + ttl_s; after this the message is dropped even if never delivered. */
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long,
    /** PENDING, SENT or DROPPED. */
    val state: String,
    val attempts: Int,
    @ColumnInfo(name = "next_attempt_at")
    val nextAttemptAt: Long,
    /** Hub error code of the last failure; never message content. */
    @ColumnInfo(name = "last_error")
    val lastError: String?,
    /** Hub seq once posted. */
    val seq: Long?,
)

/** A msg_id that passed the receiver checks (SPEC §3.3 rule 4). */
@Entity(tableName = "seen_messages", indices = [Index(value = ["seen_at"])])
data class SeenMessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "msg_id")
    val msgId: String,
    @ColumnInfo(name = "seen_at")
    val seenAt: Long,
)

/**
 * One device from `GET /v1/devices`. [recordJson] is the opened record plaintext, present only
 * when the record passed the SPEC §5.3 membership checks (the device is trusted).
 */
@Entity(tableName = "directory")
data class DirectoryDeviceEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "sign_pub")
    val signPub: String,
    @ColumnInfo(name = "kx_pub")
    val kxPub: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "last_seen")
    val lastSeen: Long?,
    @ColumnInfo(name = "record_json")
    val recordJson: String?,
    /** The record's `updated`, kept to reject rollbacks. */
    @ColumnInfo(name = "record_updated")
    val recordUpdated: Long?,
    /** The kv the trusted record was sealed under. */
    @ColumnInfo(name = "record_kv")
    val recordKv: Long?,
)

/** Per-app forwarding mode, for apps seen posting notifications (no QUERY_ALL_PACKAGES). */
@Entity(tableName = "notification_apps")
data class NotificationAppEntity(
    @PrimaryKey
    @ColumnInfo(name = "package_name")
    val packageName: String,
    val label: String,
    /** FORWARD, HIDE_CONTENT or OFF. */
    val mode: String,
    @ColumnInfo(name = "last_posted_at")
    val lastPostedAt: Long,
    /** "Ongoing: Forward": also forward this app's ongoing notifications that are not Live Updates, progress or calls. Added in schema 4. */
    @ColumnInfo(name = "forward_ongoing", defaultValue = "0")
    val forwardOngoing: Boolean = false,
)

/** A short activity line or notice for Home. Never stores full clipboard or notification content. */
@Entity(tableName = "recent_items", indices = [Index(value = ["at"])])
data class RecentItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** A `RecentItemKind` name. */
    val kind: String,
    val summary: String,
    @ColumnInfo(name = "device_id")
    val deviceId: String?,
    val at: Long,
)

/**
 * A notification key this phone forwarded (`notification.posted@1`), so `notification.removed@1`
 * is still sent after the listener reconnects or the process restarts. Holds only the system
 * notification key, never content. Added in schema version 2.
 */
@Entity(tableName = "forwarded_notifications", indices = [Index(value = ["forwarded_at"])])
data class ForwardedNotificationEntity(
    @PrimaryKey
    val key: String,
    @ColumnInfo(name = "forwarded_at")
    val forwardedAt: Long,
)

/**
 * A convergent icon blob this phone uploaded (V2 spec §3.2). The id already depends on the group
 * key, so a rekey makes old ids unreachable; [kv] only lets old rows be dropped. Holds only the
 * public blob id, never a key. Added in schema version 4.
 */
@Entity(tableName = "icon_uploads", indices = [Index(value = ["uploaded_at"])])
data class IconUploadEntity(
    @PrimaryKey
    val id: String,
    val kv: Long,
    @ColumnInfo(name = "uploaded_at")
    val uploadedAt: Long,
)
