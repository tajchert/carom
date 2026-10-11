package com.mtajchert.carom.app.core.data.runtime

/**
 * Tells the action window (SPEC §9, docs/CLIENTS.md) that the phone is awake for a reason.
 * M3 adds the window itself to this file. Until it is bound, the optional is empty and
 * callers do nothing.
 */
interface ActivitySignal {
    /** A notification was forwarded; [online] are the recipients with a live socket. */
    fun onForwarded(online: Set<String>)

    /** A media change or a received action: restart the idle timer if the window is open. */
    fun onActivity()
}
