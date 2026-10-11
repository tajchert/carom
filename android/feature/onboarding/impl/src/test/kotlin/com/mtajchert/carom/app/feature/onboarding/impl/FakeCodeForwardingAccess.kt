package com.mtajchert.carom.app.feature.onboarding.impl

import android.content.Context
import android.content.Intent
import com.mtajchert.carom.app.feature.notifications.api.CodeForwardingAccess
import com.mtajchert.carom.app.feature.notifications.api.CodeForwardingState

class FakeCodeForwardingAccess : CodeForwardingAccess {
    var current = CodeForwardingState.UNSUPPORTED

    override fun state() = current

    override fun enableIntent(context: Context): Intent = Intent("enable")

    override fun adbCommand(): String = "adb"
}
