package com.mtajchert.carom.app.core.data.di

import android.content.Context
import androidx.work.WorkManager
import com.mtajchert.carom.app.core.data.repository.DefaultDeviceRepository
import com.mtajchert.carom.app.core.data.repository.DefaultRecentItemsRepository
import com.mtajchert.carom.app.core.data.repository.DefaultSessionRepository
import com.mtajchert.carom.app.core.data.repository.DefaultUserPreferencesRepository
import com.mtajchert.carom.app.core.data.repository.DeviceRepository
import com.mtajchert.carom.app.core.data.repository.RecentItemsRepository
import com.mtajchert.carom.app.core.data.repository.SessionRepository
import com.mtajchert.carom.app.core.data.repository.UserPreferencesRepository
import com.mtajchert.carom.app.core.data.runtime.ActionWindow
import com.mtajchert.carom.app.core.data.runtime.ActionWindowHold
import com.mtajchert.carom.app.core.data.runtime.ActionWindowSettings
import com.mtajchert.carom.app.core.data.runtime.ActivitySignal
import com.mtajchert.carom.app.core.data.runtime.AlwaysOnState
import com.mtajchert.carom.app.core.data.runtime.AndroidNotificationAccess
import com.mtajchert.carom.app.core.data.runtime.CancelWorkOnWipe
import com.mtajchert.carom.app.core.data.runtime.ForegroundSocketSession
import com.mtajchert.carom.app.core.data.runtime.HubSocketOpener
import com.mtajchert.carom.app.core.data.runtime.LiveConnectionState
import com.mtajchert.carom.app.core.data.runtime.LiveHubConnection
import com.mtajchert.carom.app.core.data.runtime.NotificationAccess
import com.mtajchert.carom.app.core.data.runtime.PiggybackFetch
import com.mtajchert.carom.app.core.data.runtime.StartActionWindow
import com.mtajchert.carom.app.core.data.runtime.SyncStartHook
import com.mtajchert.carom.app.core.data.sync.AndroidDeviceProfileProvider
import com.mtajchert.carom.app.core.data.sync.CaromSync
import com.mtajchert.carom.app.core.data.sync.DataStoreSyncStateStore
import com.mtajchert.carom.app.core.data.sync.DefaultCaromSync
import com.mtajchert.carom.app.core.data.sync.DirectoryStore
import com.mtajchert.carom.app.core.data.sync.EnrolledHubProvider
import com.mtajchert.carom.app.core.data.sync.Enrollment
import com.mtajchert.carom.app.core.data.sync.EnrollmentStore
import com.mtajchert.carom.app.core.data.sync.HubProvider
import com.mtajchert.carom.app.core.data.sync.IncomingMessageHandler
import com.mtajchert.carom.app.core.data.sync.LocalDeviceProfileProvider
import com.mtajchert.carom.app.core.data.sync.LocalStateWiper
import com.mtajchert.carom.app.core.data.sync.LogcatSyncLog
import com.mtajchert.carom.app.core.data.sync.NoticeSink
import com.mtajchert.carom.app.core.data.sync.OutboxStore
import com.mtajchert.carom.app.core.data.sync.PreferencesStateWiper
import com.mtajchert.carom.app.core.data.sync.ReceiveRulesStore
import com.mtajchert.carom.app.core.data.sync.RecentItemNoticeSink
import com.mtajchert.carom.app.core.data.sync.RoomDirectoryStore
import com.mtajchert.carom.app.core.data.sync.RoomOutboxStore
import com.mtajchert.carom.app.core.data.sync.RoomSeenMessageStore
import com.mtajchert.carom.app.core.data.sync.SeenMessageStore
import com.mtajchert.carom.app.core.data.sync.SentListener
import com.mtajchert.carom.app.core.data.sync.SyncLog
import com.mtajchert.carom.app.core.data.sync.SyncStateStore
import com.mtajchert.carom.app.core.data.sync.SyncStatusRepository
import com.mtajchert.carom.app.core.data.sync.WipeHook
import com.mtajchert.carom.app.core.data.work.SyncWorkScheduler
import com.mtajchert.carom.app.core.data.work.WorkManagerSyncScheduler
import com.mtajchert.carom.app.core.hub.Backoff
import com.mtajchert.carom.app.core.hub.HubClientFactory
import com.mtajchert.carom.app.core.model.SessionState
import com.mtajchert.carom.app.core.protocol.DeviceKind
import com.mtajchert.carom.app.core.security.SecretVault
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoSet
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Singleton

