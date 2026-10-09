package dev.wckdboy.autobot.feature.imagine.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.wckdboy.autobot.agent.core.tools.toolsPlugin
import dev.wckdboy.autobot.agent.runtime.AgentPluginProvider
import dev.wckdboy.autobot.core.diffusion.DiffusionBackendRepository
import dev.wckdboy.autobot.feature.imagine.generate.GenerateImageTool
import dev.wckdboy.autobot.feature.imagine.generate.ImageGenerator

@Module
@InstallIn(SingletonComponent::class)
object ImagineModule {

    /** Contributes `generate_image` to the agent harness. */
    @Provides
    @IntoSet
    fun provideImageToolPlugin(backends: DiffusionBackendRepository, generator: ImageGenerator): AgentPluginProvider =
        AgentPluginProvider { toolsPlugin("image-generation", GenerateImageTool(backends, generator)) }
}
