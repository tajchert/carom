package com.mtajchert.carom.app.feature.onboarding.impl

import android.content.Context
import android.os.Build
import android.provider.Settings
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/** The name suggested on "Name this device". */
fun interface DeviceNameProvider {
    fun defaultName(): String
}

/** The system device name (Settings → About phone), or the model when it is not set. */
class SystemDeviceNameProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : DeviceNameProvider {
    override fun defaultName(): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.trim()?.takeIf { it.isNotEmpty() }
            ?: Build.MODEL?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Android phone"
}

@Module
@InstallIn(SingletonComponent::class)
interface OnboardingModule {
    @Binds
    fun bindsDeviceNameProvider(impl: SystemDeviceNameProvider): DeviceNameProvider
}
