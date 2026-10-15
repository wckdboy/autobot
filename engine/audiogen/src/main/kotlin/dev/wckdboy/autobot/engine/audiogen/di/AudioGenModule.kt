package dev.wckdboy.autobot.engine.audiogen.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.wckdboy.autobot.engine.audiogen.LocalAudioGen
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AudioGenModule {
    @Provides
    @Singleton
    fun provideLocalAudioGen(): LocalAudioGen = LocalAudioGen()
}
