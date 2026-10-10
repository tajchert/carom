package com.mtajchert.carom.app.feature.clipboard.impl

import com.mtajchert.carom.app.core.data.sync.IncomingMessageHandler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Contributes the `clipboard.set@1` handler (and so the capability) to the sync engine. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ClipboardModule {
    @Binds @IntoSet
    internal abstract fun bindsClipboardHandler(impl: ClipboardIncomingHandler): IncomingMessageHandler

    @Binds
    internal abstract fun bindsArrivalNotifier(impl: AndroidClipboardArrivalNotifier): ClipboardArrivalNotifier

    @Binds
    internal abstract fun bindsSendTargets(impl: DataStoreClipboardSendTargets): ClipboardSendTargets
}
