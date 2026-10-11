package com.mtajchert.carom.app.feature.notifications.impl.actions

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle

/** Records what would have been fired instead of starting anything. */
internal class RecordingSender : PendingIntentSender {
    class Sent(val intent: PendingIntent, val fillIn: Intent?, val options: Bundle?)

    val sent = mutableListOf<Sent>()

    /** When set, [send] throws it, like a canceled `PendingIntent` or a refused start. */
    var failure: Exception? = null

    override fun send(pendingIntent: PendingIntent, fillIn: Intent?, options: Bundle?) {
        failure?.let { throw it }
        sent += Sent(pendingIntent, fillIn, options)
    }
}
