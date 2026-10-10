package dev.wckdboy.autobot.engine.npu.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.wckdboy.autobot.engine.npu.LocalNpu
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NpuModule {
    @Provides
    @Singleton
    fun provideLocalNpu(@ApplicationContext context: Context): LocalNpu = LocalNpu(context)
}
