package dev.wckdboy.autobot.agent.runtime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Model adapters by provider id, built once and reused for every agent step. Building one reads
 * the provider row, decrypts its API key through the Keystore (tens of ms, more on StrongBox),
 * builds an HTTP client and decodes the cached model list, and the agent asks for an adapter
 * several times per step. [clear] (provider, model list or network route changed; panic wipe)
 * drops them all; a build that raced a [clear] is used once but not kept.
 */
class AdapterCache<T : Any>(private val build: suspend (String) -> T?) {
    private val mutex = Mutex()
    private val adapters = HashMap<String, T>()
    private var generation = 0L

    suspend fun get(id: String): T? {
        val seen = mutex.withLock {
            adapters[id]?.let { return it }
            generation
        }
        // Built outside the lock: a slow key decrypt must not hold up other providers.
        val built = build(id) ?: return null
        return mutex.withLock {
            // Another step may have built it meanwhile: keep the first, so steps share one client.
            adapters[id] ?: built.also { if (generation == seen) adapters[id] = it }
        }
    }

    suspend fun clear() = mutex.withLock {
        generation++
        adapters.clear()
    }
}