/**
 * Data layer bindings. Feature agents contribute message handlers with
 * `@Binds @IntoSet fun bind(h: ClipboardHandler): IncomingMessageHandler`.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds @Singleton
    internal abstract fun bindsUserPreferencesRepository(impl: DefaultUserPreferencesRepository): UserPreferencesRepository

    @Binds @Singleton
    internal abstract fun bindsSessionRepository(impl: DefaultSessionRepository): SessionRepository

    @Binds @Singleton
    internal abstract fun bindsDeviceRepository(impl: DefaultDeviceRepository): DeviceRepository

    @Binds @Singleton
    internal abstract fun bindsRecentItemsRepository(impl: DefaultRecentItemsRepository): RecentItemsRepository

    @Binds
    internal abstract fun bindsOutboxStore(impl: RoomOutboxStore): OutboxStore

    @Binds
    internal abstract fun bindsSeenMessageStore(impl: RoomSeenMessageStore): SeenMessageStore

    @Binds
    internal abstract fun bindsDirectoryStore(impl: RoomDirectoryStore): DirectoryStore

    @Binds
    internal abstract fun bindsSyncStateStore(impl: DataStoreSyncStateStore): SyncStateStore

    @Binds
    internal abstract fun bindsEnrollmentStore(impl: DataStoreSyncStateStore): EnrollmentStore

    @Binds
    internal abstract fun bindsSyncStatusRepository(impl: DataStoreSyncStateStore): SyncStatusRepository

    @Binds
    internal abstract fun bindsReceiveRulesStore(impl: DataStoreSyncStateStore): ReceiveRulesStore

    @Binds
    internal abstract fun bindsLiveHubConnection(impl: LiveConnectionState): LiveHubConnection

    @Binds
    internal abstract fun bindsNotificationAccess(impl: AndroidNotificationAccess): NotificationAccess

    @Binds
    internal abstract fun bindsSentListener(impl: PiggybackFetch): SentListener

    @Binds @IntoSet
    internal abstract fun bindsCancelWorkOnWipe(impl: CancelWorkOnWipe): WipeHook

    /** Starts the action window with the app process; it also reads the setting at that point. */
    @Binds @IntoSet
    internal abstract fun bindsStartActionWindow(impl: StartActionWindow): SyncStartHook

    /** Contributed by feature modules (e.g. the ongoing notification); empty without them. */
    @Multibinds
    internal abstract fun syncStartHooks(): Set<SyncStartHook>

    @Binds
    internal abstract fun bindsNoticeSink(impl: RecentItemNoticeSink): NoticeSink

    @Binds
    internal abstract fun bindsHubProvider(impl: EnrolledHubProvider): HubProvider

    @Binds
    internal abstract fun bindsProfileProvider(impl: AndroidDeviceProfileProvider): LocalDeviceProfileProvider

    @Binds
    internal abstract fun bindsLocalStateWiper(impl: PreferencesStateWiper): LocalStateWiper

    @Binds
    internal abstract fun bindsSyncLog(impl: LogcatSyncLog): SyncLog

    @Binds @Singleton
    internal abstract fun bindsSyncWorkScheduler(impl: WorkManagerSyncScheduler): SyncWorkScheduler

    /** Empty until feature agents add handlers; an unhandled type is acknowledged and ignored. */
    @Multibinds
    internal abstract fun incomingMessageHandlers(): Set<IncomingMessageHandler>

    companion object {
        @Provides
        @Singleton
        fun providesCaromSync(
            vault: SecretVault,
            hubProvider: HubProvider,
            outbox: OutboxStore,
            seen: SeenMessageStore,
            directory: DirectoryStore,
            syncState: SyncStateStore,
            handlers: Set<@JvmSuppressWildcards IncomingMessageHandler>,
            profileProvider: LocalDeviceProfileProvider,
            notices: NoticeSink,
            wiper: LocalStateWiper,
            log: SyncLog,
            wipeHooks: Set<@JvmSuppressWildcards WipeHook>,
            sentListener: SentListener,
        ): CaromSync = DefaultCaromSync(
            vault = vault,
            hubProvider = hubProvider,
            outbox = outbox,
            seen = seen,
            directory = directory,
            syncState = syncState,
            handlers = handlers,
            profileProvider = profileProvider,
            notices = notices,
            localStateWiper = wiper,
            log = log,
            wipeHooks = wipeHooks,
            sentListener = sentListener,
        )

        @Provides
        @Singleton
        fun providesEnrollment(
            vault: SecretVault,
            hubFactory: HubClientFactory,
            hubProvider: HubProvider,
            store: EnrollmentStore,
            sync: CaromSync,
        ): Enrollment = Enrollment(vault, hubFactory, hubProvider, store, sync)

        @Provides
        @Singleton
        fun providesWorkManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)

        /**
         * The one action window. Its session is a second `ForegroundSocketSession` with its own
         * [LiveConnectionState], so it never flips the "app is visible" flag. While the app is
         * visible both sockets are open; the Hub fans out to both and the seen-message store drops
         * the duplicate.
         */
        @Provides
        @Singleton
        fun providesActionWindow(
            preferences: UserPreferencesRepository,
            session: SessionRepository,
            directory: DirectoryStore,
            sync: CaromSync,
            opener: HubSocketOpener,
            log: SyncLog,
        ): ActionWindow {
            val settings = combine(preferences.userPreferences, session.session) { prefs, s ->
                ActionWindowSettings(prefs.actionsFromMac, prefs.actionWindowMinutes, paired = s is SessionState.Paired)
            }.distinctUntilChanged()
            return ActionWindow(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                settings = settings,
                hasDesktopOnline = { online ->
                    directory.all().any { it.id in online && it.record?.kind == DeviceKind.DESKTOP }
                },
                runSession = { maxBackoffMs ->
                    ForegroundSocketSession(
                        sync = sync,
                        state = LiveConnectionState(),
                        connect = opener::open,
                        backoff = Backoff(maxMs = maxBackoffMs),
                    ).run()
                },
                log = log,
            )
        }

        @Provides
        fun providesActivitySignal(window: ActionWindow): ActivitySignal = window

        @Provides
        fun providesActionWindowHold(window: ActionWindow): ActionWindowHold = window

        @Provides
        fun providesAlwaysOnState(window: ActionWindow): AlwaysOnState = window
    }
}
