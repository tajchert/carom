package com.mtajchert.carom.app.feature.clipboard.impl

import com.mtajchert.carom.app.core.data.sync.DirectoryEntry
import com.mtajchert.carom.app.core.protocol.DeviceKind
import com.mtajchert.carom.app.core.protocol.DeviceRecord
import com.mtajchert.carom.app.core.protocol.Envelope
import com.mtajchert.carom.app.core.protocol.MessageBodies
import com.mtajchert.carom.app.core.protocol.MessageTypes
import com.mtajchert.carom.app.core.protocol.Payload
import com.mtajchert.carom.app.core.protocol.ReceiveRule
import com.mtajchert.carom.app.core.protocol.Received
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeSendTargets(initial: Set<String>? = null) : ClipboardSendTargets {
    val state = MutableStateFlow(initial)
    override val selected: Flow<Set<String>?> = state
    override suspend fun set(ids: Set<String>?) {
        state.value = ids
    }
}

class FakeNotifier : ClipboardArrivalNotifier {
    class Shown(val from: String, val text: String, val sensitive: Boolean)

    val shown = mutableListOf<Shown>()
    override fun show(fromName: String, text: String, sensitive: Boolean) {
        shown += Shown(fromName, text, sensitive)
    }
}

fun clipboardMessage(from: String, text: String, sensitive: Boolean = false): Received = Received(
    Envelope(msgId = "m1", from = from, to = listOf("self"), ttlS = 60, kv = 1, salt = "", ct = "", sig = ""),
    Payload(
        type = MessageTypes.CLIPBOARD_SET,
        ts = 1,
        body = MessageBodies.toJson(MessageBodies.ClipboardSet.serializer(), MessageBodies.ClipboardSet(text = text, sensitive = sensitive)),
    ),
)

fun entry(
    id: String,
    name: String,
    receive: Map<String, ReceiveRule> = mapOf(MessageTypes.CLIPBOARD_SET to ReceiveRule.Everyone),
    caps: List<String> = listOf(MessageTypes.CLIPBOARD_SET),
    kind: DeviceKind = DeviceKind.DESKTOP,
): DirectoryEntry = DirectoryEntry(
    id = id,
    signPub = "",
    kxPub = "",
    createdAt = 0,
    lastSeen = null,
    hasPush = false,
    record = DeviceRecord(id, name, kind, "macos", "1", "", "", caps, receive, 1),
    recordKv = 1,
)
