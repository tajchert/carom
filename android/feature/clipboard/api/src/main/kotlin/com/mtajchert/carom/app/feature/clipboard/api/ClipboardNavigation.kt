package com.mtajchert.carom.app.feature.clipboard.api

import android.content.Context
import android.content.Intent
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Clipboard settings: "Send clipboard to", "Receive clipboard from" and the arrival notification. */
@Serializable
data object ClipboardSettingsRoute : NavKey

/** How other features start a clipboard send without depending on the implementation module. */
object ClipboardSend {
    /** Class name of the transparent send activity; checked by a test in the impl module. */
    const val ACTIVITY_CLASS = "com.mtajchert.carom.app.feature.clipboard.impl.ClipboardSendActivity"

    /**
     * Intent for the in-app "Send clipboard" button and the ongoing notification action. The
     * activity reads the clipboard once it has window focus (Android blocks background reads),
     * sends it and finishes.
     */
    fun intent(context: Context): Intent = Intent().setClassName(context.packageName, ACTIVITY_CLASS)
}
