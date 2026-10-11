package com.mtajchert.carom.app.feature.clipboard.impl

import com.mtajchert.carom.app.core.data.runtime.SyncStartHook
import com.mtajchert.carom.app.core.data.sync.IncomingMessageHandler
import com.mtajchert.carom.app.core.data.sync.WipeHook
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

    @Binds
    internal abstract fun bindsOngoingNotifier(impl: AndroidOngoingNotifier): OngoingNotifier

    /** Removes the "Clipboard from …" notice when this phone leaves its Hub. */
    @Binds @IntoSet
    internal abstract fun bindsClearArrivalOnWipe(impl: ClearClipboardNoticeOnWipe): WipeHook

    /** Keeps the optional ongoing notification in step with its setting and the pairing. */
    @Binds @IntoSet
    internal abstract fun bindsOngoingNotification(impl: OngoingNotificationController): SyncStartHook

    @Binds
    internal abstract fun bindsClockSkewNotifier(impl: AndroidClockSkewNotifier): ClockSkewNotifier

    /** One notification per clock-drift episode. */
    @Binds @IntoSet
    internal abstract fun bindsClockSkewNotification(impl: ClockSkewNotificationController): SyncStartHook
}
