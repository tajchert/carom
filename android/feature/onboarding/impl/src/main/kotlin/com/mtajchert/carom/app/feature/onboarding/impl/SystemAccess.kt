package com.mtajchert.carom.app.feature.onboarding.impl

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mtajchert.carom.app.feature.notifications.api.CodeForwardingState

/** What the permissions step shows. [postNotifications] is null below Android 13 (nothing to ask). */
data class PermissionStatus(
    val notificationAccess: Boolean,
    val postNotifications: Boolean?,
    val batteryUnrestricted: Boolean,
    /** Android 13+ blocks notification access for sideloaded apps until "Allow restricted settings". */
    val showRestrictedSettingsHint: Boolean,
    /** UNSUPPORTED below Android 15: the card is hidden. Filled in by the ViewModel, not by [SystemAccess]. */
    val codeForwarding: CodeForwardingState = CodeForwardingState.UNSUPPORTED,
)

/** System checks and settings deep links for onboarding. Nothing here needs a new permission. */
internal object SystemAccess {
    /**
     * The notification listener, named by string so onboarding does not depend on the
     * notifications feature. Its package is the namespace, not the application id.
     */
    const val LISTENER_CLASS = "com.mtajchert.carom.app.feature.notifications.impl.CaromListenerService"

    fun status(context: Context): PermissionStatus {
        val access = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        val post = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            null
        }
        val battery = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
        return PermissionStatus(
            notificationAccess = access,
            postNotifications = post,
            batteryUnrestricted = battery,
            showRestrictedSettingsHint = !access && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
        )
    }

    fun hasCamera(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** The listener's own switch (Android 11+), falling back to the list of listeners. */
    fun openNotificationAccess(context: Context) {
        val list = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context.packageName, LISTENER_CLASS).flattenToString(),
            )
            if (start(context, detail)) return
        }
        start(context, list)
    }

    /** App info, where Android 13+ hides "Allow restricted settings" behind the ⋮ menu. */
    fun openAppInfo(context: Context) {
        start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }

    /**
     * The battery optimisation list. The direct exemption request needs an extra permission and
     * is restricted by store policy, so the user picks Carom in the list instead.
     */
    fun openBatterySettings(context: Context) {
        if (!start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) openAppInfo(context)
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
