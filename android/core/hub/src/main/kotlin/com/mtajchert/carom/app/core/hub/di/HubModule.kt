package com.mtajchert.carom.app.core.hub.di

import com.mtajchert.carom.app.core.hub.HubClientFactory
import com.mtajchert.carom.app.core.hub.HubSocket
import com.mtajchert.carom.app.core.hub.OkHttpHubClientFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class HubModule {

    @Binds
    @Singleton
    internal abstract fun bindsHubClientFactory(impl: OkHttpHubClientFactory): HubClientFactory

    companion object {
        /**
         * One client for the whole app. No logging interceptor: request bodies carry sealed
         * content and headers carry the device token, and neither may be logged.
         */
        @Provides
        @Singleton
        fun providesOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .pingInterval(HubSocket.PING_INTERVAL_S, TimeUnit.SECONDS)
            .build()
    }
}
