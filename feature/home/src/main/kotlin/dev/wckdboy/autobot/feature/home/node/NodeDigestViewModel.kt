package dev.wckdboy.autobot.feature.home.node

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.wckdboy.autobot.core.data.ProviderRepository
import dev.wckdboy.autobot.core.data.RecipeRepository
import dev.wckdboy.autobot.core.data.debug.DemoMode
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.providers.remote.NodeAgents
import dev.wckdboy.autobot.shared.invincible.NodeDigest
import dev.wckdboy.autobot.shared.invincible.NodeDigests
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The "While you were away" card: [digest] of [node] (its "My node" provider [providerId]). */
@Immutable
data class NodeDigestCardState(val providerId: String, val node: String, val digest: NodeDigest)

/** Where the digest comes from: the paired node, and what it did after a time. */
interface NodeDigestSource {
    /** The first paired node, as (provider id, name); null when none is paired. */
    suspend fun node(): Pair<String, String>?

    /** What [providerId]'s node did after [since] (Unix seconds); null when the pairing is unreadable. */
    suspend fun digest(providerId: String, since: Long): NodeDigest?
}

/** When the owner last looked at each node's digest (Unix seconds). */
interface NodeSeenStore {
    fun lastSeen(providerId: String): Long?
    fun markSeen(providerId: String, at: Long)
}

/**
 * Loads the digest of the paired node when the Run screen shows, and only keeps it when there is
 * something new. Dismissing it, or opening what it points at, marks it seen.
 */
@HiltViewModel
class NodeDigestViewModel internal constructor(
    private val source: NodeDigestSource,
    private val seen: NodeSeenStore,
    private val now: () -> Long,
) : ViewModel() {
    @Inject
    constructor(source: NodeDigestSource, seen: NodeSeenStore) : this(source, seen, { System.currentTimeMillis() / 1000 })

    private val _state = MutableStateFlow<NodeDigestCardState?>(null)
    val state: StateFlow<NodeDigestCardState?> = _state

    private var loading: Job? = null

    /** Asks the node again (the Run screen came back); quietly nothing when it does not answer. */
    fun refresh() {
        loading?.cancel()
        loading = viewModelScope.launch {
            _state.value = try {
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The node is asleep or out of reach: no card rather than an error on the home screen.
                null
            }
        }
    }

    internal suspend fun load(): NodeDigestCardState? {
        val (providerId, node) = source.node() ?: return null
        val digest = source.digest(providerId, NodeDigests.since(seen.lastSeen(providerId), now())) ?: return null
        return if (digest.hasNews) NodeDigestCardState(providerId, node, digest) else null
    }

    /** Dismissed or opened: everything up to now is seen, and the card goes. */
    fun markSeen() {
        val current = _state.value ?: return
        loading?.cancel()
        seen.markSeen(current.providerId, now())
        _state.value = null
    }
}

/** The paired node through [NodeAgents], with the recipes for their result chats. */
@Singleton
class RepositoryNodeDigestSource @Inject constructor(
    private val providers: ProviderRepository,
    private val agents: NodeAgents,
    private val recipes: RecipeRepository,
) : NodeDigestSource {
    override suspend fun node(): Pair<String, String>? =
        providers.observeProviders().first().firstOrNull { it.kind == ProviderKind.INVINCIBLE_NODE }?.let { it.id to it.displayName }

    override suspend fun digest(providerId: String, since: Long): NodeDigest? {
        // Debug builds' demo (`--ez demo true`): the shared sample, as iOS's `-AutobotDemo` shows it.
        if (DemoMode.on) return NodeDigests.sample(now = System.currentTimeMillis() / 1000, failedReceipt = DemoMode.digestFailed)
        val provider = providers.observeProviders().first().firstOrNull { it.id == providerId } ?: return null
        return agents.digest(provider, since, recipes.all().map(NodeDigests::recipe))
    }
}

/** "Last seen" per node in its own small preferences file (not settings that sync or back up). */
@Singleton
class PrefsNodeSeenStore @Inject constructor(@ApplicationContext context: Context) : NodeSeenStore {
    private val prefs = context.getSharedPreferences("node_digest", Context.MODE_PRIVATE)

    override fun lastSeen(providerId: String): Long? =
        NodeDigests.seenKey(providerId).let { key -> if (prefs.contains(key)) prefs.getLong(key, 0) else null }

    override fun markSeen(providerId: String, at: Long) {
        prefs.edit().putLong(NodeDigests.seenKey(providerId), at).apply()
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class NodeDigestModule {
    @Binds
    abstract fun source(impl: RepositoryNodeDigestSource): NodeDigestSource

    @Binds
    abstract fun seen(impl: PrefsNodeSeenStore): NodeSeenStore
}
