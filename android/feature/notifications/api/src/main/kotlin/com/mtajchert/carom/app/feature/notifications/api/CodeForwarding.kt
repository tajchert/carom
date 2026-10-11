package com.mtajchert.carom.app.feature.notifications.api

import android.content.Context
import android.content.Intent

/** Whether Android shows this app verification codes. */
enum class CodeForwardingState {
    /** Below Android 15 nothing is redacted, so there is nothing to enable. */
    UNSUPPORTED,

    /** Android 15+ hides codes from Carom's listener. */
    NOT_ENABLED,

    /** Carom may receive sensitive notification content. */
    ENABLED,
}

/**
 * The "Enable code forwarding" flow, for screens outside the notifications feature (onboarding).
 * The implementation lives in `:feature:notifications:impl`.
 */
interface CodeForwardingAccess {
    fun state(): CodeForwardingState

    /** Opens the screen that creates the Companion Device Manager association. */
    fun enableIntent(context: Context): Intent

    /** `adb shell cmd appops set --user 0 <package> RECEIVE_SENSITIVE_NOTIFICATIONS allow` for this build. */
    fun adbCommand(): String
}
