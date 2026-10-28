package com.mtajchert.carom.app.feature.notifications.impl.codes

import android.content.IntentSender

sealed interface AssociationOutcome {
    /** The association exists. */
    data object Created : AssociationOutcome

    /** Android needs the user to confirm in its own dialog; start [sender] and report the result. */
    data class NeedsConsent(val sender: IntentSender) : AssociationOutcome

    /** [reason] is a short code (never user content). */
    data class Failed(val reason: String) : AssociationOutcome
}

/** The Companion Device Manager as this feature needs it. A seam so the flow is tested without the framework. */
interface AssociationApi {
    /** Asks for a watch-profile association with the Mac's short-lived Carom beacon. */
    suspend fun associate(): AssociationOutcome

    /** True when this app already has an association. */
    fun hasAssociation(): Boolean
}
