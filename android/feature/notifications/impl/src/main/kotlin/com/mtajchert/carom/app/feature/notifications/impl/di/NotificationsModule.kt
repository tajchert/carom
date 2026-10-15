package com.mtajchert.carom.app.feature.notifications.impl.di

import com.mtajchert.carom.app.core.data.runtime.SyncStartHook
import com.mtajchert.carom.app.core.data.sync.IncomingMessageHandler
import com.mtajchert.carom.app.core.data.sync.WipeHook
import com.mtajchert.carom.app.feature.notifications.impl.BlobUploader
import com.mtajchert.carom.app.feature.notifications.api.CodeForwardingAccess
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
import com.mtajchert.carom.app.feature.notifications.impl.codes.AndroidAssociationApi
import com.mtajchert.carom.app.feature.notifications.impl.codes.AndroidCodeForwardingAccess
import com.mtajchert.carom.app.feature.notifications.impl.codes.AppOpsSensitiveAccessChecker
import com.mtajchert.carom.app.feature.notifications.impl.codes.AndroidRedactionPromptNotifier
import com.mtajchert.carom.app.feature.notifications.impl.codes.AssociationApi
import com.mtajchert.carom.app.feature.notifications.impl.codes.PrefsRedactionPromptStore
import com.mtajchert.carom.app.feature.notifications.impl.codes.RedactionPromptNotifier
import com.mtajchert.carom.app.feature.notifications.impl.codes.RedactionPromptStore
import com.mtajchert.carom.app.feature.notifications.impl.codes.SensitiveAccessChecker
import com.mtajchert.carom.app.feature.notifications.impl.media.AndroidMediaSessionSource
import com.mtajchert.carom.app.feature.notifications.impl.media.MediaArtUploader
import com.mtajchert.carom.app.feature.notifications.impl.media.MediaCommandHandler
import com.mtajchert.carom.app.feature.notifications.impl.media.MediaSessionSource
import com.mtajchert.carom.app.feature.notifications.impl.media.SyncMediaArtUploader
import com.mtajchert.carom.app.feature.notifications.impl.actions.ActionsServiceController
import com.mtajchert.carom.app.feature.notifications.impl.actions.ActionsServiceLauncher
import com.mtajchert.carom.app.feature.notifications.impl.actions.AndroidActionsServiceLauncher
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

    /** Adds `media.command@1` to this device's caps and known types. */
    @Binds
    @IntoSet
    abstract fun bindsMediaCommandHandler(impl: MediaCommandHandler): IncomingMessageHandler

    @Binds
    abstract fun bindsMediaSessionSource(impl: AndroidMediaSessionSource): MediaSessionSource

    @Binds
    abstract fun bindsMediaArtUploader(impl: SyncMediaArtUploader): MediaArtUploader

    @Binds
    abstract fun bindsAssociationApi(impl: AndroidAssociationApi): AssociationApi

    @Binds
    abstract fun bindsSensitiveAccessChecker(impl: AppOpsSensitiveAccessChecker): SensitiveAccessChecker

    /** Used by onboarding, which depends on `:feature:notifications:api` only. */
    @Binds
    abstract fun bindsCodeForwardingAccess(impl: AndroidCodeForwardingAccess): CodeForwardingAccess

    @Binds
    abstract fun bindsRedactionPromptNotifier(impl: AndroidRedactionPromptNotifier): RedactionPromptNotifier

    @Binds
    abstract fun bindsRedactionPromptStore(impl: PrefsRedactionPromptStore): RedactionPromptStore

    /** Adds `notification.action@1` to this device's handlers; the setting decides whether it is advertised. */
    @Binds
    @IntoSet
    abstract fun bindsActionHandler(impl: NotificationActionHandler): IncomingMessageHandler

    @Binds
    abstract fun bindsPendingIntentSender(impl: AndroidPendingIntentSender): PendingIntentSender

    @Binds
    abstract fun bindsActionsServiceLauncher(impl: AndroidActionsServiceLauncher): ActionsServiceLauncher

    /** Keeps the Always-mode foreground service in step with the setting. */
    @Binds
    @IntoSet
    abstract fun bindsActionsServiceController(impl: ActionsServiceController): SyncStartHook

    companion object {
        @Provides
        @Singleton
        fun providesRedactionDetector(): RedactionDetector = RedactionDetector(RedactionDetector.systemPlaceholders())
    }
}
