package com.mtajchert.carom.app.di

import com.mtajchert.carom.app.core.data.runtime.LiveHubConnection
import com.mtajchert.carom.app.feature.pairing.api.PairCandidateFeed
import com.mtajchert.carom.app.feature.pairing.api.PushedPairCandidate
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.map

/**
 * Connects the "Add device" screen to the foreground WebSocket: every `pair` frame (SPEC §7)
 * reaches the screen at once instead of on its next poll. Candidates stay untrusted until the
 * screen verifies them (SPEC §8 step 3).
 */
@Module
@InstallIn(SingletonComponent::class)
object PairingFeedModule {
    @Provides
    fun providesPairCandidateFeed(live: LiveHubConnection): PairCandidateFeed = PairCandidateFeed {
        live.pairCandidates.map { PushedPairCandidate(it.pairId, it.candidate) }
    }
}
