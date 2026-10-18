package dev.wckdboy.autobot.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.wckdboy.autobot.core.designsystem.markdown.LiteMarkdown
import dev.wckdboy.autobot.core.models.ModelDownloader
import dev.wckdboy.autobot.core.models.ModelLibrary
import dev.wckdboy.autobot.core.security.KeptModelsSource
import dev.wckdboy.autobot.core.security.WipeParticipant
import javax.inject.Provider

@Module
@InstallIn(SingletonComponent::class)
object WipeModule {
    /** Stops downloads (which write progress to the database) before a panic wipe closes it. */
    @Provides
    @IntoSet
    fun provideDownloadsWipeParticipant(downloader: Provider<ModelDownloader>): WipeParticipant =
        WipeParticipant.ordered(WipeParticipant.STOP_WORK) { downloader.get().pauseAll() }

    /** A wipe that keeps models carries their registry rows to the new install. */
    @Provides
    @IntoSet
    fun provideKeptModelsSource(library: Provider<ModelLibrary>): KeptModelsSource = KeptModelsSource { library.get().exportReady() }

    /** Parsed chat text kept for rendering goes too (decrypted content in memory). */
    @Provides
    @IntoSet
    fun provideMarkdownCacheWipeParticipant(): WipeParticipant =
        WipeParticipant.ordered(WipeParticipant.STOP_WORK) { LiteMarkdown.clearCache() }
}
