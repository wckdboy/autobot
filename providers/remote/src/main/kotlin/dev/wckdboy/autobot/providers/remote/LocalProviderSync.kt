package dev.wckdboy.autobot.providers.remote

import dev.wckdboy.autobot.core.data.ProviderRepository
import dev.wckdboy.autobot.core.data.model.Provider
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.core.models.ModelLibrary
import dev.wckdboy.autobot.core.models.SystemModel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the built-in "This phone" provider in step with installed chat models: it appears when
 * the first on-device chat model finishes downloading (or when the OS's own model is usable),
 * its default model is always an installed one, and it disappears when nothing is left.
 */
@Singleton
class LocalProviderSync @Inject constructor(
    private val providers: ProviderRepository,
    private val library: ModelLibrary,
    private val systemModel: SystemModel,
) {
    fun start(scope: CoroutineScope) {
        scope.launch { systemModel.refresh() }
        scope.launch {
            val installed = library.models
                .map { list -> list.filter { it.isLocalChat }.sortedBy { it.addedAt }.map { it.id } }
            combine(installed, systemModel.status) { ids, status -> localModels(ids, status) }
                .distinctUntilChanged()
                .collect { ids -> sync(ids) }
        }
    }

    private suspend fun sync(models: List<String>) {
        val existing = providers.getProvider(LOCAL_PROVIDER_ID)
        if (models.isEmpty()) {
            if (existing != null) providers.delete(LOCAL_PROVIDER_ID)
            return
        }
        val defaultModel = existing?.defaultModel?.takeIf { it in models } ?: models.first()
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

        /** Models "This phone" offers: installed ones first, then the OS's model when it can run. */
        internal fun localModels(installed: List<String>, system: SystemModelStatus?): List<String> =
            installed + listOfNotNull(system?.takeIf { it.usable }?.id)
    }
}
