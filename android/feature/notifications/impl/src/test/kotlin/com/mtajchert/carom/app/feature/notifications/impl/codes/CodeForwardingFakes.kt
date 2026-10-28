package com.mtajchert.carom.app.feature.notifications.impl.codes

import android.content.Context
import android.content.Intent
import com.mtajchert.carom.app.feature.notifications.api.CodeForwardingAccess
import com.mtajchert.carom.app.feature.notifications.api.CodeForwardingState

class FakeCodeForwardingAccess : CodeForwardingAccess {
    var current = CodeForwardingState.NOT_ENABLED

    /** Answers handed out first, one per call, before [current]: models Android flipping the switch late. */
    val queued = ArrayDeque<CodeForwardingState>()

    override fun state(): CodeForwardingState = queued.removeFirstOrNull() ?: current

    override fun enableIntent(context: Context): Intent = Intent()

    override fun adbCommand(): String = AdbFallback.command("com.mtajchert.carom.app")
}

class FakeAssociationApi : AssociationApi {
    var outcome: AssociationOutcome = AssociationOutcome.Created
    var associated = false
    var calls = 0

    override suspend fun associate(): AssociationOutcome {
        calls++
        return outcome
    }

    override fun hasAssociation(): Boolean = associated
}

class FakeRedactionPromptStore : RedactionPromptStore {
    override var lastShownAt: Long? = null
    override var muted = false
}

class FakeListenerReconnector : ListenerReconnector {
    var connected = true
    var calls = 0

    override fun reconnect(): Boolean {
        calls++
        return connected
    }
}
