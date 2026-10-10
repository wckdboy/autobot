package dev.wckdboy.autobot.engine.speech.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.wckdboy.autobot.engine.speech.LocalSpeech
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SpeechModule {
    @Provides
    @Singleton
    fun provideLocalSpeech(@ApplicationContext context: Context): LocalSpeech = LocalSpeech(context)
}
