package com.mtajchert.carom.app.feature.clipboard.impl

import android.content.Context
import com.mtajchert.carom.app.core.data.repository.UserPreferencesRepository
import com.mtajchert.carom.app.core.data.sync.DirectoryStore
import com.mtajchert.carom.app.core.data.sync.IncomingMessageHandler
import com.mtajchert.carom.app.core.protocol.MessageBodies
import com.mtajchert.carom.app.core.protocol.MessageTypes
import com.mtajchert.carom.app.core.protocol.Received
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * `clipboard.set@1` from another device: writes the primary clip (flagged sensitive when asked),
 * remembers the text's hash so it is not sent back, and optionally posts a quiet notification.
 * The engine already ran the SPEC §3.3 checks, including the stale-clipboard rule.
 */
class ClipboardIncomingHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val echo: EchoGuard,
    private val preferences: UserPreferencesRepository,
    private val directory: DirectoryStore,
    private val notifier: ClipboardArrivalNotifier,
) : IncomingMessageHandler {
    override val type: String = MessageTypes.CLIPBOARD_SET

    override suspend fun handle(message: Received) {
        val body = MessageBodies.fromJson(MessageBodies.ClipboardSet.serializer(), message.payload.body) ?: return
        // Remember first: the hash must be in place before anything can react to the new clip.
        echo.remember(body.text)
        ClipboardAccess.write(context, body.text, body.sensitive)
        if (preferences.userPreferences.first().clipboardArrivalNotification) {
            val sender = directory.all().firstOrNull { it.id == message.envelope.from }?.record?.name
                ?: context.getString(R.string.feature_clipboard_unknown_device)
            notifier.show(sender, body.text, body.sensitive)
        }
    }
}
