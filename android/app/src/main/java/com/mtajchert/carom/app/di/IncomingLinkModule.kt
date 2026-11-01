package com.mtajchert.carom.app.di

import com.mtajchert.carom.app.feature.onboarding.api.IncomingLink
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** One holder shared by MainActivity (offers a carom:// link) and onboarding (consumes it). */
@Module
@InstallIn(SingletonComponent::class)
object IncomingLinkModule {
    @Provides
    @Singleton
    fun providesIncomingLink(): IncomingLink = IncomingLink()
}
