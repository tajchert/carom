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
import com.mtajchert.carom.app.feature.notifications.impl.media.AndroidMediaSessionSource
import com.mtajchert.carom.app.feature.notifications.impl.media.MediaArtUploader
import com.mtajchert.carom.app.feature.notifications.impl.media.MediaCommandHandler
import com.mtajchert.carom.app.feature.notifications.impl.media.MediaSessionSource
import com.mtajchert.carom.app.feature.notifications.impl.media.SyncMediaArtUploader
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

    /** Adds `media.command@1` to this device's caps and known types. */
    @Binds
    @IntoSet
    abstract fun bindsMediaCommandHandler(impl: MediaCommandHandler): IncomingMessageHandler

    @Binds
    abstract fun bindsMediaSessionSource(impl: AndroidMediaSessionSource): MediaSessionSource

    @Binds
    abstract fun bindsMediaArtUploader(impl: SyncMediaArtUploader): MediaArtUploader

    companion object {
        @Provides
        @Singleton
        fun providesRedactionDetector(): RedactionDetector = RedactionDetector(RedactionDetector.systemPlaceholders())
    }
}
