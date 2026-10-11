package com.mtajchert.carom.app.feature.notifications.impl.di

import com.mtajchert.carom.app.core.data.sync.IncomingMessageHandler
import com.mtajchert.carom.app.core.data.sync.WipeHook
import com.mtajchert.carom.app.feature.notifications.impl.BlobUploader
import com.mtajchert.carom.app.feature.notifications.impl.ClearForwardedKeysOnWipe
import com.mtajchert.carom.app.feature.notifications.impl.ClearIconCacheOnWipe
import com.mtajchert.carom.app.feature.notifications.impl.DefaultBlobUploader
import com.mtajchert.carom.app.feature.notifications.impl.ForwardedKeys
import com.mtajchert.carom.app.feature.notifications.impl.GroupKeySource
import com.mtajchert.carom.app.feature.notifications.impl.IconCache
import com.mtajchert.carom.app.feature.notifications.impl.ImageEncoder
import com.mtajchert.carom.app.feature.notifications.impl.ImageLoader
import com.mtajchert.carom.app.feature.notifications.impl.NotificationAppsRepository
import com.mtajchert.carom.app.feature.notifications.impl.NotificationDismissHandler
import com.mtajchert.carom.app.feature.notifications.impl.RedactionDetector
import com.mtajchert.carom.app.feature.notifications.impl.RoomForwardedKeys
import com.mtajchert.carom.app.feature.notifications.impl.RoomIconCache
import com.mtajchert.carom.app.feature.notifications.impl.RoomNotificationAppsRepository
import com.mtajchert.carom.app.feature.notifications.impl.VaultGroupKeySource
import com.mtajchert.carom.app.feature.notifications.impl.apps.AndroidNotificationAccessChecker
import com.mtajchert.carom.app.feature.notifications.impl.apps.NotificationAccessChecker
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

    @Binds
    abstract fun bindsImageLoader(impl: ImageEncoder): ImageLoader

    @Binds
    abstract fun bindsBlobUploader(impl: DefaultBlobUploader): BlobUploader

    @Binds
    abstract fun bindsIconCache(impl: RoomIconCache): IconCache

    @Binds
    abstract fun bindsGroupKeySource(impl: VaultGroupKeySource): GroupKeySource

    /** Forgets uploaded icon ids when this device leaves its group. */
    @Binds
    @IntoSet
    abstract fun bindsClearIconCacheOnWipe(impl: ClearIconCacheOnWipe): WipeHook

    companion object {
        @Provides
        @Singleton
        fun providesRedactionDetector(): RedactionDetector = RedactionDetector(RedactionDetector.systemPlaceholders())
    }
}
