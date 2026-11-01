package com.mtajchert.carom.app.feature.onboarding.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A `carom://` link that opened the app, waiting for onboarding. The app binds one instance as a
 * singleton; the activity [offer]s a link and the onboarding ViewModel [consume]s it once. The link
 * carries a secret: never log it.
 */
class IncomingLink {
    private val pending = MutableStateFlow<String?>(null)
    val link: StateFlow<String?> = pending.asStateFlow()

    fun offer(uri: String) {
        pending.value = uri
    }

    /** Clears the link so it is handled once. */
    fun consume() {
        pending.value = null
    }

    override fun toString(): String = "IncomingLink"
}
