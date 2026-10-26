package dev.wckdboy.autobot.feature.chat.conversation

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.ImageBitmap
import dev.wckdboy.autobot.agent.core.approval.PermissionPreset
import dev.wckdboy.autobot.agent.core.llm.ThinkingSetting
import dev.wckdboy.autobot.agent.core.session.ApprovalOutcome
import dev.wckdboy.autobot.core.a2ui.A2uiChatState
import dev.wckdboy.autobot.shared.a2ui.A2uiActionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val NoneSaved: StateFlow<Map<String, String>> = MutableStateFlow(emptyMap())

/** What the chat screen can ask of its [ChatViewModel] (an interface so the screen can be measured with a fake). */
@Stable
interface ChatActions {
    val draft: String

    fun setDraft(text: String)
    fun send(text: String)
    fun stop()
    fun attach(uri: Uri)
    fun removeAttachment(id: String)
    suspend fun attachmentThumbnail(id: String): ImageBitmap?
    suspend fun thumbnail(galleryId: String): ImageBitmap?

    /** Unsaved incognito images ([dev.wckdboy.autobot.shared.panic.IncognitoImages]) the user saved, by id → gallery id. */
    val savedImages: StateFlow<Map<String, String>> get() = NoneSaved

    /** Saves an unsaved incognito image into the gallery (the Save button). */
    fun saveImage(id: String) {}
    fun toggleDictation()
    fun consumeDictation()
    fun speak(key: String, text: String)
    fun setVoiceMode(on: Boolean)
    fun setHandsFree(on: Boolean)
    fun voiceTap()
    fun voiceDenied()
    fun approve(callId: String, outcome: ApprovalOutcome)
    fun answer(callId: String, answers: List<String>?)
    fun selectProvider(providerId: String)
    fun selectModel(model: String, confirmed: Boolean = false)
    fun refreshModels()
    fun setToolsEnabled(enabled: Boolean)
    fun setPermission(preset: PermissionPreset)

    /** This chat's Think switch (remembered with the chat). */
    fun setThinking(setting: ThinkingSetting)

    /** The Think default for this chat's model (null: back to auto). */
    fun setThinkingDefault(setting: ThinkingSetting?)

    /** This chat's redaction switch (remembered with the chat; what Settings says makes it follow Settings). */
    fun setRedaction(enabled: Boolean)

    /** This chat's local-first cascade policy (remembered with the chat; what Settings says makes it follow Settings). */
    fun setCascade(policy: dev.wckdboy.autobot.shared.cascade.CascadePolicy)

    /** "Ask <model>": sends the prompt of the phone's offered answer to the chat's model (a cloud one, or the user's node). */
    fun escalate()
    fun compact()
    fun rename(title: String)
    fun setPinned(pinned: Boolean)
    fun setArchived(archived: Boolean)
    fun delete()
    fun undoStep(callId: String)
    fun undoTurn(turn: Int)
    fun redo(revertId: String)
    fun dismissNotice()

    /** This chat's generative UI (`show_ui` surfaces); null where a screen has none. */
    val a2ui: A2uiChatState? get() = null

    /** A tap on a generated UI: a [A2uiActionResult.SendEvent] goes to the agent as the next turn. */
    fun a2uiAction(action: A2uiActionResult) {}
}
