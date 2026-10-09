package dev.wckdboy.autobot.engine.llama.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.wckdboy.autobot.engine.llama.LocalLlm
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LlamaModule {
    @Provides
    @Singleton
    fun provideLocalLlm(@ApplicationContext context: Context): LocalLlm = LocalLlm(context)
}
