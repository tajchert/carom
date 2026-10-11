package com.mtajchert.carom.app.feature.notifications.impl.actions

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * The seam over `PendingIntent.send`, so tests see what would have been fired. Throws what the
 * platform throws: [PendingIntent.CanceledException] when the app canceled it, [SecurityException]
 * when the start is refused.
 */
fun interface PendingIntentSender {
    fun send(pendingIntent: PendingIntent, fillIn: Intent?, options: Bundle?)
}

/** `PendingIntent.send`, never an `IntentSender` (spec §5.3). */
class AndroidPendingIntentSender @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : PendingIntentSender {
    override fun send(pendingIntent: PendingIntent, fillIn: Intent?, options: Bundle?) {
        pendingIntent.send(context, 0, fillIn, null, null, null, options)
    }
}
