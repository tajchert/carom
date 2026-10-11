package com.mtajchert.carom.app.core.model

/**
 * Non-secret settings stored in Preferences DataStore (docs/CLIENTS.md "Identity and storage").
 *
 * Secrets (identity keys, group keys, device token) are NOT here; they live in core:security.
 *
 * @property hubUrl the Hub base URL from the setup or pair URI; null before onboarding.
 *   No Hub URL is ever baked into a build.
 * @property deviceId this device's id once onboarding finished (also derivable from the identity).
 * @property deviceName the user-visible name written into this device's record.
 * @property forwardingPaused global pause for notification forwarding.
 * @property forwardOnlyWhenLocked forward notifications only while the screen is locked (default off).
 * @property clipboardArrivalNotification post a quiet notification when a clipboard arrives (default on).
 * @property ongoingNotification show the optional ongoing "Carom" notification (default off).
 * @property actionsFromMac when the phone listens for taps, replies and media commands from a Mac (spec §5.3).
 * @property actionWindowMinutes how long the phone stays reachable after a notification in
 *   [ActionsFromMac.AFTER_NOTIFICATION] mode; one of [ActionsFromMac.WINDOW_MINUTES].
 */
data class UserPreferences(
    val themeBrand: ThemeBrand = ThemeBrand.DEFAULT,
    val darkThemeConfig: DarkThemeConfig = DarkThemeConfig.FOLLOW_SYSTEM,
    val hubUrl: String? = null,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val forwardingPaused: Boolean = false,
    val forwardOnlyWhenLocked: Boolean = false,
    val clipboardArrivalNotification: Boolean = true,
    val ongoingNotification: Boolean = false,
    val actionsFromMac: ActionsFromMac = ActionsFromMac.AFTER_NOTIFICATION,
    val actionWindowMinutes: Int = ActionsFromMac.DEFAULT_WINDOW_MINUTES,
)

/** The "Actions from Mac" setting. */
enum class ActionsFromMac {
    /** The phone does not advertise `notification.action@1` and `media.command@1`. */
    OFF,

    /** A WebSocket stays open for [UserPreferences.actionWindowMinutes] after a forwarded notification. */
    AFTER_NOTIFICATION,

    /** A foreground service keeps the WebSocket open while the phone is paired. */
    ALWAYS,
    ;

    companion object {
        val WINDOW_MINUTES: List<Int> = listOf(2, 5, 15)
        const val DEFAULT_WINDOW_MINUTES: Int = 5

        /** [minutes] when it is one of [WINDOW_MINUTES], otherwise [DEFAULT_WINDOW_MINUTES]. */
        fun windowOrDefault(minutes: Int?): Int = minutes?.takeIf { it in WINDOW_MINUTES } ?: DEFAULT_WINDOW_MINUTES
    }
}

/** Theme brand options. */
enum class ThemeBrand {
    /** Use the app's default color scheme. */
    DEFAULT,

    /** Use Android 12+ dynamic colors derived from the user's wallpaper. */
    DYNAMIC,
}

/** Dark theme configuration options. */
enum class DarkThemeConfig {
    FOLLOW_SYSTEM,
    LIGHT,
    DARK,
}
