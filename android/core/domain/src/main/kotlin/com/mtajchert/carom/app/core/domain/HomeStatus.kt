package com.mtajchert.carom.app.core.domain

import com.mtajchert.carom.app.core.data.repository.SessionRepository
import com.mtajchert.carom.app.core.data.runtime.PushStatusRepository
import com.mtajchert.carom.app.core.data.sync.CaromSync
import com.mtajchert.carom.app.core.data.sync.OutboxStore
import com.mtajchert.carom.app.core.data.sync.SyncStatusRepository
import com.mtajchert.carom.app.core.model.ConnectionStatus
import com.mtajchert.carom.app.core.model.PushState
import com.mtajchert.carom.app.core.model.SessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/** What Home shows: pairing state, what the engine is doing, push and how much is waiting to be sent. */
data class HomeStatus(
    val session: SessionState,
    val connection: ConnectionStatus,
    val pendingMessages: Int,
    val push: PushState = PushState.UNKNOWN,
    val lastSyncAt: Long? = null,
    /** The Hub refused this device (`401`): it must pair again (SPEC §5.4). */
    val mustPairAgain: Boolean = false,
)

class ObserveHomeStatusUseCase @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val sync: CaromSync,
    private val outbox: OutboxStore,
    private val push: PushStatusRepository,
    private val syncStatus: SyncStatusRepository,
) {
    operator fun invoke(): Flow<HomeStatus> = combine(
        sessionRepository.session,
        sync.status,
        outbox.observePendingCount(),
        push.state,
        combine(syncStatus.lastSyncAt, syncStatus.rejected) { last, rejected -> last to rejected },
    ) { session, status, pending, pushState, (lastSync, rejected) ->
        val paired = session is SessionState.Paired
        HomeStatus(
            session = session,
            connection = when {
                !paired -> ConnectionStatus.NOT_SET_UP
                rejected -> ConnectionStatus.REJECTED
                else -> status
            },
            pendingMessages = pending,
            push = pushState,
            lastSyncAt = lastSync,
            mustPairAgain = paired && rejected,
        )
    }
}
