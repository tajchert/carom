package com.mtajchert.carom.app.feature.clipboard.impl

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/** Text read from the clipboard, with the sensitive marker some apps (password managers) set. */
class ClipboardText(val text: String, val sensitive: Boolean) {
    override fun toString() = "ClipboardText(length=${text.length}, sensitive=$sensitive)" // never log content
}

/** Reads and writes the primary clip. Reading only works while this app has window focus (Android 10+). */
object ClipboardAccess {
    private const val CLIP_LABEL = "Carom"
    private const val IS_SENSITIVE_KEY = "android.content.extra.IS_SENSITIVE"

    /** `ClipDescription.EXTRA_IS_SENSITIVE` is API 33; older versions use the same string key. */
    val sensitiveKey: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ClipDescription.EXTRA_IS_SENSITIVE else IS_SENSITIVE_KEY

    fun manager(context: Context): ClipboardManager = context.getSystemService(ClipboardManager::class.java)

    fun read(context: Context): ClipboardText? {
        val clip = manager(context).primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val text = clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
        if (text.isEmpty()) return null
        val sensitive = clip.description.extras?.getBoolean(sensitiveKey, false) == true
        return ClipboardText(text, sensitive)
    }

    fun write(context: Context, text: String, sensitive: Boolean) {
        val clip = ClipData.newPlainText(CLIP_LABEL, text)
        if (sensitive) {
            clip.description.extras = PersistableBundle().apply { putBoolean(sensitiveKey, true) }
        }
        manager(context).setPrimaryClip(clip)
    }
}
