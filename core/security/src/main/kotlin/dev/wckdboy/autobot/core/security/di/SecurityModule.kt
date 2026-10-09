package dev.wckdboy.autobot.core.security.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import dev.wckdboy.autobot.core.security.WipeParticipant

@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {
    /** Declares the (possibly empty) set of [WipeParticipant]s. */
    @Multibinds
    abstract fun wipeParticipants(): Set<WipeParticipant>
}
