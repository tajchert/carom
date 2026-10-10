package com.mtajchert.carom.app.core.domain

import com.mtajchert.carom.app.core.data.repository.SessionRepository
import com.mtajchert.carom.app.core.data.sync.CaromSync
import com.mtajchert.carom.app.core.data.sync.OutboxStore
import com.mtajchert.carom.app.core.model.ConnectionStatus
import com.mtajchert.carom.app.core.model.SessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/** What Home shows: pairing state, what the engine is doing and how much is waiting to be sent. */
data class HomeStatus(
    val session: SessionState,
    val connection: ConnectionStatus,
    val pendingMessages: Int,
)

class ObserveHomeStatusUseCase @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val sync: CaromSync,
    private val outbox: OutboxStore,
) {
    operator fun invoke(): Flow<HomeStatus> = combine(
        sessionRepository.session,
        sync.status,
        outbox.observePendingCount(),
    ) { session, status, pending ->
        HomeStatus(
            session = session,
            connection = if (session is SessionState.Paired) status else ConnectionStatus.NOT_SET_UP,
            pendingMessages = pending,
        )
    }
}
