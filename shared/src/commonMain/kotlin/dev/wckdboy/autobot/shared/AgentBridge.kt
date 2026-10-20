package dev.wckdboy.autobot.shared

import dev.wckdboy.autobot.shared.invincible.NodeCrypto
import dev.wckdboy.autobot.agent.core.session.SessionLog
import dev.wckdboy.autobot.shared.recipes.Recipe
import dev.wckdboy.autobot.shared.recipes.NodeRecipes
import dev.wckdboy.autobot.shared.recipes.NodeRecipeTarget
import dev.wckdboy.autobot.shared.invincible.NodeLink
import dev.wckdboy.autobot.shared.invincible.NodeAgentOpened
import dev.wckdboy.autobot.shared.invincible.ApprovalAnswer
import dev.wckdboy.autobot.shared.invincible.AgentSchedule
import dev.wckdboy.autobot.shared.invincible.AgentEvent
import dev.wckdboy.autobot.shared.invincible.NodeAgent
import dev.wckdboy.autobot.shared.invincible.NodeActivity
import dev.wckdboy.autobot.shared.invincible.NodeDigest
import dev.wckdboy.autobot.shared.invincible.NodeDigests
import dev.wckdboy.autobot.shared.invincible.PendingApproval
import dev.wckdboy.autobot.shared.invincible.AgentSession
import dev.wckdboy.autobot.shared.invincible.NodeBots
import dev.wckdboy.autobot.shared.invincible.NodeBotsRepository
import dev.wckdboy.autobot.shared.invincible.NodeBotsState
import dev.wckdboy.autobot.shared.invincible.nodeCrypto
import dev.wckdboy.autobot.shared.invincible.nodeConnector
import dev.wckdboy.autobot.shared.invincible.fingerprint
import dev.wckdboy.autobot.shared.invincible.PairedNode
import dev.wckdboy.autobot.shared.invincible.PairingCode
import dev.wckdboy.autobot.shared.invincible.PrivateTurns
import dev.wckdboy.autobot.shared.invincible.NodeReceiptLog
import dev.wckdboy.autobot.shared.invincible.HostNodeCrypto
import dev.wckdboy.autobot.shared.invincible.HostNodeConnector
import dev.wckdboy.autobot.shared.invincible.NodeProviders
import dev.wckdboy.autobot.shared.invincible.UnavailableChatProvider
import dev.wckdboy.autobot.agent.core.Disposable
import dev.wckdboy.autobot.agent.core.Harness
import dev.wckdboy.autobot.agent.core.Hooks
import dev.wckdboy.autobot.agent.core.approval.PermissionPreset
import dev.wckdboy.autobot.agent.core.llm.LlmCallConfig
import dev.wckdboy.autobot.agent.core.llm.ThinkingMode
import dev.wckdboy.autobot.agent.core.llm.ThinkingSetting
import dev.wckdboy.autobot.agent.core.loop.chooseThinking
import dev.wckdboy.autobot.agent.core.loop.restoreThinking
import dev.wckdboy.autobot.agent.core.loop.Agent
import dev.wckdboy.autobot.agent.core.loop.AgentConfig
import dev.wckdboy.autobot.agent.core.loop.AgentStatus
import dev.wckdboy.autobot.agent.core.loop.LiveStream
import dev.wckdboy.autobot.agent.core.monotonicNanos
import dev.wckdboy.autobot.agent.core.policy.Compactor
import dev.wckdboy.autobot.agent.core.policy.quotaFallbackPlugin
import dev.wckdboy.autobot.agent.core.session.ApprovalOutcome
import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.SessionJson
import dev.wckdboy.autobot.agent.core.session.SessionStore
import dev.wckdboy.autobot.agent.core.session.StoredLog
import dev.wckdboy.autobot.agent.core.tools.ToolExecutor
import dev.wckdboy.autobot.agent.core.tools.ToolRegistry
import dev.wckdboy.autobot.agent.core.tools.UserQuestion
import dev.wckdboy.autobot.agent.core.tools.toolsPlugin
import dev.wckdboy.autobot.agent.runtime.ImageAttachments
import dev.wckdboy.autobot.agent.runtime.ProviderLlmAdapter
import dev.wckdboy.autobot.agent.runtime.WebFetchTool
import dev.wckdboy.autobot.agent.runtime.workspace.FileTools
import dev.wckdboy.autobot.agent.runtime.workspace.Workspace
import dev.wckdboy.autobot.agent.runtime.workspace.WorkspaceContexts
import dev.wckdboy.autobot.agent.runtime.workspace.WorkspaceJournal
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.providers.remote.ChatProvider
import dev.wckdboy.autobot.providers.remote.ConnectionTestResult
import dev.wckdboy.autobot.providers.remote.ModelCache
import dev.wckdboy.autobot.providers.remote.ModelDataPolicies
import dev.wckdboy.autobot.providers.remote.ModelListResult
import dev.wckdboy.autobot.providers.remote.ProviderPreset
import dev.wckdboy.autobot.providers.remote.ProviderPresets
import dev.wckdboy.autobot.providers.remote.RemoteModel
import dev.wckdboy.autobot.providers.remote.SystemModels
import dev.wckdboy.autobot.providers.remote.ThinkingDefaults
import dev.wckdboy.autobot.providers.remote.ThinkingSupport
import dev.wckdboy.autobot.shared.mcp.McpHub
import dev.wckdboy.autobot.shared.mcp.McpServerSpec
import dev.wckdboy.autobot.shared.mcp.McpServerStatus
import dev.wckdboy.autobot.shared.memory.BridgedMemoryStore
import dev.wckdboy.autobot.shared.memory.Memory
import dev.wckdboy.autobot.shared.memory.MemoryStoreBridge
import dev.wckdboy.autobot.shared.memory.memoryPlugin
import dev.wckdboy.autobot.shared.vault.VaultHost
import dev.wckdboy.autobot.shared.vault.vaultPlugin
import dev.wckdboy.autobot.shared.search.WebSearchSource
import dev.wckdboy.autobot.shared.search.webSearchPlugin
import dev.wckdboy.autobot.shared.studio.StudioRunner
import dev.wckdboy.autobot.shared.studio.studioPlugin
import dev.wckdboy.autobot.core.network.AuditLog
import dev.wckdboy.autobot.shared.session.ReceiptProvider
import dev.wckdboy.autobot.shared.session.SessionProjection
import dev.wckdboy.autobot.shared.session.receiptsPlugin
import dev.wckdboy.autobot.shared.redaction.RedactionGateway
import dev.wckdboy.autobot.shared.cascade.CascadePolicy
import dev.wckdboy.autobot.shared.cascade.LocalFirstCascade
import dev.wckdboy.autobot.agent.core.loop.chooseCascade
import dev.wckdboy.autobot.agent.core.loop.restoreCascade
import dev.wckdboy.autobot.agent.core.loop.chooseRedaction
import dev.wckdboy.autobot.agent.core.loop.restoreRedaction
import dev.wckdboy.autobot.core.data.settings.AppSettings
import dev.wckdboy.autobot.shared.session.SessionProjector
import dev.wckdboy.autobot.shared.recipes.RecipeCodec
import dev.wckdboy.autobot.shared.recipes.RecipeCopy
import dev.wckdboy.autobot.shared.recipes.RecipeTrigger
import dev.wckdboy.autobot.shared.recipes.RecipeTurnResult
import dev.wckdboy.autobot.shared.recipes.RecipeTurns
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.concurrent.atomics.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okio.Path.Companion.toPath
import dev.wckdboy.autobot.shared.usage.BridgedUsageStore
import dev.wckdboy.autobot.shared.usage.UsageJson
import dev.wckdboy.autobot.shared.usage.UsageLedger
import dev.wckdboy.autobot.shared.usage.UsageListener
import dev.wckdboy.autobot.shared.usage.UsageMeter
import dev.wckdboy.autobot.shared.usage.UsagePlan
import dev.wckdboy.autobot.shared.usage.UsageProvider
import dev.wckdboy.autobot.shared.usage.UsageRecord
import dev.wckdboy.autobot.shared.usage.UsageStoreBridge

/**
 * A configured chat provider as the host stores it. [route] is `INHERIT`, `DIRECT`, `TOR` or
 * `SOCKS5`; [presetId] a `ProviderPresets` id (null for rows saved before presets were stored);
 * [modelsJson] its cached model list ([ModelCache.encode]), whose context windows the agent uses;
 * [helperModel] the model for subagents and summaries (null: the chat's own model).
 */
class ProviderSpec(
    val id: String,
    val kind: ProviderKind,
    val displayName: String,
    val baseUrl: String,
    val apiKey: String?,
    val defaultModel: String,
    val route: String = "INHERIT",
    val presetId: String? = null,
    val modelsJson: String? = null,
    val helperModel: String? = null,
) {
    val preset: ProviderPreset get() = ProviderPresets.resolve(presetId, kind, baseUrl)
}

/** The host's provider list (decrypting API keys on demand). */
interface ProviderDirectory {
    fun provider(id: String): ProviderSpec?
}

/**
 * Encrypted persistence of session logs. Each entry is one JSON line (a `LogEntry`); the host
 * must keep append order. Called on a background thread.
 */
interface SessionStoreBridge {
    fun load(sessionId: String): List<String>
    fun append(sessionId: String, entries: List<String>)
}

/** Decrypted image attachments for one model call (see [ImageAttachments]). */
interface ImageStoreBridge {
    fun openPlain(id: String): String?
    fun discard(path: String)
}

/** Receives a session's UI state as JSON ([SessionState]), throttled, on a background thread. */
fun interface SessionListener {
    fun onState(json: String)
}

