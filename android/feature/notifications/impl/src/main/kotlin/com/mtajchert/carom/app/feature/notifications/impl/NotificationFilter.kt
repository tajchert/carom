package com.mtajchert.carom.app.feature.notifications.impl

import com.mtajchert.carom.app.core.model.AppForwardingMode

/**
 * What the forwarder needs from one posted notification, read once off the main thread from a
 * `StatusBarNotification` (see [RichExtractor]). Plain data, so filters and mapping are testable
 * without the framework. Image references are in [imageSources]; nothing is loaded yet.
 *
 * Content fields are already plain text. [toString] never prints them.
 */
data class NotificationSnapshot(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val postedAt: Long,
    val ongoing: Boolean = false,
    val foregroundService: Boolean = false,
    val groupSummary: Boolean = false,
    val localOnly: Boolean = false,
    val mediaStyle: Boolean = false,
    /** The channel importance from the current ranking, or null when unknown. */
    val importance: Int? = null,
    val title: String? = null,
    val text: String? = null,
    val subText: String? = null,
    val category: String? = null,
    /** Android 15+ replaced the content with "Sensitive notification content hidden". */
    val redacted: Boolean = false,
    /** A group summary the system made (`FLAG_GROUP_SUMMARY` with an override group key). */
    val autogroupSummary: Boolean = false,
    /** `FLAG_PROMOTED_ONGOING`: an Android 16 Live Update. */
    val promoted: Boolean = false,
    val rich: RichContent = RichContent(),
    val imageSources: Map<ImageSlot, ImageSource> = emptyMap(),
) {
    override fun toString() = "NotificationSnapshot(key=$key, app=$packageName)" // never log content
}

/** Why a notification was not forwarded. Safe to log. */
enum class SkipReason {
    OWN,
    AUTOGROUP_SUMMARY,
    LOCAL_ONLY,
    MEDIA,
    MIN_IMPORTANCE,
    ONGOING,
    PAUSED,
    SCREEN_UNLOCKED,
    APP_OFF,
}

/** The phone-side filters of V2 spec §5.1, as pure functions. */
object NotificationFilter {
    /** `NotificationManager.IMPORTANCE_MIN`. Silent (LOW) channels are forwarded since V2. */
    private const val IMPORTANCE_MIN = 1

    /**
     * The checks that depend only on the notification. A notification that fails them is not
     * even recorded in the app list.
     */
    fun structural(n: NotificationSnapshot, ownPackage: String): SkipReason? = when {
        n.packageName == ownPackage -> SkipReason.OWN
        n.autogroupSummary -> SkipReason.AUTOGROUP_SUMMARY
        n.localOnly -> SkipReason.LOCAL_ONLY
        // Media notifications are not forwarded as posted; the media card replaces them (spec §5.2).
        n.mediaStyle -> SkipReason.MEDIA
        // MIN channels (and NONE, which never reaches a listener). Unknown importance passes.
        n.importance != null && n.importance in 0..IMPORTANCE_MIN -> SkipReason.MIN_IMPORTANCE
        else -> null
    }

    /**
     * Ongoing and foreground-service notifications: allowed for Live Updates, progress and calls,
     * and for apps with "Ongoing: Forward" on. Needs the app setting, so it runs after the app
     * was recorded (otherwise an app that only posts ongoing notifications could never be turned on).
     */
    fun ongoing(n: NotificationSnapshot, forwardOngoing: Boolean): SkipReason? = when {
        !(n.ongoing || n.foregroundService) -> null
        n.promoted || n.rich.progress != null || n.rich.template == RichContent.TEMPLATE_CALL || forwardOngoing -> null
        else -> SkipReason.ONGOING
    }

    /** The user's settings: global pause, "Only when screen is locked", then the per-app mode. */
    fun policy(
        mode: AppForwardingMode,
        paused: Boolean,
        onlyWhenLocked: Boolean,
        screenLocked: Boolean,
    ): SkipReason? = when {
        paused -> SkipReason.PAUSED
        onlyWhenLocked && !screenLocked -> SkipReason.SCREEN_UNLOCKED
        mode == AppForwardingMode.OFF -> SkipReason.APP_OFF
        else -> null
    }
}

/**
 * Defaults for apps seen for the first time: *Forward*, except a small built-in deny list of
 * noisy system packages, which start *Off* (the user can still turn them on).
 */
object DefaultAppModes {
    val DENY_LIST: Set<String> = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.shell",
        "com.android.vending",
        "com.android.providers.downloads",
        "com.android.bluetooth",
        "com.android.nfc",
        "com.google.android.gms",
        "com.google.android.as",
        "com.google.android.apps.wellbeing",
        "com.google.android.apps.turbo",
        "com.google.android.projection.gearhead",
        "com.samsung.android.lool",
        "com.sec.android.app.samsungapps",
    )

    fun defaultFor(packageName: String): AppForwardingMode =
        if (packageName in DENY_LIST) AppForwardingMode.OFF else AppForwardingMode.FORWARD
}
