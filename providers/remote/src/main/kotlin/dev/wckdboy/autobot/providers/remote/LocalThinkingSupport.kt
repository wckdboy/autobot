package dev.wckdboy.autobot.providers.remote

import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.core.models.FileRole
import dev.wckdboy.autobot.core.models.ModelLibrary
import dev.wckdboy.autobot.engine.llama.GgufChatTemplate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Whether a model reasons (and can switch it off), for the Think chip: an on-device model's own
 * chat template when its file has one, else the model's name ([ThinkingSupport.byName]).
 */
@Singleton
class LocalThinkingSupport @Inject constructor(private val library: ModelLibrary) {
    suspend fun of(kind: ProviderKind, model: String): ThinkingSupport {
        if (kind == ProviderKind.LOCAL && !SystemModels.isSystem(model)) {
            val path = library.get(model)?.paths?.get(FileRole.MODEL)
            if (path != null) {
                val template = withContext(Dispatchers.IO) { runCatching { GgufChatTemplate.read(path) }.getOrNull() }
                ThinkingSupport.fromTemplate(template)?.let { return it }
            }
        }
        return ThinkingSupport.byName(kind, model)
    }
}