/**
 * The efficient session stream ([AgentBridge.openStream]); both methods are called on one
 * background coroutine, in order, never concurrently.
 *
 * - [onTranscript] gets a [TranscriptState] JSON — `{"sessionId","revision","projection"}` — only
 *   when the projected transcript changed (log appended, a tool started or finished, an approval
 *   or question came or went). `revision` starts at 1 and grows by one per transcript sent.
 * - [onFrame] gets a [LiveFrame] JSON at most ~30 times a second while something changes: the
 *   streamed answer (as a text delta, see [LiveFrameStream]), running flag, approvals, question,
 *   settings. It is small (no tool outputs); `revision` names the transcript it goes with, which
 *   was always delivered before it.
 */
interface SessionStreamListener {
    fun onTranscript(json: String)

    fun onFrame(json: String)
}

/** The host's MCP bearer tokens (Keychain), read on a background thread when a server connects. */
fun interface McpTokenSource {
    fun token(serverId: String): String?
}

/** Receives the MCP servers' connection state. */
fun interface McpStatusListener {
    fun onStatus(servers: List<McpServerStatus>)
}

/** Receives the ids of sessions with a turn in progress. */
fun interface RunningListener {
    fun onRunning(sessionIds: List<String>)
}

@Serializable
class LiveState(
    val text: String,
    val reasoning: String,
    val toolNames: List<String>,
    val tokensPerSecond: Float?,
    /** Reasoning tokens so far, when the engine counts them (on-device). */
    val reasoningTokens: Int? = null,
    /** The reasoning closed (the answer streams now). */
    val reasoningDone: Boolean = false,
    /** "budget" or "loop" when the engine ended the reasoning early. */
    val reasoningStop: String? = null,
)

@Serializable
class ApprovalState(
    val callId: String,
    val toolName: String,
    val summary: String,
    val reason: String?,
    /** Private fragments the call would send (taint tracking); the card highlights them. */
    val privateData: List<String> = emptyList(),
)

@Serializable
class QuestionState(val callId: String, val question: String, val options: List<String>, val multiSelect: Boolean)

/**
 * [SessionStreamListener.onTranscript]: the transcript ([projection]) as of [revision].
 */
@Serializable
class TranscriptState(val sessionId: String, val revision: Long, val projection: SessionProjection)

/** A streamed call in a [LiveFrameStream]: its arguments are not sent, only how long they are so far. */
@Serializable
class LiveToolCall(val index: Int, val id: String?, val name: String?, val argumentsLength: Int)

/**
 * The answer being streamed, in a [LiveFrame]. [turn] / [step] identify the stream. Text and
 * reasoning come as deltas: [text] is the streamed text from offset [textFrom] on, [textLength]
 * the full length so far (likewise [reasoning] / [reasoningFrom] / [reasoningLength]).
 *
 * Client rule: [textFrom] `== 0` → replace; `== local length` → append; anything else (a frame
 * was lost) → call [AgentBridge.resync], whose next frame starts at 0. A client that coalesces
 * frames must concatenate the deltas of consecutive frames of the same stream (or keep the
 * first frame's offsets and append the later texts). With `textDeltas = false`
 * ([AgentBridge.openStream] overload) every frame carries the full text and `textFrom` is 0.
 */
@Serializable
class LiveFrameStream(
    val turn: Int,
    val step: Int,
    val textFrom: Int,
    val text: String,
    val textLength: Int,
    val reasoningFrom: Int,
    val reasoning: String,
    val reasoningLength: Int,
    val toolCalls: List<LiveToolCall>,
    val toolNames: List<String>,
    val tokensPerSecond: Float?,
    val reasoningTokens: Int? = null,
    val reasoningDone: Boolean = false,
    val reasoningStop: String? = null,
)

/**
 * [SessionStreamListener.onFrame]: everything on the chat screen except the transcript.
 * [revision] is the transcript revision in effect; [frame] counts frames from 1.
 */
@Serializable
class LiveFrame(
    val sessionId: String,
    val revision: Long,
    val frame: Long,
    val live: LiveFrameStream?,
    val running: Boolean,
    val approvals: List<ApprovalState>,
    val question: QuestionState?,
    val toolsEnabled: Boolean,
    val permission: String,
    val provider: String,
    val model: String,
    /** The chat's Think switch: OFF, AUTO or ON, with [thinkingBudget] for ON (null: the model's default). */
    val thinking: String = "AUTO",
    val thinkingBudget: Int? = null,
    /** Whether the Think chip shows for this model ([ThinkingSupport]): NONE, SWITCH or ALWAYS. */
    val thinkingSupport: String = "NONE",
    /** The redaction gateway for this chat's route: ON, OFF, or NONE (on-device or loopback: nothing to hide). */
    val redaction: String = "NONE",
    /** The local-first cascade policy of this chat (OFF, ASK, AUTO, NEVER), or NONE where it cannot apply (agent runs, on-device routes). */
    val cascade: String = "NONE",
)

/** A [SessionState] without its projection, which [AgentBridge.open] encodes only when it changes. */
@Serializable
internal class SessionFrame(
    val sessionId: String,
    val live: LiveState?,
    val running: Boolean,
    val approvals: List<ApprovalState>,
    val question: QuestionState?,
    val toolsEnabled: Boolean,
    val permission: String,
    val provider: String,
    val model: String,
    /** The chat's Think switch: OFF, AUTO or ON, with [thinkingBudget] for ON (null: the model's default). */
    val thinking: String = "AUTO",
    val thinkingBudget: Int? = null,
    /** Whether the Think chip shows for this model ([ThinkingSupport]): NONE, SWITCH or ALWAYS. */
    val thinkingSupport: String = "NONE",
    /** The redaction gateway for this chat's route: ON, OFF, or NONE (on-device or loopback: nothing to hide). */
    val redaction: String = "NONE",
    /** The local-first cascade policy of this chat (OFF, ASK, AUTO, NEVER), or NONE where it cannot apply (agent runs, on-device routes). */
    val cascade: String = "NONE",
)

/** Everything a chat screen renders, computed by the same code as on Android. */
@Serializable
class SessionState(
    val sessionId: String,
    val projection: SessionProjection,
    val live: LiveState?,
    val running: Boolean,
    val approvals: List<ApprovalState>,
    val question: QuestionState?,
    val toolsEnabled: Boolean,
    val permission: String,
    val provider: String,
    val model: String,
    /** The chat's Think switch: OFF, AUTO or ON, with [thinkingBudget] for ON (null: the model's default). */
    val thinking: String = "AUTO",
    val thinkingBudget: Int? = null,
    /** Whether the Think chip shows for this model ([ThinkingSupport]): NONE, SWITCH or ALWAYS. */
    val thinkingSupport: String = "NONE",
    /** The redaction gateway for this chat's route: ON, OFF, or NONE (on-device or loopback: nothing to hide). */
    val redaction: String = "NONE",
    /** The local-first cascade policy of this chat (OFF, ASK, AUTO, NEVER), or NONE where it cannot apply (agent runs, on-device routes). */
    val cascade: String = "NONE",
)

/**
 * The agent runtime for hosts that are not Kotlin (iOS): the same [Harness], plugins and tools
 * that Android's `AgentHost` wires up, behind plain callbacks and JSON strings. All methods
 * return immediately; work runs on the bridge's own coroutine scope.
 *
 * [audit] is the host's audit log (the one its [transport] records into): each turn's privacy
 * receipt lists the traffic it tied to that turn. Null leaves the receipts' network part unknown.
 *
 * [journalDir] holds the undo journal (copies of files the agent replaced); keep it next to the
 * workspace with the same file protection. Null turns undo off.
 *
 * Memory: at most [MAX_OPEN_AGENTS] idle agents stay open (each holds its whole log); the least
 * recently used idle one beyond that is closed and reopened from the store on its next use.
 * Agents that run a turn, wait on the user, have an open state stream ([open] / [openStream])
 * or are [retain]ed are never closed.
 *
 * Lifecycle: call [setForeground] when the app enters the background (`false`) and returns
 * (`true`); MCP list-change streams pause meanwhile.
 *
 * Providers: model adapters are built once per provider and reused across steps; call
 * [invalidateProviders] whenever the provider list, a provider's key or its cached model list
 * changes (they also expire after [ADAPTER_TTL_MS]).
 */
