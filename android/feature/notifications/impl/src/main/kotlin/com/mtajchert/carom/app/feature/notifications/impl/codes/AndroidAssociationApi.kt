package com.mtajchert.carom.app.feature.notifications.impl.codes

import android.annotation.SuppressLint
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Build
import androidx.annotation.RequiresApi
import com.mtajchert.carom.app.feature.notifications.impl.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * [AssociationApi] over `CompanionDeviceManager`. Code forwarding only exists on Android 15+, so
 * the Android 13 (`TIRAMISU`) callbacks are enough and older versions report `Failed("unsupported")`.
 *
 * DEVICE VERIFICATION (spec §5.2, Task 8 checklist): whether a self-managed association makes the
 * system stop redacting codes for the listener is unconfirmed. [CodeForwardingViewModel] re-checks
 * the app op afterwards and falls back to the adb command, so a wrong guess costs one screen.
 */
class AndroidAssociationApi @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AssociationApi {

    override suspend fun associate(profile: AssociationProfile): AssociationOutcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return AssociationOutcome.Failed("unsupported")
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return AssociationOutcome.Failed("unavailable")
        return start(manager, profile)
    }

    override fun hasAssociation(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return try {
            context.getSystemService(CompanionDeviceManager::class.java)?.myAssociations?.isNotEmpty() == true
        } catch (_: RuntimeException) {
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun start(manager: CompanionDeviceManager, profile: AssociationProfile): AssociationOutcome =
        suspendCancellableCoroutine { cont ->
            fun finish(outcome: AssociationOutcome) {
                if (cont.isActive) cont.resume(outcome)
            }
            val callback = object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) = finish(AssociationOutcome.NeedsConsent(intentSender))
                override fun onAssociationCreated(associationInfo: AssociationInfo) = finish(AssociationOutcome.Created)
                override fun onFailure(error: CharSequence?) = finish(AssociationOutcome.Failed("refused"))
            }
            try {
                manager.associate(request(profile), Executor { it.run() }, callback)
            } catch (e: RuntimeException) {
                // SecurityException when the permission is missing, IllegalStateException for bad requests.
                finish(AssociationOutcome.Failed(e.javaClass.simpleName))
            }
        }

    // REQUEST_COMPANION_SELF_MANAGED is not declared: the platform does not grant it to
    // third-party apps (lint ProtectedPermissions). Without it associate() throws
    // SecurityException, start() maps that to Failed, and the flow falls back to the adb screen.
    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun request(profile: AssociationProfile): AssociationRequest {
        val builder = AssociationRequest.Builder()
        if (profile == AssociationProfile.SELF_MANAGED) {
            builder.setSelfManaged(true)
            builder.setDisplayName(context.getString(R.string.feature_notifications_codes_association_name))
        }
        return builder.build()
    }
}
