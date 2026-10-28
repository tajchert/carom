package com.mtajchert.carom.app.feature.notifications.impl.codes

import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.os.ParcelUuid
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * [AssociationApi] over `CompanionDeviceManager`. Code forwarding only exists on Android 15+, so
 * the Android 13 (`TIRAMISU`) callbacks are enough and older versions report `Failed("unsupported")`.
 *
 * Android only shows codes to listeners that hold `RECEIVE_SENSITIVE_NOTIFICATIONS`, and the only
 * role a third-party app can get it from is a companion watch (or glasses). So the request uses the
 * watch profile and matches the Mac's Carom beacon, which it advertises for a few minutes from
 * Settings › Notifications. The system grants the role when the association is created and keeps it
 * across reboots while the association exists. Nothing connects to the Mac and presence is never
 * observed, so once the beacon stops there is no link between the two.
 * [CodeForwardingViewModel] still re-checks the app op and falls back to the adb command.
 */
class AndroidAssociationApi @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AssociationApi {

    override suspend fun associate(): AssociationOutcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return AssociationOutcome.Failed("unsupported")
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return AssociationOutcome.Failed("unavailable")
        return start(manager)
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
    private suspend fun start(manager: CompanionDeviceManager): AssociationOutcome =
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
                manager.associate(request(), Executor { it.run() }, callback)
            } catch (e: RuntimeException) {
                // SecurityException when the permission is missing, IllegalStateException for bad requests.
                finish(AssociationOutcome.Failed(e.javaClass.simpleName))
            }
        }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun request(): AssociationRequest {
        val beacon = ScanFilter.Builder().setServiceUuid(ParcelUuid(BEACON_SERVICE)).build()
        return AssociationRequest.Builder()
            .setDeviceProfile(AssociationRequest.DEVICE_PROFILE_WATCH)
            .addDeviceFilter(BluetoothLeDeviceFilter.Builder().setScanFilter(beacon).build())
            .setSingleDevice(true)
            .build()
    }

    companion object {
        /** Advertised by the Mac app's `CompanionBeacon`; keep the two in sync. */
        val BEACON_SERVICE: UUID = UUID.fromString("a1c39594-89e2-4224-94e2-9e0186526adf")
    }
}