class AgentBridge(
    workspaceDir: String,
    private val providers: ProviderDirectory,
    private val transport: HttpTransport,
    private val local: LocalEngine,
    store: SessionStoreBridge,
    images: ImageStoreBridge?,
    mcpTokens: McpTokenSource,
    audit: AuditLog? = null,
    journalDir: String? = null,
) {
    // An exception escaping a launch must not reach Kotlin/Native's default handler, which
    // terminates the process: it is logged and only that job ends.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t -> println("AgentBridge: ${t::class.simpleName}: ${t.message}") },
    )
    val workspace = Workspace(workspaceDir.toPath())
    private val journal = journalDir?.let { WorkspaceJournal(workspace, it.toPath()) }
    val harness = Harness()
    private val contexts = WorkspaceContexts(workspace)
    private val sessions = BridgedSessionStore(store)
    private val mcp = McpHub(transport, { id -> mcpTokens.token(id) })
    private val attachments = images?.let { bridge ->
        object : ImageAttachments {
            override suspend fun openPlain(id: String): String? = withContext(IoDispatcher) { bridge.openPlain(id) }

            override fun discard(path: String) = bridge.discard(path)
        }
    }

    /** Counts every model call ([setUsage] adds the encrypted ledger behind it). */
    private val usage = UsageMeter()

    private val mutex = Mutex()
    /** Agents as they are created, for calls that arrive while [open] is still creating one. */
    private val created = MutableStateFlow<Map<String, Agent>>(emptyMap())
    /** Open agents, least recently used first ([used] moves one to the end). Guarded by [mutex]. */
    private val agents = LinkedHashMap<String, Agent>()

    /** [sessionId]'s open agent, now the most recently used. Call under [mutex]. */
    private fun used(sessionId: String): Agent? = agents.remove(sessionId)?.also { agents[sessionId] = it }
    private val watchers = HashMap<String, Job>()
    /** The route each session was opened with (or switched to), to reopen an evicted agent. */
    private val routes = HashMap<String, Pair<String, String>>()
    /** Hold counts: [retain] calls and open state streams. Held agents are never evicted. */
    private val holds = HashMap<String, Int>()
    private val holdLock = dev.wckdboy.autobot.agent.core.Lock()

    private class CachedAdapter(val at: Long, val adapter: dev.wckdboy.autobot.agent.core.llm.LlmAdapter)

    private val adapterLock = dev.wckdboy.autobot.agent.core.Lock()
    private val adapterCache = HashMap<String, CachedAdapter>()
    private var adapterBuilds = 0
    private val running = MutableStateFlow<Set<String>>(emptySet())

    @kotlin.concurrent.Volatile
    private var redactionDefault = AppSettings().redactCloud

    private val redaction = RedactionGateway(
        // An unknown provider counts as cloud: when in doubt, hide.
        cloud = { id -> withContext(IoDispatcher) { providers.provider(id) }?.let { RedactionGateway.isCloud(it.kind, it.baseUrl) } ?: true },
        enabled = { redactionDefault },
    )

    @kotlin.concurrent.Volatile
    private var cascadeDefault = CascadePolicy.DEFAULT

    /** Paired nodes' signed receipts for this host's turns, and the turns kept private on a node. */
    private val nodeReceipts = NodeReceiptLog()
    private val privateTurns = PrivateTurns()

    private val cascade = LocalFirstCascade(
        defaultPolicy = { cascadeDefault },
        onDevice = { quotaFallback.onDevice },
        // An unknown provider counts as cloud, as for redaction.
        cloud = { id -> withContext(IoDispatcher) { providers.provider(id) }?.let { RedactionGateway.isCloud(it.kind, it.baseUrl) } ?: true },
        // "Phone first" on the user's own node: follow-ups go there and stay there (private).
        owned = { id -> withContext(IoDispatcher) { providers.provider(id) }?.kind == ProviderKind.INVINCIBLE_NODE },
        privateTurns = privateTurns,
    )
    private val interactions = MutableStateFlow<List<Pending>>(emptyList())

    private sealed interface Pending {
        val sessionId: String
        val callId: String

        class Approval(
            override val sessionId: String,
            override val callId: String,
            val state: ApprovalState,
            val answer: CompletableDeferred<ApprovalOutcome>,
        ) : Pending

        class Question(
            override val sessionId: String,
            override val callId: String,
            val question: UserQuestion,
            val answer: CompletableDeferred<List<String>?>,
        ) : Pending
    }

    init {
        harness.llm.setResolver { id -> adapter(id) }
        val root = harness.root
        root.plugin(
            quotaFallbackPlugin(
                enabled = { quotaFallback.enabled },
                onDevice = { quotaFallback.onDevice },
                eligible = { cfg -> providers.provider(cfg.provider)?.let { ModelDataPolicies.autoPickable(it.preset.id, cfg.model) } ?: true },
            ),
        )
        root.plugin(
            receiptsPlugin(audit, usage, nodeReceipts) { id ->
                withContext(IoDispatcher) { providers.provider(id) }?.let {
                    ReceiptProvider(it.displayName, it.kind == ProviderKind.LOCAL, it.preset.id, it.preset.privacyNote)
                }
            },
        )
        // Redaction gateway: personal data becomes placeholders before a cloud request.
        root.plugin(redaction.plugin)
        // Local-first cascade (Settings, off by default): this phone answers plain chats first.
        root.plugin(cascade.plugin)
        root.plugin(toolsPlugin("workspace-files", *FileTools(workspace, journal).all.toTypedArray()))
        root.plugin(toolsPlugin("web", WebFetchTool(TransportWebFetcher(transport))))
        root.plugin(contexts.plugin)
        root.plugin(mcp.plugin)
        root.intercept(Hooks.ApprovalRequest) { request, _ ->
            val pending = Pending.Approval(
                request.agent.id,
                request.callId,
                ApprovalState(request.callId, request.toolName, request.summary, request.reason, request.privateData),
                CompletableDeferred(),
            )
            await(pending) { pending.answer.await() }
        }
        root.intercept(Hooks.UserQuestion) { request, _ ->
            val pending = Pending.Question(request.agent.id, request.callId, request.question, CompletableDeferred())
            await(pending) { pending.answer.await() }
        }
    }

    /**
     * The model adapter for provider [id], built once (directory lookup, Keychain key, model list
     * decode) and reused by every step until [invalidateProviders] or [ADAPTER_TTL_MS].
     */
    private fun adapter(id: String): dev.wckdboy.autobot.agent.core.llm.LlmAdapter? {
        val now = monotonicNanos()
        adapterLock.withLock { adapterCache[id] }?.takeIf { now - it.at < ADAPTER_TTL_MS * 1_000_000 }?.let { return it.adapter }
        val spec = providers.provider(id) ?: return null
        val built = usage.wrap(
            ProviderLlmAdapter(
                spec.id, chatProvider(spec), spec.kind, attachments, spec.preset::echoesReasoning, ModelCache.decode(spec.modelsJson),
                helperModel = spec.helperModel?.takeIf { m -> m.isNotBlank() },
            ),
            UsageProvider(spec.id, spec.displayName, spec.preset.id, spec.kind == ProviderKind.LOCAL),
        )
        adapterLock.withLock {
            adapterBuilds++
            adapterCache[id] = CachedAdapter(now, built)
        }
        return built
    }

    /**
     * Drops the cached model adapters: call it when providers were added, edited (key, URL, route,
     * helper model) or removed, when their cached model lists changed, or when the network policy
     * changed. Cheap; the next step rebuilds what it needs.
     */
    fun invalidateProviders() {
        adapterLock.withLock { adapterCache.clear() }
    }

    /** Model adapters built so far (diagnostics and tests). */
    internal val adapterBuildCount: Int get() = adapterLock.withLock { adapterBuilds }

    /**
     * App lifecycle: `false` when the app goes to the background, `true` when it is back. In the
     * background no MCP `subscriptions/listen` stream stays open or reconnects.
     */
    fun setForeground(foreground: Boolean) = mcp.setForeground(foreground)

    private suspend fun <T> await(pending: Pending, answer: suspend () -> T): T {
        interactions.update { it + pending }
        try {
            return answer()
        } finally {
            interactions.update { list -> list.filterNot { it === pending } }
        }
    }

    /**
     * How this host reaches a paired Invincible node: set by hosts that can open node
     * connections. Without it a "My node" provider says it is not available here, and its pairing
     * secret never goes anywhere.
     */
    var nodeConnector: (suspend (addresses: List<String>, route: String) -> NodeLink)? = null
    var nodeCrypto: NodeCrypto? = null

    /** A host without coroutines (iOS) brings its crypto and connections this way. */
    fun setNodeHost(crypto: HostNodeCrypto, connector: HostNodeConnector) {
        nodeCrypto = nodeCrypto(crypto)
        nodeConnector = nodeConnector(connector)
    }

    /**
     * Pairs with the node in [code] (an `invincible://pair?…` link) as [deviceName], over the
     * connector (route `INHERIT`). [callback] gets the pairing to store as the provider's secret,
     * the node's name and key fingerprint, or an error.
     */
    fun pairNode(code: String, deviceName: String, callback: (secret: String?, node: String?, fingerprint: String?, error: String?) -> Unit) {
        val connector = nodeConnector
        val crypto = nodeCrypto
        val parsed = PairingCode.parse(code.trim())
        when {
            connector == null || crypto == null -> return callback(null, null, null, "My node is not available on this device yet")
            parsed == null -> return callback(null, null, null, "That is not an Invincible pairing code (invincible://pair?…)")
        }
        launchAnswering(
            callback = { r: List<String?> -> callback(r[0], r[1], r[2], r[3]) },
            failed = { listOf(null, null, null, it) },
        ) {
            val paired = PairedNode.pair(crypto!!, parsed!!, deviceName) { addresses -> connector!!(addresses, "INHERIT") }
            listOf(paired.toSecret(), paired.name, paired.fingerprint, null)
        }
    }

    /** The key fingerprint of a pairing code, to compare with what `invincible pair` printed; null when it is not one. */
    fun pairingFingerprint(code: String): String? = PairingCode.parse(code.trim())?.let { fingerprint(it.nodeKey) }

    /*
     * The Autobot Agent on a paired node (Invincible's spec/agent.md) for hosts without coroutines:
     * every call answers once through its callback; a turn's events arrive one by one.
     */

    /** The agent on [providerId]'s node, with this device's conversation id there; null when it is not a usable pairing. */
    private suspend fun nodeAgent(providerId: String): Pair<NodeAgent, String>? = nodeAgentAndPairing(providerId)?.let { (agent, paired) ->
        agent to NodeAgent.sessionFor(paired.deviceName)
    }

    private suspend fun nodeAgentAndPairing(providerId: String): Pair<NodeAgent, PairedNode>? {
        val spec = withContext(IoDispatcher) { providers.provider(providerId) } ?: return null
        val connector = nodeConnector ?: return null
        val crypto = nodeCrypto ?: return null
        if (spec.kind != ProviderKind.INVINCIBLE_NODE) return null
        val paired = spec.apiKey?.let(PairedNode::fromSecret) ?: return null
        val transport = paired.transport(crypto) { addresses -> connector(addresses, spec.route) }
        return NodeAgent(transport, "${spec.displayName} · agent") to paired
    }

    private suspend fun requireNodeAgent(providerId: String): Pair<NodeAgent, String> =
        nodeAgent(providerId) ?: error("This node's pairing is unreadable here; pair it again in Providers.")

    /**
     * Opens the agent: this device's conversation (or [session], one an approval waits in), the
     * agent's settings and the conversation so far.
     */
    fun nodeAgentOpen(providerId: String, session: String?, callback: (NodeAgentOpened?, String?) -> Unit) = launchAnswering(
        callback = { r: Pair<NodeAgentOpened?, String?> -> callback(r.first, r.second) },
        failed = { null to it },
    ) {
        val (agent, own) = requireNodeAgent(providerId)
        val id = session ?: own
        // A conversation not started yet (a new chat with a bot) has no history: empty, not an error.
        val history = try {
            agent.history(id)
        } catch (e: dev.wckdboy.autobot.shared.invincible.NodeAgentException) {
            if (e.status == 404) emptyList() else throw e
        }
        NodeAgentOpened(id, agent.info(), history) to null
    }

    /** The approvals waiting now, oldest first; null with no error from a node too old to list them. */
    fun nodeAgentApprovals(providerId: String, callback: (List<PendingApproval>?, String?) -> Unit) = launchAnswering(
        callback = { r: Pair<List<PendingApproval>?, String?> -> callback(r.first, r.second) },
        failed = { null to it },
    ) { requireNodeAgent(providerId).first.approvals() to null }

    /** Answers an approval "no, do this instead: [text]"; false when it was no longer waiting. */
    fun nodeAgentInstead(providerId: String, id: String, text: String, callback: (Boolean) -> Unit) = launchAnswering(
        callback = callback,
        failed = { false },
    ) { requireNodeAgent(providerId).first.answerInstead(id, text) }

    /**
     * Runs a turn; [listener] gets its events, the last being an answer or a failure. Cancelling
     * stops listening, not the turn. [bot]: the bot a new session belongs to (bots mode); null for
     * the default one.
     */
    fun nodeAgentTurn(providerId: String, session: String, text: String, bot: String?, listener: (AgentEvent) -> Unit): Cancellable {
        val job = scope.launch {
            try {
                val (agent, _) = requireNodeAgent(providerId)
                agent.turn(session, text, bot).collect { listener(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                listener(AgentEvent.Failed(e.message ?: "The node did not answer"))
            }
        }
        return Cancellable { job.cancel() }
    }

    /** Answers an approval; false when it was no longer waiting. */
    fun nodeAgentAnswer(providerId: String, id: String, answer: ApprovalAnswer, callback: (Boolean) -> Unit) = launchAnswering(
        callback = callback,
        failed = { false },
    ) { requireNodeAgent(providerId).first.answer(id, answer) }

    /** Starts the conversation over; null, or why it could not. */
    fun nodeAgentReset(providerId: String, session: String, callback: (String?) -> Unit) = launchAnswering(
        callback = callback,
        failed = { it },
    ) {
        requireNodeAgent(providerId).first.reset(session)
        null
    }

    /**
     * The node's bots (bots mode): [NodeBotsState.supported] false from a node too old to have
     * them, [NodeBotsState.problem] when it did not answer.
     */
    fun nodeBots(providerId: String, callback: (NodeBotsState) -> Unit) = launchAnswering(
        callback = callback,
        failed = { NodeBotsState(problem = it) },
    ) { NodeBotsRepository(requireNodeAgent(providerId).first).refresh() }

    /** Switches bot [name] on or off; null, or why it did not work. */
    fun nodeBotSetEnabled(providerId: String, name: String, enabled: Boolean, callback: (String?) -> Unit) = launchAnswering(
        callback = callback,
        failed = { it },
    ) {
        requireNodeAgent(providerId).first.setBotEnabled(name, enabled)
        null
    }

    /** The node's conversations, or only [bot]'s (bots mode), newest first. */
    fun nodeAgentSessions(providerId: String, bot: String?, callback: (List<AgentSession>?, String?) -> Unit) = launchAnswering(
        callback = { r: Pair<List<AgentSession>?, String?> -> callback(r.first, r.second) },
        failed = { null to it },
    ) { requireNodeAgent(providerId).first.sessions(bot).sortedByDescending { it.modified } to null }

    /** A new conversation id for a chat from this device with [bot]; null when the pairing is unreadable. */
    fun nodeAgentNewSession(providerId: String, bot: String, now: Long, callback: (String?) -> Unit) = launchAnswering(
        callback = callback,
        failed = { null },
    ) { nodeAgentAndPairing(providerId)?.let { (_, paired) -> NodeBots.newSession(paired.deviceName, bot, now) } }

    fun nodeAgentSchedules(providerId: String, callback: (List<AgentSchedule>?, String?) -> Unit) = launchAnswering(
        callback = { r: Pair<List<AgentSchedule>?, String?> -> callback(r.first, r.second) },
        failed = { null to it },
    ) { requireNodeAgent(providerId).first.schedules() to null }

    /** Adds a scheduled task; null, or why not (a schedule that does not parse, …). */
    fun nodeAgentAddSchedule(providerId: String, schedule: String, task: String, telegram: Boolean, callback: (String?) -> Unit) = launchAnswering(
        callback = callback,
        failed = { it },
    ) {
        requireNodeAgent(providerId).first.addSchedule(schedule, task, telegram)
        null
    }

    fun nodeAgentRemoveSchedule(providerId: String, id: String, callback: (String?) -> Unit) = launchAnswering(
        callback = callback,
        failed = { it },
    ) {
        requireNodeAgent(providerId).first.removeSchedule(id)
        null
    }

    /**
     * What the node and its agent did and know (Invincible's spec/audit.md): its requests, each
     * receipt checked against the key pinned at pairing; the agent's actions, memory and skills.
     */
    fun nodeActivity(providerId: String, callback: (NodeActivity?, String?) -> Unit) = launchAnswering(
        callback = { r: Pair<NodeActivity?, String?> -> callback(r.first, r.second) },
        failed = { null to it },
    ) {
        val (agent, paired) = nodeAgentAndPairing(providerId) ?: error("This node's pairing is unreadable here; pair it again in Providers.")
        agent.activity(nodeCrypto!!, paired.name, paired.receiptKey) to null
    }

    /**
     * "While you were away": what [providerId]'s node did after [since] (Unix seconds, from
     * [NodeDigests.since]) and what waits for the owner now. [recipesJson] are the host's recipes
     * ([RecipeCodec] JSON), for the result chats of those that ran there.
     */
    fun nodeDigest(providerId: String, since: Long, recipesJson: List<String>, callback: (NodeDigest?, String?) -> Unit) = launchAnswering(
        callback = { r: Pair<NodeDigest?, String?> -> callback(r.first, r.second) },
        failed = { null to it },
    ) {
        val (agent, paired) = nodeAgentAndPairing(providerId) ?: error("This node's pairing is unreadable here; pair it again in Providers.")
        val recipes = recipesJson.mapNotNull(NodeDigests::recipeJson)
        NodeDigests.fetch(agent, nodeCrypto!!, paired.name, paired.receiptKey, providerId, since, recipes, exceptDevice = paired.deviceName) to null
    }

    /*
     * Recipes on a paired node (Invincible's spec/agent.md, "Recipes from Autobot"), for hosts
     * that keep recipes themselves: JSON in, JSON out ([RecipeCodec]). The host stores what comes
     * back and creates the result chats; receipts are checked and chats written here.
     */

    /** [providerId]'s node as recipes use it; null when it is not a usable pairing. */
    private suspend fun nodeRecipeTarget(providerId: String): NodeRecipeTarget? {
        val spec = withContext(IoDispatcher) { providers.provider(providerId) } ?: return null
        val connector = nodeConnector ?: return null
        val crypto = nodeCrypto ?: return null
        if (spec.kind != ProviderKind.INVINCIBLE_NODE) return null
        val paired = spec.apiKey?.let(PairedNode::fromSecret) ?: return null
        val transport = paired.transport(crypto) { addresses -> connector(addresses, spec.route) }
        return NodeRecipeTarget(NodeAgent(transport, "${spec.displayName} · recipes"), paired.name) { id ->
            NodeReceiptLog.fetch(crypto, transport, paired.name, paired.receiptKey, id)
        }
    }

    private suspend fun requireNodeRecipeTarget(recipe: Recipe): NodeRecipeTarget =
        recipe.runsOn?.let { nodeRecipeTarget(it) } ?: error("The node this recipe runs on is no longer paired.")

    /** Sends [recipeJson] to its node; the recipe JSON to store (it records what was sent), or why not. */
    fun nodeRecipePush(recipeJson: String, callback: (String?, String?) -> Unit) = launchAnswering(
        callback = { r: Pair<String?, String?> -> callback(r.first, r.second) },
        failed = { null to it },
    ) {
        val recipe = RecipeCodec.decode(recipeJson) ?: error("This recipe cannot be read")
        val zone = kotlinx.datetime.TimeZone.currentSystemDefault()
        NodeRecipes.push(requireNodeRecipeTarget(recipe).agent, recipe, zone, nowMillis())
        RecipeCodec.encode(recipe.copy(state = recipe.state.copy(pushed = NodeRecipes.pushed(recipe, zone, nowMillis())))) to null
    }

    /** Takes [recipeJson] off the node it ran on; null, or why not. */
    fun nodeRecipeRemove(recipeJson: String, callback: (String?) -> Unit) = launchAnswering(callback = callback, failed = { it }) {
        val recipe = RecipeCodec.decode(recipeJson) ?: error("This recipe cannot be read")
        NodeRecipes.remove(requireNodeRecipeTarget(recipe).agent, recipe)
        null
    }

    /** "Run now" on the node; null, or why not. */
    fun nodeRecipeRunNow(recipeJson: String, callback: (String?) -> Unit) = launchAnswering(callback = callback, failed = { it }) {
        val recipe = RecipeCodec.decode(recipeJson) ?: error("This recipe cannot be read")
        if (NodeRecipes.runNow(requireNodeRecipeTarget(recipe).agent, recipe)) null else "It is running on the node already."
    }

    /** The node's runs of [recipeJson] the phone has not collected, oldest first, as JSON ([NodeRun]); or why not. */
    fun nodeRecipeRuns(recipeJson: String, callback: (List<String>?, String?) -> Unit) = launchAnswering(
        callback = { r: Pair<List<String>?, String?> -> callback(r.first, r.second) },
        failed = { null to it },
    ) {
        val recipe = RecipeCodec.decode(recipeJson) ?: error("This recipe cannot be read")
        NodeRecipes.newRuns(requireNodeRecipeTarget(recipe).agent, recipe).map { NodeRecipes.encodeRun(it) } to null
    }

    /**
     * Writes run [runJson] of [recipeJson] into chat [chatId] (made by the host in the Recipes
     * folder) with the node's receipts checked, and answers the run's record JSON.
     */
    fun nodeRecipeDeliver(recipeJson: String, runJson: String, chatId: String, callback: (String?) -> Unit) = launchAnswering(
        callback = callback,
        failed = { null },
    ) {
        val recipe = RecipeCodec.decode(recipeJson) ?: error("This recipe cannot be read")
        val run = NodeRecipes.decodeRun(runJson) ?: error("not a run")
        val target = requireNodeRecipeTarget(recipe)
        val receipts = run.receipts.map { id ->
            try {
                target.receipt(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dev.wckdboy.autobot.agent.core.session.ReceiptNode(target.node, id, verified = false, missing = e.message ?: "could not fetch it")
            }
        }
        val log = SessionLog.open(chatId, sessions)
        log.appendAll(NodeRecipes.transcript(recipe, run, receipts.firstNotNullOfOrNull { it.model }, receipts))
        RecipeCodec.encodeRecord(NodeRecipes.record(run, chatId, receipts))
    }

    /** Answers a question a team member on the node asked (board task [id]); null, or why not. */
    fun nodeBoardAnswer(providerId: String, id: String, text: String, callback: (String?) -> Unit) = launchAnswering(
        callback = callback,
        failed = { it },
    ) {
        if (requireNodeAgent(providerId).first.answerBoard(id, text)) null else "That question was already answered."
    }

    /** Makes the node's agent forget a fact; null, or why not. */
    fun nodeAgentForget(providerId: String, id: Long, callback: (String?) -> Unit) = launchAnswering(
        callback = callback,
        failed = { it },
    ) {
        if (requireNodeAgent(providerId).first.forget(id)) null else "The agent no longer remembers that."
    }

    /** The chat client for a stored provider: the on-device engine, a paired node, or the host's HTTP transport. */
    fun chatProvider(spec: ProviderSpec): ChatProvider = when (spec.kind) {
        ProviderKind.LOCAL -> LocalEngineChatProvider(local)
        ProviderKind.INVINCIBLE_NODE -> {
            val connector = nodeConnector
            val crypto = nodeCrypto
            if (connector == null || crypto == null) {
                UnavailableChatProvider("My node is not available on this device yet")
            } else {
                NodeProviders.chatProvider(crypto, spec.apiKey, spec.displayName, spec.preset, nodeReceipts, privateTurns) { addresses -> connector(addresses, spec.route) }
            }
        }
        else -> TransportChatProvider(spec.baseUrl, spec.apiKey, transport, spec.displayName, spec.route, spec.preset)
    }

    /**
     * Providers screen "test connection": [callback] gets success, a summary (model count and the
     * first ids, or the error) and, on success, the model list to cache ([ModelCache] JSON).
     */
    fun testConnection(spec: ProviderSpec, callback: (Boolean, String, String?) -> Unit) = launchAnswering(
        callback = { (ok, message, models): Triple<Boolean, String, String?> -> callback(ok, message, models) },
        failed = { Triple(false, it, null) },
    ) {
        when (val result = chatProvider(spec).testConnection()) {
            is ConnectionTestResult.Success -> Triple(true, result.summary(), cache(result.models, spec))
            is ConnectionTestResult.Failure -> Triple(false, result.message, null)
        }
    }

    /** The configured MCP servers; sessions pick up enabled servers' tools at their next turn. */
    fun setMcpServers(servers: List<McpServerSpec>) = mcp.update(servers)

    fun observeMcpStatus(listener: McpStatusListener): Cancellable {
        val job = scope.launch { mcp.status.collect { listener.onStatus(it) } }
        return Cancellable { job.cancel() }
    }

    /** MCP "test connection" (initialize + tools/list); [callback] gets the tool count or an error message. */
    fun testMcpServer(spec: McpServerSpec, token: String?, callback: (Boolean, String) -> Unit) = launchAnswering(
        callback = { (ok, message): Pair<Boolean, String> -> callback(ok, message) },
        failed = { false to it },
    ) {
        McpHub.test(transport, spec, token).fold({ true to it.toString() }, { false to McpHub.describe(it) })
    }

    /** Fetches the provider's chat models; [callback] gets the [ModelCache] JSON or an error message. */
    fun listModels(spec: ProviderSpec, callback: (String?, String?) -> Unit) = launchAnswering(
        callback = { (models, error): Pair<String?, String?> -> callback(models, error) },
        failed = { null to it },
    ) {
        when (val result = chatProvider(spec).listModels()) {
            is ModelListResult.Success -> cache(result.models, spec) to null
            is ModelListResult.Failure -> null to result.message
        }
    }

    /**
     * Runs [block] on the bridge scope and hands its result to [callback] exactly once. When the
     * block fails, is cancelled, or never starts because the bridge was shut down, [callback]
     * gets [failed] with a message instead, so a host awaiting it (a Swift continuation) always
     * resumes.
     */
    private fun <R> launchAnswering(callback: (R) -> Unit, failed: (String) -> R, block: suspend () -> R) {
        val answered = AtomicBoolean(false)
        fun answer(result: R) {
            if (answered.compareAndSet(expectedValue = false, newValue = true)) callback(result)
        }
        scope.launch { answer(block()) }.invokeOnCompletion { cause ->
            when (cause) {
                null -> Unit
                is CancellationException -> answer(failed("Cancelled"))
                else -> answer(failed(cause.message ?: (cause::class.simpleName ?: "error")))
            }
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun cache(models: List<RemoteModel>, spec: ProviderSpec): String? =
        models.takeIf { it.isNotEmpty() && spec.kind != ProviderKind.LOCAL }?.let { ModelCache(Clock.System.now().toEpochMilliseconds(), it).encode() }

    private suspend fun agent(sessionId: String, providerId: String, model: String): Agent = mutex.withLock {
        used(sessionId)?.let { return it }
        routes.getOrPut(sessionId) { providerId to model }
        val agent = harness.openAgent(
            sessionId = sessionId,
            store = sessions,
            config = AgentConfig(LlmCallConfig(provider = providerId, model = model)),
            scope = scope,
        )
        agent.log.latest<SessionEvent.PermissionPresetChanged>()?.let { change ->
            PermissionPreset.fromLabel(change.preset)?.let { agent.setPermission(it) }
        }
        agent.log.latest<SessionEvent.AgentSettingsChanged>()?.let { s -> agent.updateConfig { it.copy(toolsEnabled = s.toolsEnabled) } }
        agent.restoreThinking(ThinkingDefaults.get(thinkingDefaults, providerId, model))
        agent.restoreRedaction()
        agent.restoreCascade()
        watchers[sessionId] = scope.launch {
            agent.status.collect { status ->
                running.update { if (status == AgentStatus.RUNNING) it + sessionId else it - sessionId }
            }
        }
        agents[sessionId] = agent
        created.update { it + (sessionId to agent) }
        evictIdle()
        agent
    }

    /**
     * Keeps [sessionId]'s agent open (never evicted) until a matching [release]. An open state
     * stream ([open] / [openStream]) holds its session the same way while it runs.
     */
    fun retain(sessionId: String) = holdLock.withLock {
        holds[sessionId] = (holds[sessionId] ?: 0) + 1
    }

    /** Undoes one [retain]; the agent may then be closed once it is idle and others are newer. */
    fun release(sessionId: String) {
        val released = holdLock.withLock {
            val n = (holds[sessionId] ?: return) - 1
            if (n <= 0) holds.remove(sessionId) else holds[sessionId] = n
            n <= 0
        }
        if (released) scope.launch { mutex.withLock { evictIdle() } }
    }

    /** Session ids of the agents currently open (diagnostics and tests). */
    fun openSessions(): List<String> = created.value.keys.toList()

    /** Closes the least recently used idle agents beyond [MAX_OPEN_AGENTS]. Call under [mutex]. */
    private fun evictIdle() {
        val waiting = interactions.value.mapTo(HashSet()) { it.sessionId }
        val held = holdLock.withLock { holds.keys.toSet() }
        val busy = agents.filterValues { it.status.value != AgentStatus.IDLE }.keys + waiting + held
        var excess = agents.size - MAX_OPEN_AGENTS
        if (excess <= 0) return
        val idle = mutableListOf<String>()
        for (id in agents.keys) {
            if (excess == 0) break
            if (id in busy) continue
            idle += id
            excess--
        }
        for (id in idle) {
            val agent = agents.remove(id) ?: continue
            watchers.remove(id)?.cancel()
            created.update { it - id }
            running.update { it - id }
            scope.launch { agent.dispose() }
        }
    }

    /** The transcript inputs of one session: projected incrementally, re-projected only on change. */
    private class Transcript(val projection: SessionProjection, val approvals: List<ApprovalState>, val question: QuestionState?)

    private fun transcript(sessionId: String, a: Agent): kotlinx.coroutines.flow.Flow<Transcript> {
        val projector = SessionProjector { name, args -> describe(a, name, args) }
        var last: SessionProjection? = null
        // Only this session's approvals and questions: another chat's prompt doesn't re-project this one.
        val mine = interactions.map { list -> list.filter { it.sessionId == sessionId } }.distinctUntilChanged()
        return combine(a.log.entries, a.runningTools, mine) { entries, runningTools, pending ->
            val approvals = pending.filterIsInstance<Pending.Approval>().map { it.state }
            val question = pending.filterIsInstance<Pending.Question>().firstOrNull()?.let {
                QuestionState(it.callId, it.question.question, it.question.options, it.question.multiSelect)
            }
            val projection = try {
                projector.project(entries, approvals.mapTo(HashSet()) { it.callId }, runningTools)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Never lose the screen over one row: keep showing the last good transcript.
                last ?: SessionProjection(emptyList(), emptyList(), null, null)
            }
            last = projection
            Transcript(projection, approvals, question)
        }
    }

    /** Runs [block] with [sessionId] held (not evictable) on the bridge scope; the handle cancels it. */
    private fun holding(sessionId: String, block: suspend () -> Unit): Cancellable {
        retain(sessionId)
        val job = scope.launch { block() }
        job.invokeOnCompletion { release(sessionId) }
        return Cancellable { job.cancel() }
    }

    /**
     * Opens [sessionId] (with [providerId] / [model] for a new session) and streams its
     * [SessionState] to [listener] until the returned handle is cancelled. Every state carries the
     * whole transcript; prefer [openStream], which sends it only when it changes.
     */
    fun open(sessionId: String, providerId: String, model: String, listener: SessionListener): Cancellable = holding(sessionId) {
        val a = agent(sessionId, providerId, model)
        var encoded: Pair<SessionProjection, EncodedProjection>? = null
        combine(transcript(sessionId, a), a.live, a.status, a.config, a.permission) { t, live, status, config, permission ->
            val projection = encoded?.takeIf { it.first === t.projection }?.second
                ?: EncodedProjection(t.projection).also { encoded = t.projection to it }
            projection.splice(
                SessionFrame(
                    sessionId = sessionId,
                    live = live?.toState(),
                    running = status == AgentStatus.RUNNING,
                    approvals = t.approvals,
                    question = t.question,
                    toolsEnabled = config.toolsEnabled,
                    permission = permission.label,
                    provider = config.llm.provider,
                    model = config.llm.model,
                    thinking = (config.llm.thinking ?: ThinkingSetting.AUTO).mode.name,
                    thinkingBudget = config.llm.thinking?.budget,
                    thinkingSupport = thinkingSupport(config.llm.provider, config.llm.model).name,
                    redaction = redactionState(config),
                    cascade = cascadeState(config),
                ),
            )
        }.conflate().collect { json ->
            listener.onState(json)
            delay(FRAME_MS)
        }
    }

    /**
     * Opens [sessionId] like [open], streaming the transcript and live frames separately to
     * [listener] (see [SessionStreamListener], [TranscriptState], [LiveFrame]): the transcript
     * (which can hold megabytes of tool output) is encoded and sent only when it changes, the
     * live frame (text deltas) at most ~30 times a second. Until the handle is cancelled the
     * session is held open (never evicted).
     */
    fun openStream(sessionId: String, providerId: String, model: String, listener: SessionStreamListener): Cancellable =
        openStream(sessionId, providerId, model, listener, true)

    /** [openStream]; with [textDeltas] false every frame carries the full streamed text. */
    fun openStream(sessionId: String, providerId: String, model: String, listener: SessionStreamListener, textDeltas: Boolean): Cancellable =
        holding(sessionId) {
            val a = agent(sessionId, providerId, model)
            val resyncs = resyncFlow(sessionId)
            var sentProjection: SessionProjection? = null
            var revision = 0L
            var frames = 0L
            var lastFrame: String? = null
            // Delivered text/reasoning length of the current stream (turn, step).
            var stream: Pair<Int, Int>? = null
            var textSent = 0
            var reasoningSent = 0
            var seenResync = resyncs.value
            combine(transcript(sessionId, a), a.live, a.status, a.config, a.permission, resyncs) { values -> values }
                .conflate()
                .collect { values ->
                    val t = values[0] as Transcript
                    val live = values[1] as LiveStream?
                    val status = values[2] as AgentStatus
                    val config = values[3] as AgentConfig
                    val permission = values[4] as PermissionPreset
                    val resync = values[5] as Int
                    val full = resync != seenResync
                    seenResync = resync
                    // A new projection instance with the same rows (e.g. a step/start event) is not sent again.
                    if (full || (t.projection !== sentProjection && t.projection != sentProjection)) {
                        revision++
                        sentProjection = t.projection
                        listener.onTranscript(BridgeJson.encodeToString(TranscriptState.serializer(), TranscriptState(sessionId, revision, t.projection)))
                    }
                    val liveFrame = live?.let { l ->
                        val key = l.turn to l.step
                        if (full || !textDeltas || key != stream || l.text.length < textSent || l.reasoning.length < reasoningSent) {
                            stream = key
                            textSent = 0
                            reasoningSent = 0
                        }
                        l.toFrame(textSent, reasoningSent).also {
                            textSent = l.text.length
                            reasoningSent = l.reasoning.length
                        }
                    }
                    if (live == null) stream = null
                    frames++
                    val frame = LiveFrame(
                        sessionId = sessionId,
                        revision = revision,
                        frame = frames,
                        live = liveFrame,
                        running = status == AgentStatus.RUNNING,
                        approvals = t.approvals,
                        question = t.question,
                        toolsEnabled = config.toolsEnabled,
                        permission = permission.label,
                        provider = config.llm.provider,
                        model = config.llm.model,
                        thinking = (config.llm.thinking ?: ThinkingSetting.AUTO).mode.name,
                        thinkingBudget = config.llm.thinking?.budget,
                        thinkingSupport = thinkingSupport(config.llm.provider, config.llm.model).name,
                        redaction = redactionState(config),
                        cascade = cascadeState(config),
                    )
                    // Nothing new (e.g. a tick with an empty delta): no frame.
                    val signature = BridgeJson.encodeToString(LiveFrame.serializer(), frame.withFrame(0))
                    if (signature == lastFrame && !full) {
                        frames--
                    } else {
                        lastFrame = signature
                        listener.onFrame(BridgeJson.encodeToString(LiveFrame.serializer(), frame))
                    }
                    delay(FRAME_MS)
                }
        }

    private fun LiveFrame.withFrame(n: Long) =
        LiveFrame(sessionId, revision, n, live, running, approvals, question, toolsEnabled, permission, provider, model, thinking, thinkingBudget, thinkingSupport, redaction, cascade)

    private val resyncs = HashMap<String, MutableStateFlow<Int>>()

    private fun resyncFlow(sessionId: String): MutableStateFlow<Int> = holdLock.withLock { resyncs.getOrPut(sessionId) { MutableStateFlow(0) } }

    /**
     * Asks every [openStream] of [sessionId] to send the transcript again (new revision) and a
     * frame with the full streamed text (`textFrom` 0): for a client that lost a frame.
     */
    fun resync(sessionId: String) {
        resyncFlow(sessionId).update { it + 1 }
    }

    /** A projection encoded once and spliced into every frame until the transcript changes. */
    private class EncodedProjection(projection: SessionProjection) {
        private val json = BridgeJson.encodeToString(SessionProjection.serializer(), projection)

        /** The [SessionState] JSON: [frame]'s fields plus `projection`. */
        fun splice(frame: SessionFrame): String =
            "{\"projection\":" + json + "," + BridgeJson.encodeToString(SessionFrame.serializer(), frame).removePrefix("{")
    }

    /** Sessions with a turn in progress, for list indicators. */
    fun observeRunning(listener: RunningListener): Cancellable {
        val job = scope.launch { running.collect { listener.onRunning(it.toList()) } }
        return Cancellable { job.cancel() }
    }

    /** Sends a prompt (with optional attached image ids): a new turn, or queued behind the running one. */
    fun send(sessionId: String, text: String, images: List<String>) {
        val prompt = text.trim()
        if (prompt.isEmpty() && images.isEmpty()) return
        withAgent(sessionId) { it.followup(prompt, images) }
    }

    /**
     * Runs the recipe [recipeJson] headless in [sessionId] (a new chat) on [providerId]/[model]
     * ([RecipeTurns]) and answers [callback] once with the run's record JSON ([RecipeCodec]).
     * Only an on-device provider may run a recipe; anything else fails the run.
     */
    fun runRecipe(
        sessionId: String,
        providerId: String,
        model: String,
        recipeJson: String,
        context: String?,
        startedAt: Long,
        manual: Boolean,
        timeoutMs: Long,
        callback: (String) -> Unit,
    ) {
        val trigger = if (manual) RecipeTrigger.MANUAL else RecipeTrigger.SCHEDULE
        fun failed(reason: String) = RecipeCodec.encodeRecord(RecipeTurnResult.failed(reason).record(startedAt, nowMillis(), trigger, null))
        val recipe = RecipeCodec.decode(recipeJson) ?: return callback(failed("This recipe cannot be read"))
        scope.launch {
            val record = try {
                val spec = withContext(IoDispatcher) { providers.provider(providerId) }
                if (spec == null || spec.kind != ProviderKind.LOCAL || model.isBlank()) {
                    failed(RecipeCopy.ON_DEVICE_ONLY)
                } else {
                    rememberRoute(sessionId, providerId, model)
                    val agent = agent(sessionId, providerId, model)
                    retain(sessionId)
                    try {
                        RecipeCodec.encodeRecord(RecipeTurns.run(agent, recipe, providerId, model, context, timeoutMs).record(startedAt, nowMillis(), trigger, sessionId))
                    } finally {
                        release(sessionId)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                failed(t.message ?: (t::class.simpleName ?: "error"))
            }
            callback(record)
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

    /** Delivers [text] at the next step boundary of the running turn. */
    fun steer(sessionId: String, text: String) = withAgent(sessionId) { it.steer(text) }

    fun cancel(sessionId: String) = withAgent(sessionId) { it.cancel() }

    /** Answers the approval [callId]. Prefer the overload with the session: call ids repeat across chats. */
    fun answerApproval(callId: String, outcome: ApprovalOutcome) {
        interactions.value.filterIsInstance<Pending.Approval>().firstOrNull { it.callId == callId }?.answer?.complete(outcome)
    }

    /** Answers [sessionId]'s approval [callId]. */
    fun answerApproval(sessionId: String, callId: String, outcome: ApprovalOutcome) {
        interactions.value.filterIsInstance<Pending.Approval>().firstOrNull { it.sessionId == sessionId && it.callId == callId }
            ?.answer?.complete(outcome)
    }

    /** Answers the question [callId]. Prefer the overload with the session: call ids repeat across chats. */
    fun answerQuestion(callId: String, answers: List<String>?) {
        interactions.value.filterIsInstance<Pending.Question>().firstOrNull { it.callId == callId }?.answer?.complete(answers)
    }

    /** Answers [sessionId]'s question [callId]. */
    fun answerQuestion(sessionId: String, callId: String, answers: List<String>?) {
        interactions.value.filterIsInstance<Pending.Question>().firstOrNull { it.sessionId == sessionId && it.callId == callId }
            ?.answer?.complete(answers)
    }

    fun setRoute(sessionId: String, providerId: String, model: String) {
        rememberRoute(sessionId, providerId, model)
        withAgent(sessionId) { a ->
            a.updateConfig { it.copy(llm = it.llm.copy(provider = providerId, model = model)) }
            a.restoreThinking(ThinkingDefaults.get(thinkingDefaults, providerId, model))
        }
    }

    /**
     * The user's Think defaults per model ([ThinkingDefaults] text, from Settings): a chat that
     * never chose follows its model's default. Applies to open chats at once.
     */
    fun setThinkingDefaults(text: String?) {
        thinkingDefaults = text
        scope.launch {
            mutex.withLock { agents.values.toList() }.forEach { a ->
                a.restoreThinking(ThinkingDefaults.get(text, a.config.value.llm.provider, a.config.value.llm.model))
            }
        }
    }

    @kotlin.concurrent.Volatile
    private var thinkingDefaults: String? = null

    /** Sets [sessionId]'s Think switch (OFF, AUTO, ON) and remembers it with the chat; [budget] for ON (null: the model's default). */
    fun setThinking(sessionId: String, mode: String, budget: Int?) = withAgent(sessionId) { a ->
        val m = ThinkingMode.entries.firstOrNull { it.name == mode.uppercase() } ?: return@withAgent
        a.chooseThinking(ThinkingSetting(m, budget?.takeIf { it > 0 && m == ThinkingMode.ON }))
    }

    /** Chat templates of on-device models by id, read once from their files (`""`: none). */
    @kotlin.concurrent.Volatile
    private var localTemplates: Map<String, String> = emptyMap()

    /** Whether [model] on [providerId] reasons (and can be switched): decides whether the Think chip shows. */
    fun thinkingSupport(providerId: String, model: String): ThinkingSupport {
        val kind = providers.provider(providerId)?.kind ?: return ThinkingSupport.NONE
        if (kind == ProviderKind.LOCAL && !SystemModels.isSystem(model)) {
            val template = localTemplates[model] ?: runCatching { local.chatTemplate(model) }.getOrNull().orEmpty().also { localTemplates = localTemplates + (model to it) }
            ThinkingSupport.fromTemplate(template)?.let { return it }
        }
        return ThinkingSupport.byName(kind, model)
    }

    private fun rememberRoute(sessionId: String, providerId: String, model: String) {
        scope.launch { mutex.withLock { routes[sessionId] = providerId to model } }
    }

    fun setToolsEnabled(sessionId: String, enabled: Boolean) = withAgent(sessionId) { a ->
        if (a.config.value.toolsEnabled != enabled) {
            a.updateConfig { it.copy(toolsEnabled = enabled) }
            a.log.append(SessionEvent.AgentSettingsChanged(enabled))
        }
    }

    fun setPermission(sessionId: String, preset: PermissionPreset) = withAgent(sessionId) { it.setPermission(preset) }

    /**
     * Settings → "Redact before cloud" (on by default): personal data in cloud requests becomes
     * placeholders, restored on the device. Chats without their own choice follow it.
     */
    fun setRedactionDefault(enabled: Boolean) {
        redactionDefault = enabled
    }

    /** [sessionId]'s own redaction choice; choosing what Settings says makes the chat follow Settings again. */
    fun setRedaction(sessionId: String, enabled: Boolean) = withAgent(sessionId) { a ->
        a.chooseRedaction(redaction.override(enabled))
    }

    /**
     * Settings → "Phone first" (OFF by default; ASK, AUTO, NEVER): chats without their own choice
     * follow it. Unknown values count as OFF.
     */
    fun setCascadeDefault(policy: String) {
        cascadeDefault = CascadePolicy.parse(policy) ?: CascadePolicy.DEFAULT
    }

    /** [sessionId]'s own cascade policy; choosing what Settings says makes the chat follow Settings again. */
    fun setCascade(sessionId: String, policy: String) = withAgent(sessionId) { a ->
        val chosen = CascadePolicy.parse(policy) ?: return@withAgent
        a.chooseCascade(cascade.override(chosen))
    }

    /** "Ask <cloud model>" under the phone's answer: sends that prompt to the chat's cloud model as a follow-up. */
    fun escalate(sessionId: String) = withAgent(sessionId) { a -> cascade.escalate(a) }

    /** The chip: the chat's effective cascade policy, NONE for agent runs and on-device or loopback routes. */
    private fun cascadeState(config: AgentConfig): String {
        val spec = providers.provider(config.llm.provider)
        val cloudRoute = spec == null || RedactionGateway.isCloud(spec.kind, spec.baseUrl)
        return cascade.chipState(config, cloudRoute)
    }

    /** ON / OFF for a cloud route, NONE when the route stays on the phone. */
    private fun redactionState(config: AgentConfig): String {
        val spec = providers.provider(config.llm.provider)
        if (spec != null && !RedactionGateway.isCloud(spec.kind, spec.baseUrl)) return "NONE"
        return if (config.redaction ?: redactionDefault) "ON" else "OFF"
    }

    /** Manual `/compact`. */
    fun compact(sessionId: String) = withAgent(sessionId) { a ->
        a.log.append(SessionEvent.CommandRun("compact"))
        a.ctx[Compactor.Key]?.compact(a, "manual")
    }

    /** Undoes one file step (and later changes to the same files); only while the session is idle. */
    fun undoStep(sessionId: String, callId: String) = withAgent(sessionId) { a -> journal?.undoStep(a, callId) }

    /** Undoes every file change of [turn]. */
    fun undoTurn(sessionId: String, turn: Int) = withAgent(sessionId) { a -> journal?.undoTurn(a, turn) }

    /** Re-applies what an undo ([revertId]) put back. */
    fun redo(sessionId: String, revertId: String) = withAgent(sessionId) { a -> journal?.redo(a, revertId) }

    /** Stops and forgets the agent (before deleting its conversation). */
    fun discard(sessionId: String) {
        journal?.drop(sessionId)
        scope.launch {
            val agent = mutex.withLock {
                watchers.remove(sessionId)?.cancel()
                created.update { it - sessionId }
                routes.remove(sessionId)
                agents.remove(sessionId)
            } ?: return@launch
            running.update { it - sessionId }
            agent.dispose()
        }
    }

    /** Panic wipe: stop every agent and the bridge itself. */
    fun shutdown(done: () -> Unit) {
        // [done] runs once in every case, also when the bridge is already shut down.
        val finished = AtomicBoolean(false)
        scope.launch {
            val all = mutex.withLock {
                watchers.values.forEach { it.cancel() }
                watchers.clear()
                agents.values.toList().also { agents.clear() }
            }
            all.forEach { runCatching { it.dispose() } }
            invalidateProviders()
            runCatching { mcp.close() }
        }.invokeOnCompletion {
            if (finished.compareAndSet(expectedValue = false, newValue = true)) done()
            scope.cancel()
        }
    }

    /**
     * Adds a host-implemented tool (e.g. iOS `generate_image`) to every session, behind the same
     * permission gate as the built-in tools. Registering a name again replaces the tool.
     */
    fun registerTool(tool: HostTool) {
        try {
            harness.root.plugin(toolsPlugin("host:${tool.name}", HostToolDefinition(tool)))
        } catch (t: Throwable) {
            println("AgentBridge: tool ${tool.name} not registered: ${t.message}")
        }
    }

    private var memoryHandle: Disposable? = null
    private var vaultHandle: Disposable? = null
    private var searchHandle: Disposable? = null
    private var studioHandle: Disposable? = null

    private class QuotaFallback(val enabled: Boolean, val onDevice: LlmCallConfig?)

    @kotlin.concurrent.Volatile
    private var quotaFallback = QuotaFallback(false, null)

    /**
     * "On limit, fall back" (off by default): when a provider's usage limit is used up, a turn is
     * retried once on the provider's helper model, else on the on-device model
     * ([onDeviceProviderId] / [onDeviceModel], null when none is installed), with a notice.
     */
    fun setQuotaFallback(enabled: Boolean, onDeviceProviderId: String?, onDeviceModel: String?) {
        val onDevice = if (onDeviceProviderId.isNullOrBlank() || onDeviceModel.isNullOrBlank()) null else LlmCallConfig(onDeviceProviderId, onDeviceModel)
        quotaFallback = QuotaFallback(enabled, onDevice)
    }

    /**
     * Local memory on ([store] = the host's encrypted notes) or off (null): the memory tools and
     * the recall context come and go with it, in every session.
     */
    fun setMemory(store: MemoryStoreBridge?) {
        memoryHandle?.dispose()
        memoryHandle = store?.let { harness.root.plugin(memoryPlugin(Memory(BridgedMemoryStore(it)))) }
    }

    /**
     * Private vault on ([host] = the host's encrypted vault and embedding engine) or off (null):
     * `vault_search` and the vault context come and go with it, in every session.
     */
    fun setVault(host: VaultHost?) {
        vaultHandle?.dispose()
        vaultHandle = host?.let { harness.root.plugin(vaultPlugin(it.vault)) }
    }

    /** `web_search` on (the host's current search settings, read per call) or off (null). */
    fun setWebSearch(source: WebSearchSource?) {
        searchHandle?.dispose()
        searchHandle = source?.let { s -> harness.root.plugin(webSearchPlugin(transport) { withContext(IoDispatcher) { s.config() } }) }
    }

    /**
     * The Creative Studio's `run_workflow` on ([runner], usually [StudioBridge.runner]) or off
     * (null), in every session. GENERATE: it asks like `generate_image`.
     */
    fun setStudio(runner: StudioRunner?) {
        studioHandle?.dispose()
        studioHandle = runner?.let { harness.root.plugin(studioPlugin(it)) }
    }

    /**
     * The spend and quota board's storage ([store] = the host's encrypted usage rows). Without
     * it, calls are still counted for receipts but nothing is kept.
     */
    fun setUsage(store: UsageStoreBridge?) {
        usage.ledger = store?.let { UsageLedger(BridgedUsageStore(it), scope) }
        usageEpoch.update { it + 1 }
    }

    private val usageEpoch = MutableStateFlow(0)

    private val usageNames = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Current provider names by id, for the board (rows keep the name they were recorded with). */
    fun setUsageNames(names: Map<String, String>) {
        usageNames.value = names
    }

    /**
     * The Usage screen's report ([UsageReport] JSON) now and whenever usage, the plan or the
     * provider names change, and once a minute for the "resets in" times.
     */
    fun observeUsage(listener: UsageListener): Cancellable {
        val job = scope.launch {
            combine(usageNames, usageEpoch) { names, _ -> names }.collectLatest { names ->
                val ledger = usage.ledger ?: return@collectLatest
                ledger.load()
                val ticks = flow {
                    while (true) {
                        emit(Unit)
                        delay(USAGE_TICK_MS)
                    }
                }
                combine(ledger.state.filterNotNull(), ticks) { state, _ -> state }.collect { state ->
                    listener.onReport(UsageJson.encode(ledger.report(state, names)))
                }
            }
        }
        return Cancellable { job.cancel() }
    }

    /** Saves the price table and windows ([UsagePlan] JSON); invalid JSON is ignored. */
    fun saveUsagePlan(json: String) {
        val plan = UsageJson.decodePlan(json) ?: return
        val ledger = usage.ledger ?: return
        scope.launch { ledger.savePlan(plan) }
    }

    /** Forgets the counted usage (the price table and windows stay). */
    fun clearUsageHistory() {
        val ledger = usage.ledger ?: return
        scope.launch { ledger.clearHistory() }
    }

    /** Joins usage from an export ([callback] gets how many buckets were added). */
    fun importUsage(records: List<UsageRecord>, plan: UsagePlan?, callback: (Int) -> Unit) {
        val ledger = usage.ledger ?: return callback(0)
        scope.launch { callback(runCatching { ledger.import(records, plan) }.getOrDefault(0)) }
    }

    /** Skills found in the workspace, as `name: description` lines. */
    fun skills(): List<String> = runCatching { contexts.skills().map { "${it.name}: ${it.description}" } }.getOrDefault(emptyList())

    /**
     * Runs [block] on the session's agent. A call made right after [open] (e.g. "chat" turning
     * tools off) waits for the agent [open] is still creating instead of being dropped.
     */
    private fun withAgent(sessionId: String, block: suspend (Agent) -> Unit) {
        scope.launch {
            val agent = mutex.withLock { used(sessionId) }
                // Closed to save memory ([evictIdle]): reopen it from the store.
                ?: mutex.withLock { routes[sessionId] }?.let { (provider, model) -> agent(sessionId, provider, model) }
                ?: withTimeoutOrNull(OPEN_WAIT_MS) { created.map { it[sessionId] }.filterNotNull().first() }
                ?: return@launch
            try {
                block(agent)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // e.g. the store failing to log a settings change: the call is dropped, the bridge lives.
                println("AgentBridge: ${t::class.simpleName}: ${t.message}")
            }
        }
    }

    private fun describe(agent: Agent, name: String, arguments: String): String {
        val tool = agent.ctx[ToolRegistry.Key]?.get(name) ?: return arguments.take(80)
        val args = ToolExecutor.parseArgs(arguments) ?: return arguments.take(80)
        return runCatching { tool.describeCall(args) }.getOrDefault("")
    }

    private fun LiveStream.toFrame(textFrom: Int, reasoningFrom: Int): LiveFrameStream {
        val seconds = (monotonicNanos() - startedAtNanos) / 1e9f
        val approxTokens = (text.length + reasoning.length) / 4f
        return LiveFrameStream(
            turn = turn,
            step = step,
            textFrom = textFrom,
            text = text.substring(textFrom.coerceIn(0, text.length)),
            textLength = text.length,
            reasoningFrom = reasoningFrom,
            reasoning = reasoning.substring(reasoningFrom.coerceIn(0, reasoning.length)),
            reasoningLength = reasoning.length,
            toolCalls = toolCalls.map { LiveToolCall(it.index, it.id, it.name, it.argumentsLength) },
            toolNames = toolCalls.mapNotNull { it.name },
            tokensPerSecond = if (seconds > 0.5f && approxTokens > 0) approxTokens / seconds else null,
            reasoningTokens = reasoningTokens,
            reasoningDone = reasoningDone,
            reasoningStop = reasoningStop,
        )
    }

    private fun LiveStream.toState(): LiveState {
        val seconds = (monotonicNanos() - startedAtNanos) / 1e9f
        val approxTokens = (text.length + reasoning.length) / 4f
        return LiveState(
            text = text,
            reasoning = reasoning,
            toolNames = toolCalls.mapNotNull { it.name },
            tokensPerSecond = if (seconds > 0.5f && approxTokens > 0) approxTokens / seconds else null,
            reasoningTokens = reasoningTokens,
            reasoningDone = reasoningDone,
            reasoningStop = reasoningStop,
        )
    }

    companion object {
        private const val USAGE_TICK_MS = 60_000L

        /** Idle agents kept open beyond the ones running, waiting on the user, streaming or retained. */
        const val MAX_OPEN_AGENTS = 4

        /** Cached model adapters are rebuilt after this long even without [invalidateProviders]. */
        const val ADAPTER_TTL_MS = 60_000L
        private const val OPEN_WAIT_MS = 10_000L
        /** UI frames are coalesced to at most ~30 per second while streaming. */
        private const val FRAME_MS = 33L
        private val BridgeJson = Json { encodeDefaults = true }
    }
}

/**
 * [SessionStore] over the host's [SessionStoreBridge]; unreadable lines are skipped, but their
 * seq still counts (read from the raw line, or else one above the line before), so the next
 * append goes above them.
 */
internal class BridgedSessionStore(private val bridge: SessionStoreBridge) : SessionStore {
    override suspend fun load(sessionId: String): List<LogEntry> = loadLog(sessionId).entries

    override suspend fun loadLog(sessionId: String): StoredLog = withContext(IoDispatcher) {
        var lastSeq = 0L
        val entries = bridge.load(sessionId).mapNotNull { line ->
            val entry = try {
                SessionJson.decodeFromString(LogEntry.serializer(), line)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            lastSeq = maxOf(lastSeq, entry?.seq ?: rawSeq(line) ?: (lastSeq + 1))
            entry
        }
        StoredLog(entries, lastSeq)
    }

    private fun rawSeq(line: String): Long? = runCatching { Json.parseToJsonElement(line).jsonObject["seq"]?.jsonPrimitive?.longOrNull }.getOrNull()

    override suspend fun append(sessionId: String, entries: List<LogEntry>) = withContext(IoDispatcher) {
        bridge.append(sessionId, entries.map { SessionJson.encodeToString(LogEntry.serializer(), it) })
    }
}
