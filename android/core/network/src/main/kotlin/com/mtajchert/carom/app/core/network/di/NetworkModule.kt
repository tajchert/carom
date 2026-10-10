package com.mtajchert.carom.app.core.network.di

import com.mtajchert.carom.app.core.network.TaskNetworkDataSource
import com.mtajchert.carom.app.core.network.fake.FakeTaskNetworkDataSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Runnable demo source. Replace this binding when adding a product backend. */
@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkDataSourceModule {
    @Binds
    @Singleton
    abstract fun bindsTaskNetworkDataSource(impl: FakeTaskNetworkDataSource): TaskNetworkDataSource
}
