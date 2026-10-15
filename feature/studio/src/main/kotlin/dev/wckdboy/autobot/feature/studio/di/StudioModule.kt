package dev.wckdboy.autobot.feature.studio.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.wckdboy.autobot.agent.runtime.AgentPluginProvider
import dev.wckdboy.autobot.feature.studio.engine.StudioHost
import dev.wckdboy.autobot.shared.studio.studioPlugin

@Module
@InstallIn(SingletonComponent::class)
object StudioModule {

    /**
     * Contributes `run_workflow` (GENERATE: asks like `generate_image`; remote backends follow the
     * kill switch and route) so the agent can run a saved workflow by name.
     */
    @Provides
    @IntoSet
    fun provideStudioToolPlugin(host: StudioHost): AgentPluginProvider = AgentPluginProvider { studioPlugin(host.agentRunner) }
}
