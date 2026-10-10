package com.mtajchert.carom.app.feature.pairing.api

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement

/**
 * A pairing candidate pushed by the Hub over a foreground WebSocket
 * (`{"t":"pair","pair_id":…,"candidate":{…}}`, SPEC §7). [candidate] is untrusted until the
 * inviting screen verifies it (SPEC §8 step 3).
 */
class PushedPairCandidate(val pairId: String, val candidate: JsonElement) {
    override fun toString(): String = "PushedPairCandidate($pairId)"
}

/**
 * Optional seam for the sync runtime. When the app keeps a WebSocket open while the UI is
 * visible, it binds an implementation (`@Binds PairCandidateFeed` in any Hilt module) that emits
 * every `pair` frame; the "Add device" screen then reacts at once instead of on its next
 * 2-second poll. Without a binding the screen only polls `GET /v1/pair/:id`.
 */
fun interface PairCandidateFeed {
    fun candidates(): Flow<PushedPairCandidate>
}
