package dev.wckdboy.autobot.shared.memory

import dev.wckdboy.autobot.shared.IoDispatcher
import kotlinx.coroutines.withContext

/**
 * The host's encrypted note storage for apps that are not Kotlin (iOS: the `EncryptedStore`).
 * Called on a background thread; [put] replaces a note with the same id.
 */
interface MemoryStoreBridge {
    fun all(): List<MemoryNote>
    fun put(note: MemoryNote)
    fun remove(id: String): Boolean
    fun removeAll()
}

/** [MemoryStore] over a host [MemoryStoreBridge]. */
class BridgedMemoryStore(private val bridge: MemoryStoreBridge) : MemoryStore {
    override suspend fun all(): List<MemoryNote> = withContext(IoDispatcher) { bridge.all() }

    override suspend fun get(id: String): MemoryNote? = all().firstOrNull { it.id == id }

    override suspend fun upsert(note: MemoryNote) = withContext(IoDispatcher) { bridge.put(note) }

    override suspend fun delete(id: String): Boolean = withContext(IoDispatcher) { bridge.remove(id) }

    override suspend fun deleteAll() = withContext(IoDispatcher) { bridge.removeAll() }
}
