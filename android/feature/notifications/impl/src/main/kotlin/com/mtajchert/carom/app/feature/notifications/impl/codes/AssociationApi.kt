package com.mtajchert.carom.app.feature.notifications.impl.codes

import android.content.IntentSender

/** Which kind of Companion Device Manager association to ask for. Chosen on a device (see AndroidAssociationApi). */
enum class AssociationProfile {
    /** A self-managed association: no device discovery, a consent dialog only. */
    SELF_MANAGED,

    /** A plain request with no profile. Discovery-based, so it usually fails without a device filter. */
    NONE,
}

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
    suspend fun associate(profile: AssociationProfile): AssociationOutcome

    /** True when this app already has an association. */
    fun hasAssociation(): Boolean
}
