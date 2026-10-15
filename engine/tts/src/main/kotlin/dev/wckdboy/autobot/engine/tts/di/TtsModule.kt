package dev.wckdboy.autobot.engine.tts.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.wckdboy.autobot.engine.tts.LocalVoice
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TtsModule {
    @Provides
    @Singleton
    fun provideLocalVoice(): LocalVoice = LocalVoice()
}
