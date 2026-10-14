package dev.wckdboy.autobot.core.network.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.wckdboy.autobot.core.network.AuditLog
import javax.inject.Singleton

/** [AuditLog] lives in the shared (multiplatform) module, so it is provided here. */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun auditLog(): AuditLog = AuditLog()
}
