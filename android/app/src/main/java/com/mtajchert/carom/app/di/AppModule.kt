package com.mtajchert.carom.app.di

import com.mtajchert.carom.app.BuildConfig
import com.mtajchert.carom.app.core.data.sync.AndroidDeviceProfileProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /** Written into this device's record as `app_version`. */
    @Provides
    @Named(AndroidDeviceProfileProvider.APP_VERSION)
    fun providesAppVersion(): String = BuildConfig.VERSION_NAME
}
