package dev.wckdboy.autobot.core.security.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import dev.wckdboy.autobot.core.security.KeptModelsSource
import dev.wckdboy.autobot.core.security.WipeParticipant
import dev.wckdboy.autobot.core.security.panic.DevicePinCrypto
import dev.wckdboy.autobot.core.security.panic.KeystorePinMacKey
import dev.wckdboy.autobot.core.security.panic.PinLock
import dev.wckdboy.autobot.core.security.panic.PinLockStore
import dev.wckdboy.autobot.core.security.panic.PinMacKey
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {
    /** Declares the (possibly empty) set of [WipeParticipant]s. */
    @Multibinds
    abstract fun wipeParticipants(): Set<WipeParticipant>

    /** At most one: the models module's registry export (empty in tests). */
    @Multibinds
    abstract fun keptModelsSources(): Set<KeptModelsSource>

    companion object {
        @Provides
        @Singleton
        fun providePinLockStore(@ApplicationContext context: Context): PinLockStore =
            PinLockStore(File(context.dataDir, PinLockStore.DIR))

        @Provides
        @Singleton
        fun providePinMacKey(): PinMacKey = KeystorePinMacKey()

        @Provides
        @Singleton
        fun providePinLock(store: PinLockStore, key: PinMacKey): PinLock = PinLock(store, DevicePinCrypto(key))
    }
}
