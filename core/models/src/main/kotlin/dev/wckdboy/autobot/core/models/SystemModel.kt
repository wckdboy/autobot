package dev.wckdboy.autobot.core.models

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.wckdboy.autobot.providers.remote.SystemLlm
import dev.wckdboy.autobot.providers.remote.SystemModelStatus
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The operating system's own chat model for this build. Only the opt-in `playServices` flavor
 * binds one (Gemini Nano through ML Kit, `:engine:systemllm`); the default build has none, so
 * [llm] is null and [status] stays null.
 */
@Singleton
class SystemModel @Inject constructor(engine: Optional<SystemLlm>) {
    val llm: SystemLlm? = engine.orElse(null)

    private val _status = MutableStateFlow<SystemModelStatus?>(null)

    /** The last known status; null until [refresh] or when the build has no system model. */
    val status: StateFlow<SystemModelStatus?> = _status.asStateFlow()

    suspend fun refresh(): SystemModelStatus? {
        val engine = llm ?: return null
        return runCatching { engine.status() }.getOrNull().also { _status.value = it }
    }
}

@Module
@InstallIn(SingletonComponent::class)
interface SystemModelModule {
    @BindsOptionalOf
    fun systemLlm(): SystemLlm
}
