package com.mtajchert.carom.app.feature.clipboard.impl

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import com.mtajchert.carom.app.feature.clipboard.api.ClipboardSend
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@StringRes
internal fun ClipboardSendStatus.messageRes(): Int = when (this) {
    ClipboardSendStatus.SENT -> R.string.feature_clipboard_sent
    ClipboardSendStatus.QUEUED -> R.string.feature_clipboard_queued
    ClipboardSendStatus.TOO_LARGE -> R.string.feature_clipboard_too_large
    ClipboardSendStatus.EMPTY -> R.string.feature_clipboard_empty
    ClipboardSendStatus.NO_RECIPIENTS -> R.string.feature_clipboard_no_recipients
    ClipboardSendStatus.FAILED -> R.string.feature_clipboard_failed
    ClipboardSendStatus.NOT_PAIRED -> R.string.feature_clipboard_not_paired
    ClipboardSendStatus.SKIPPED_ECHO -> R.string.feature_clipboard_echo
}

/**
 * Transparent activity behind the tile, the in-app button and the ongoing notification action.
 * Android blocks background clipboard reads, so it reads once it has window focus, sends,
 * shows a short confirmation and finishes. Not exported: only this app can start it.
 */
@AndroidEntryPoint
class ClipboardSendActivity : ComponentActivity() {
    @Inject lateinit var sender: ClipboardSender

    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handled = savedInstanceState?.getBoolean(STATE_HANDLED) == true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_HANDLED, handled)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true
        val clip = ClipboardAccess.read(this)
        if (clip == null) {
            finishWith(ClipboardSendStatus.EMPTY)
            return
        }
        lifecycleScope.launch { finishWith(sender.send(clip.text, clip.sensitive)) }
    }

    private fun finishWith(status: ClipboardSendStatus) {
        Toast.makeText(applicationContext, status.messageRes(), Toast.LENGTH_SHORT).show()
        finish()
    }

    private companion object {
        const val STATE_HANDLED = "handled"
    }
}

/**
 * Share-sheet target "Send to Carom" for plain text. Exported on purpose: the system share sheet
 * starts it. It only accepts `text/plain` and sends the text through the same path as the tile.
 */
@AndroidEntryPoint
class ShareTargetActivity : ComponentActivity() {
    @Inject lateinit var sender: ClipboardSender

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = if (intent?.action == Intent.ACTION_SEND) intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() else null
        if (text.isNullOrEmpty()) {
            finishWith(ClipboardSendStatus.EMPTY)
            return
        }
        // The shared text is not known to be sensitive; password managers use the clipboard path.
        lifecycleScope.launch { finishWith(sender.send(text, sensitive = false)) }
    }

    private fun finishWith(status: ClipboardSendStatus) {
        Toast.makeText(applicationContext, status.messageRes(), Toast.LENGTH_SHORT).show()
        finish()
    }
}

/** Quick Settings tile "Send clipboard": opens the transparent activity and collapses the shade. */
class ClipboardTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = ClipboardSend.intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") // only reached below API 34
            startActivityAndCollapse(intent)
        }
    }
}
