package com.mtajchert.carom.app.core.testing.runtime

import com.mtajchert.carom.app.core.data.runtime.NotificationAccess
import com.mtajchert.carom.app.core.data.runtime.PushStatusRepository
import com.mtajchert.carom.app.core.data.sync.ReceiveRulesStore
import com.mtajchert.carom.app.core.data.sync.SyncStatusRepository
import com.mtajchert.carom.app.core.hub.HubSocket
import com.mtajchert.carom.app.core.hub.HubSocketEvent
import com.mtajchert.carom.app.core.model.PushState
import com.mtajchert.carom.app.core.protocol.ReceiveRule
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

class FakePushStatusRepository(initial: PushState = PushState.REGISTERED) : PushStatusRepository {
    override val state = MutableStateFlow(initial)
}

class FakeSyncStatusRepository : SyncStatusRepository {
    override val lastSyncAt = MutableStateFlow<Long?>(null)
    override val rejected = MutableStateFlow(false)
    override val dismissedNoticeId = MutableStateFlow(0L)
    override suspend fun dismissNotices(upToId: Long) {
        dismissedNoticeId.value = maxOf(dismissedNoticeId.value, upToId)
    }
}

class FakeNotificationAccess(var granted: Boolean = true) : NotificationAccess {
    override fun isGranted(): Boolean = granted
}

class FakeReceiveRulesStore(var rules: Map<String, ReceiveRule>? = null) : ReceiveRulesStore {
    override suspend fun receiveRules(): Map<String, ReceiveRule>? = rules
    override suspend fun setReceiveRules(rules: Map<String, ReceiveRule>) {
        this.rules = rules
    }
}

/** A scriptable [HubSocket]: tests [send] frames and inspect [acks] and [closed]. */
class FakeHubSocket : HubSocket {
    private val channel = Channel<HubSocketEvent>(Channel.UNLIMITED)
    override val events: Flow<HubSocketEvent> = channel.receiveAsFlow()

    val acks = mutableListOf<Long>()
    var closed = false
        private set

    fun send(event: HubSocketEvent) {
        channel.trySend(event)
    }

    /** Ends the event stream, like OkHttp after a close or failure. */
    fun finish(event: HubSocketEvent) {
        channel.trySend(event)
        channel.close()
    }

    override fun ack(upTo: Long): Boolean {
        acks += upTo
        return !closed
    }

    override fun close() {
        closed = true
        channel.close()
    }
}
