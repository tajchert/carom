package com.mtajchert.carom.app.core.data.sync

import com.mtajchert.carom.app.core.model.ActionsFromMac
import com.mtajchert.carom.app.core.protocol.MessageTypes

/** The capabilities the "Actions from Mac" setting controls (spec §3.6). */
object ActionCaps {
    val TYPES: Set<String> = setOf(MessageTypes.NOTIFICATION_ACTION, MessageTypes.MEDIA_COMMAND)

    /** The caps to leave out of the published record for [mode]. */
    fun withheld(mode: ActionsFromMac): Set<String> = if (mode == ActionsFromMac.OFF) TYPES else emptySet()
}
