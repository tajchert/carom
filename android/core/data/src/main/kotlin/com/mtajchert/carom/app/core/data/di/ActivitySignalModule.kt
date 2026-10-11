package com.mtajchert.carom.app.core.data.di

import com.mtajchert.carom.app.core.data.runtime.ActivitySignal
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
interface ActivitySignalModule {
    @BindsOptionalOf
    fun optionalActivitySignal(): ActivitySignal
}
