package dev.wckdboy.autobot.shared.vault

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** S11: the per-turn vault context does not re-list the store until a write. */
class VaultCacheTest {

    private class CountingStore : VaultStore {
        var listings = 0
        val docs = LinkedHashMap<String, Triple<VaultDocument, List<VaultChunk>, ByteArray>>()
        override suspend fun documents(): List<VaultDocument> {
            listings++
            return docs.values.map { it.first }
        }
        override suspend fun chunks(docId: String) = docs[docId]?.second.orEmpty()
        override suspend fun vectors(docId: String) = docs[docId]?.third
        override suspend fun put(document: VaultDocument, chunks: List<VaultChunk>, vectors: ByteArray) {
            docs[document.id] = Triple(document, chunks, vectors)
        }
        override suspend fun delete(docId: String) = docs.remove(docId) != null
        override suspend fun deleteAll() = docs.clear()
    }

    private object Embed : Embedder {
        override suspend fun modelId() = "test-embed"
        override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map { t -> FloatArray(8) { i -> (t.length + i).toFloat() } }
    }

    @Test
    fun documentListIsCachedUntilAWrite() = runBlocking {
        var now = 0L
        val store = CountingStore()
        val vault = Vault(store, Embed) { now }
        assertEquals(0, vault.cachedDocuments().size)
        repeat(5) { vault.cachedDocuments() }
        assertEquals(1, store.listings)

        vault.add(VaultText("a.txt", VaultFileType.TEXT, 11, listOf("hello vault")))
        assertEquals(listOf("a.txt"), vault.cachedDocuments().map { it.name })
        val afterAdd = store.listings
        vault.cachedDocuments()
        assertEquals(afterAdd, store.listings)

        // Changes made around this vault show up after the TTL at the latest.
        now += Vault.LIST_TTL_MS
        vault.cachedDocuments()
        assertEquals(afterAdd + 1, store.listings)
    }
}
