package com.mtajchert.carom.app.feature.notifications.impl.codes

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import com.mtajchert.carom.app.feature.notifications.api.CodeForwardingAccess
import com.mtajchert.carom.app.feature.notifications.api.CodeForwardingState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Whether the system lets this app receive sensitive notification content. Null: could not tell. */
fun interface SensitiveAccessChecker {
    fun isAllowed(): Boolean?
}

/**
 * Reads the `RECEIVE_SENSITIVE_NOTIFICATIONS` app op, the switch the adb fallback flips and a
 * Companion Device Manager association should flip. The op has no public constant, so it is read
 * by name; an unknown name means "cannot tell".
 */
class AppOpsSensitiveAccessChecker @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : SensitiveAccessChecker {
    override fun isAllowed(): Boolean? = try {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return null
        ops.unsafeCheckOpNoThrow(OP, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private companion object {
        const val OP = "android:receive_sensitive_notifications"
    }
}

/**
 * Enabled means the app op is allowed *and* the watch association exists. An op set with adb alone
 * is reset at boot, so it counts as not enabled and the screen offers pairing with the Mac.
 */
class AndroidCodeForwardingAccess @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val associations: AssociationApi,
    private val checker: SensitiveAccessChecker,
) : CodeForwardingAccess {
    override fun state(): CodeForwardingState = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM -> CodeForwardingState.UNSUPPORTED
        checker.isAllowed() == true && associations.hasAssociation() -> CodeForwardingState.ENABLED
        else -> CodeForwardingState.NOT_ENABLED
    }

    override fun enableIntent(context: Context): Intent =
        Intent().setClassName(context.packageName, ACTIVITY_CLASS)

    override fun adbCommand(): String = AdbFallback.command(context.packageName)

    companion object {
        /** By name so this file compiles before the activity exists; a test pins it to the class. */
        const val ACTIVITY_CLASS = "com.mtajchert.carom.app.feature.notifications.impl.codes.CodeForwardingActivity"
    }
}
