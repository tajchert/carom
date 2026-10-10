package com.mtajchert.carom.app.feature.pairing.impl.di

import com.mtajchert.carom.app.feature.pairing.api.PairCandidateFeed
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
interface PairingModule {
    /** Bound by the foreground WebSocket runtime when there is one; the screen polls otherwise. */
    @BindsOptionalOf
    fun optionalPairCandidateFeed(): PairCandidateFeed
}
