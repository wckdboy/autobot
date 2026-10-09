package dev.wckdboy.autobot.engine.diffusion.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.wckdboy.autobot.engine.diffusion.LocalSd
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SdModule {
    @Provides
    @Singleton
    fun provideLocalSd(@ApplicationContext context: Context): LocalSd = LocalSd(context)
}
