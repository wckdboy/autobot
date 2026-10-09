package dev.wckdboy.autobot.agent.runtime.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import dev.wckdboy.autobot.agent.runtime.AgentHost
import dev.wckdboy.autobot.agent.runtime.AgentPluginProvider
import dev.wckdboy.autobot.core.security.WipeParticipant
import javax.inject.Provider

@Module
@InstallIn(SingletonComponent::class)
abstract class AgentRuntimeModule {

    /** Other modules contribute harness plugins with `@IntoSet`; the set may be empty. */
    @Multibinds
    abstract fun agentPlugins(): Set<AgentPluginProvider>

    companion object {
        /** Stops every agent and drops incognito logs before a panic wipe deletes their storage. */
        @Provides
        @IntoSet
        fun provideAgentWipeParticipant(host: Provider<AgentHost>): WipeParticipant = WipeParticipant {
            host.get().shutdown()
        }
    }
}
