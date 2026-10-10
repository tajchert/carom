package com.mtajchert.carom.app.feature.notifications.impl

/** In-memory [ForwardedKeys]; share one instance between forwarders to model a listener reconnect. */
class FakeForwardedKeys : ForwardedKeys {
    val keys = linkedSetOf<String>()

    override suspend fun add(key: String, now: Long) {
        keys += key
    }

    override suspend fun remove(key: String): Boolean = keys.remove(key)

    override suspend fun all(): Set<String> = keys.toSet()

    override suspend fun clear() = keys.clear()
}
