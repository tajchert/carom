package com.mtajchert.carom.app.feature.notifications.impl.di

import com.mtajchert.carom.app.core.data.sync.IncomingMessageHandler
import com.mtajchert.carom.app.core.data.sync.WipeHook
import com.mtajchert.carom.app.feature.notifications.impl.ClearForwardedKeysOnWipe
import com.mtajchert.carom.app.feature.notifications.impl.ForwardedKeys
import com.mtajchert.carom.app.feature.notifications.impl.NotificationAppsRepository
import com.mtajchert.carom.app.feature.notifications.impl.NotificationDismissHandler
import com.mtajchert.carom.app.feature.notifications.impl.RedactionDetector
import com.mtajchert.carom.app.feature.notifications.impl.RoomForwardedKeys
import com.mtajchert.carom.app.feature.notifications.impl.RoomNotificationAppsRepository
import com.mtajchert.carom.app.feature.notifications.impl.apps.AndroidNotificationAccessChecker
import com.mtajchert.carom.app.feature.notifications.impl.apps.NotificationAccessChecker
import com.mtajchert.carom.app.feature.notifications.impl.actions.AndroidPendingIntentSender
import com.mtajchert.carom.app.feature.notifications.impl.actions.NotificationActionHandler
import com.mtajchert.carom.app.feature.notifications.impl.actions.PendingIntentSender
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class NotificationsModule {

    @Binds
    abstract fun bindsNotificationAppsRepository(impl: RoomNotificationAppsRepository): NotificationAppsRepository

    @Binds
    abstract fun bindsNotificationAccessChecker(impl: AndroidNotificationAccessChecker): NotificationAccessChecker

    @Binds
    abstract fun bindsForwardedKeys(impl: RoomForwardedKeys): ForwardedKeys

    @Binds
    @IntoSet
    abstract fun bindsClearForwardedKeysOnWipe(impl: ClearForwardedKeysOnWipe): WipeHook

    /** Adds `notification.dismiss@1` to this device's caps and known types. */
    @Binds
    @IntoSet
    abstract fun bindsDismissHandler(impl: NotificationDismissHandler): IncomingMessageHandler

    /** Adds `notification.action@1` to this device's handlers; the setting decides whether it is advertised. */
    @Binds
    @IntoSet
    abstract fun bindsActionHandler(impl: NotificationActionHandler): IncomingMessageHandler

    @Binds
    abstract fun bindsPendingIntentSender(impl: AndroidPendingIntentSender): PendingIntentSender

    companion object {
        @Provides
        @Singleton
        fun providesRedactionDetector(): RedactionDetector = RedactionDetector(RedactionDetector.systemPlaceholders())
    }
}
