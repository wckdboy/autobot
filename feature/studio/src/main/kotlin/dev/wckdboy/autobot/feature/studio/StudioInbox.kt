package dev.wckdboy.autobot.feature.studio

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A workflow shared into the app (share sheet): a content URI to read, or the JSON text itself. */
sealed interface WorkflowShare {
    data class File(val uri: String) : WorkflowShare
    data class Text(val json: String) : WorkflowShare
}

/**
 * Hands a shared workflow from the entry point to the Workflows room (it can be larger than a
 * navigation key should carry). Nothing is imported until the user reviews and saves it.
 */
@Singleton
class StudioInbox @Inject constructor() {
    private val _pending = MutableStateFlow<WorkflowShare?>(null)
    val pending: StateFlow<WorkflowShare?> = _pending.asStateFlow()

    fun put(share: WorkflowShare) {
        _pending.value = share
    }

    fun take(): WorkflowShare? = _pending.value.also { _pending.value = null }
}
