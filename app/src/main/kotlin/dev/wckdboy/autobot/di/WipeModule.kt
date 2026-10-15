package dev.wckdboy.autobot.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.wckdboy.autobot.core.models.ModelDownloader
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
}
