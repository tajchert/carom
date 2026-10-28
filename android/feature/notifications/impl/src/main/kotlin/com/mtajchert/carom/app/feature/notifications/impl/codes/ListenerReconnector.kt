package com.mtajchert.carom.app.feature.notifications.impl.codes

/**
 * Rebinds Carom's notification listener. Android decides whether a listener sees codes only when
 * it binds (or notification access is toggled), so a watch association created while the listener
 * is connected changes nothing until it reconnects. Returns false when no listener is connected;
 * the next bind picks the association up on its own.
 */
fun interface ListenerReconnector {
    fun reconnect(): Boolean
}
