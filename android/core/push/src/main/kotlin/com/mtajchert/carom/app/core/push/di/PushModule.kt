package com.mtajchert.carom.app.core.push.di

import com.mtajchert.carom.app.core.data.runtime.PushStatusRepository
import com.mtajchert.carom.app.core.data.runtime.SyncStartHook
import com.mtajchert.carom.app.core.data.sync.WipeHook
import com.mtajchert.carom.app.core.push.DataStorePushEndpointStore
import com.mtajchert.carom.app.core.push.PushConnector
import com.mtajchert.carom.app.core.push.PushEndpointStore
import com.mtajchert.carom.app.core.push.PushManager
import com.mtajchert.carom.app.core.push.PushStartHook
import com.mtajchert.carom.app.core.push.UnifiedPushConnector
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
internal abstract class PushModule {
    @Binds
    abstract fun bindsPushConnector(impl: UnifiedPushConnector): PushConnector

    @Binds
    abstract fun bindsPushEndpointStore(impl: DataStorePushEndpointStore): PushEndpointStore

    @Binds
    abstract fun bindsPushStatusRepository(impl: PushManager): PushStatusRepository

    /** Deletes the subscription on the Hub and unregisters before the keys are wiped. */
    @Binds @IntoSet
    abstract fun bindsPushWipeHook(impl: PushManager): WipeHook

    @Binds @IntoSet
    abstract fun bindsPushStartHook(impl: PushStartHook): SyncStartHook
}
