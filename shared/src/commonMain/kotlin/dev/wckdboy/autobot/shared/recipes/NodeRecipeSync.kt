package dev.wckdboy.autobot.shared.recipes

import dev.wckdboy.autobot.agent.core.session.ReceiptNode
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.shared.invincible.NodeAgent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone

/** A paired node as recipes use it: its agent, and its signed receipts checked against the pinned key. */
class NodeRecipeTarget(val agent: NodeAgent, val node: String, val receipt: suspend (requestId: String) -> ReceiptNode)

/** What [NodeRecipeSync] needs from the app (each app has its stores, chats and notifications). */
interface NodeRecipeHost {
    fun now(): Long
    fun zone(): TimeZone = TimeZone.currentSystemDefault()

    suspend fun recipes(): List<Recipe>
    suspend fun load(id: String): Recipe?
    suspend fun save(recipe: Recipe)

    /** The node [providerId] pairs with; null when it is no longer a usable pairing. */
    suspend fun target(providerId: String): NodeRecipeTarget?

    /** A new chat for a run in the Recipes folder, on [providerId] (the node), titled for [atMillis]. */
    suspend fun newChat(recipe: Recipe, providerId: String, atMillis: Long): String
    suspend fun append(chatId: String, events: List<SessionEvent>)

    fun notifyFinished(recipe: Recipe, record: RecipeRunRecord)
}

/**
 * Keeps recipes that run on a node in step with it: sends them there when they change (or the
 * phone's UTC offset moves), takes them off when they move back or are deleted, starts "Run now"
 * there, and collects finished runs into chats with the node's signed receipts, as if the phone
 * had run them.
 */
class NodeRecipeSync(private val host: NodeRecipeHost) {
    private val lock = Mutex()

    /** Why each node could not be reached on the last pass, by provider id. */
    var problems: Map<String, String> = emptyMap()
        private set

    /**
     * After [recipe] was saved (from [previous]): pushed to its node, or removed from the one it
     * left. Null, or why the node could not be told (the phone retries on the next [sync]).
     */
    suspend fun saved(recipe: Recipe, previous: Recipe?): String? {
        if (previous?.runsOn != null && previous.runsOn != recipe.runsOn) {
            runCatching { host.target(previous.runsOn)?.let { NodeRecipes.remove(it.agent, previous) } }
        }
        if (recipe.runsOn == null) return null
        return push(recipe)
    }

    suspend fun deleted(recipe: Recipe) {
        val node = recipe.runsOn ?: return
        runCatching { host.target(node)?.let { NodeRecipes.remove(it.agent, recipe) } }
    }

    private suspend fun push(recipe: Recipe): String? {
        val node = recipe.runsOn ?: return null
        val target = host.target(node) ?: return "The node this recipe runs on is no longer paired."
        return try {
            NodeRecipes.push(target.agent, recipe, host.zone(), host.now())
            val latest = host.load(recipe.id) ?: recipe
            host.save(latest.copy(state = latest.state.copy(pushed = NodeRecipes.pushed(recipe, host.zone(), host.now()))))
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "The node did not answer."
        }
    }

    /** "Run now" on the node; null, or why not. The result comes with a later [sync] (or [awaitRun]). */
    suspend fun runNow(recipe: Recipe): String? {
        val node = recipe.runsOn ?: return "This recipe runs on the phone."
        val target = host.target(node) ?: return "The node this recipe runs on is no longer paired."
        return try {
            // The node gets the recipe as it is now, then runs it.
            push(recipe)?.let { return it }
            if (NodeRecipes.runNow(target.agent, recipe)) null else "It is running on the node already."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "The node did not answer."
        }
    }

    /**
     * One pass over every recipe on a node: re-sends the ones that need it, collects finished
     * runs. Returns how many runs were collected.
     */
    suspend fun sync(): Int = lock.withLock {
        val problems = mutableMapOf<String, String>()
        var collected = 0
        for (recipe in host.recipes().filter { it.runsOn != null }) {
            val node = recipe.runsOn!!
            if (node in problems) continue
            val target = host.target(node)
            if (target == null) {
                problems[node] = "This node is no longer paired."
                continue
            }
            try {
                if (NodeRecipes.needsPush(recipe, recipe, host.zone(), host.now())) push(recipe)?.let { problems[node] = it }
                collected += collect(recipe, target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problems[node] = e.message ?: "The node did not answer."
            }
        }
        this.problems = problems
        collected
    }

    /** Waits (up to [timeoutMillis]) for [id]'s next run on its node and collects it; true when it came. */
    suspend fun awaitRun(id: String, timeoutMillis: Long, pollMillis: Long = 10_000): Boolean {
        val start = host.now()
        while (host.now() - start < timeoutMillis) {
            delay(pollMillis)
            val recipe = host.load(id) ?: return false
            val target = recipe.runsOn?.let { host.target(it) } ?: return false
            val got = runCatching { lock.withLock { collect(recipe, target) } }.getOrDefault(0)
            if (got > 0) return true
        }
        return false
    }

    private suspend fun collect(recipe: Recipe, target: NodeRecipeTarget): Int {
        val runs = NodeRecipes.newRuns(target.agent, recipe)
        var current = recipe
        for (run in runs) {
            val receipts = run.receipts.map { id ->
                try {
                    target.receipt(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ReceiptNode(target.node, id, verified = false, missing = e.message ?: "could not fetch it")
                }
            }
            val model = receipts.firstNotNullOfOrNull { it.model }
            val chat = host.newChat(current, current.runsOn!!, run.at * 1000)
            host.append(chat, NodeRecipes.transcript(current, run, model, receipts))
            val record = NodeRecipes.record(run, chat, receipts)
            val latest = host.load(current.id) ?: current
            current = RecipePlanner.finished(latest, record, host.zone())
            host.save(current)
            host.notifyFinished(current, record)
        }
        return runs.size
    }
}
