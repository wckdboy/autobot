package dev.wckdboy.autobot.entry

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import androidx.navigation3.runtime.NavKey
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.agent.runtime.AgentHost
import dev.wckdboy.autobot.agent.runtime.recipes.RecipeNotifications
import dev.wckdboy.autobot.core.data.ConversationRepository
import dev.wckdboy.autobot.core.data.ProviderRepository
import dev.wckdboy.autobot.core.data.settings.SettingsRepository
import dev.wckdboy.autobot.core.models.ModelLibrary
import dev.wckdboy.autobot.feature.chat.conversation.ChatHandoff
import dev.wckdboy.autobot.feature.chat.conversation.ChatStart
import dev.wckdboy.autobot.feature.home.defaultChatTarget
import dev.wckdboy.autobot.feature.studio.StudioInbox
import dev.wckdboy.autobot.feature.studio.WorkflowShare
import dev.wckdboy.autobot.navigation.Chat
import dev.wckdboy.autobot.navigation.Imagine
import dev.wckdboy.autobot.navigation.Models
import dev.wckdboy.autobot.navigation.Recipes
import dev.wckdboy.autobot.navigation.Run
import dev.wckdboy.autobot.navigation.Settings
import dev.wckdboy.autobot.navigation.Studio
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** Where an [EntryRequest] lands. */
sealed interface EntryPlan {
    /** Start a new chat and hand it [start]. */
    data class NewChat(val start: ChatStart) : EntryPlan

    data class Studio(val prompt: String?) : EntryPlan

    /** The Studio's Workflows room, with a shared workflow to review. */
    data class Workflow(val share: WorkflowShare) : EntryPlan

    /** No chat model or provider yet: the Models tab. */
    data object Setup : EntryPlan

    /** A recipe's result chat over the Recipes screen (just the screen when the chat is gone). */
    data class Recipe(val chatId: String?) : EntryPlan
}

/** Entry routing, kept pure for tests. */
object EntryRouting {
    fun plan(request: EntryRequest, canChat: Boolean): EntryPlan = when (request) {
        is EntryRequest.Imagine -> EntryPlan.Studio(request.prompt)
        is EntryRequest.ImportWorkflow -> EntryPlan.Workflow(request.uri?.let { WorkflowShare.File(it) } ?: WorkflowShare.Text(request.json.orEmpty()))
        is EntryRequest.NewChat -> if (canChat) EntryPlan.NewChat(ChatStart(draft = request.draft, images = request.images)) else EntryPlan.Setup
        EntryRequest.Voice -> if (canChat) EntryPlan.NewChat(ChatStart(voice = true)) else EntryPlan.Setup
        is EntryRequest.OpenRecipe -> EntryPlan.Recipe(request.chatId)
    }

    /** The back stack after an entry: RUN underneath, so back leads home. */
    fun backStack(plan: EntryPlan, conversationId: String?): List<NavKey> = when (plan) {
        is EntryPlan.NewChat -> if (conversationId != null) listOf(Run, Chat(conversationId)) else listOf(Models)
        is EntryPlan.Studio -> listOf(Run, Imagine(prompt = plan.prompt))
        is EntryPlan.Workflow -> listOf(Run, Studio(room = "workflows"))
        EntryPlan.Setup -> listOf(Models)
        is EntryPlan.Recipe -> listOfNotNull(Settings, Recipes, plan.chatId?.let(::Chat))
    }
}

/**
 * Holds the request of the intent that opened (or reopened) the activity until the app is
 * unlocked and set up, then turns it into a back stack. New chats follow the "incognito by
 * default" setting and start with tools off (a plain chat, like the RUN card).
 */
@HiltViewModel
class EntryViewModel @Inject constructor(
    private val conversations: ConversationRepository,
    private val providers: ProviderRepository,
    private val library: ModelLibrary,
    private val settings: SettingsRepository,
    private val host: AgentHost,
    private val handoff: ChatHandoff,
    private val studioInbox: StudioInbox,
) : ViewModel() {

    private val _pending = MutableStateFlow<EntryRequest?>(null)
    val pending: StateFlow<EntryRequest?> = _pending

    fun receive(intent: Intent?) {
        intent?.toEntryRequest()?.let { _pending.value = it }
    }

    /** Takes the pending request and returns the back stack to show, or null if there was none. */
    suspend fun open(): List<NavKey>? {
        val request = _pending.value ?: return null
        _pending.value = null
        val target = defaultChatTarget(providers.observeProviders().first(), library.all())
        val plan = EntryRouting.plan(request, canChat = target != null)
        var conversationId: String? = null
        if (plan is EntryPlan.Workflow) studioInbox.put(plan.share)
        if (plan is EntryPlan.Recipe && plan.chatId != null && conversations.getConversation(plan.chatId) == null) {
            return EntryRouting.backStack(EntryPlan.Recipe(null), null)
        }
        if (plan is EntryPlan.NewChat && target != null) {
            val (provider, model) = target
            val conversation = conversations.createConversation(settings.settings.first().incognitoByDefault, provider.id, model)
            host.setToolsEnabled(conversation.id, false)
            handoff.put(conversation.id, plan.start)
            conversationId = conversation.id
        }
        return EntryRouting.backStack(plan, conversationId)
    }
}

/** Reads an incoming intent's fields for [EntryParser]. */
fun Intent.toEntryRequest(): EntryRequest? {
    val streams = when (action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(this, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }
    return EntryParser.parse(
        action = action,
        type = type,
        text = getCharSequenceExtra(Intent.EXTRA_TEXT),
        subject = getCharSequenceExtra(Intent.EXTRA_SUBJECT),
        streams = streams.map { it.toString() },
        processText = getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT),
        prompt = getStringExtra(EntryActions.EXTRA_PROMPT),
        chat = getStringExtra(RecipeNotifications.EXTRA_CHAT),
    )
}
