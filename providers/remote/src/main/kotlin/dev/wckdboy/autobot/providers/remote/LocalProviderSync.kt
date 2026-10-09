package dev.wckdboy.autobot.providers.remote

import dev.wckdboy.autobot.core.data.ProviderRepository
import dev.wckdboy.autobot.core.data.model.Provider
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.core.models.EngineKind
import dev.wckdboy.autobot.core.models.ModelLibrary
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the built-in "This phone" provider in step with installed chat models: it appears when
 * the first on-device chat model finishes downloading, its default model is always an
 * installed one, and it disappears when the last one is deleted.
 */
@Singleton
class LocalProviderSync @Inject constructor(
    private val providers: ProviderRepository,
    private val library: ModelLibrary,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            library.models
                .map { list -> list.filter { it.isReady && it.engine == EngineKind.LLAMA }.sortedBy { it.addedAt }.map { it.id } }
                .distinctUntilChanged()
                .collect { ids -> sync(ids) }
        }
    }

    private suspend fun sync(readyChatModels: List<String>) {
        val existing = providers.getProvider(LOCAL_PROVIDER_ID)
        if (readyChatModels.isEmpty()) {
            if (existing != null) providers.delete(LOCAL_PROVIDER_ID)
            return
        }
        val defaultModel = existing?.defaultModel?.takeIf { it in readyChatModels } ?: readyChatModels.first()
        if (existing?.defaultModel == defaultModel && existing.kind == ProviderKind.LOCAL) return
        providers.save(
            Provider(
                id = LOCAL_PROVIDER_ID,
                kind = ProviderKind.LOCAL,
                displayName = "This phone",
                baseUrl = ProviderPresets.LOCAL_BASE_URL,
                defaultModel = defaultModel,
            ),
        )
    }

    companion object {
        const val LOCAL_PROVIDER_ID = "local-this-phone"
    }
}
