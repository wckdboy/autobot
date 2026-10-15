package dev.wckdboy.autobot.shared.vault

import dev.wckdboy.autobot.agent.core.approval.TaintTracker
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.tools.ToolRunContext
import dev.wckdboy.autobot.agent.core.tools.UserQuestion
import java.util.Base64
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultTest {

    /** Hashed bag of words: texts sharing words point the same way. Records what it was asked. */
    private class WordEmbedder(var model: String? = "test-embed", val dims: Int = 64) : Embedder {
        val calls = ArrayList<List<String>>()
        override suspend fun modelId() = model
        override suspend fun embed(texts: List<String>): List<FloatArray> {
            calls += texts
            return texts.map { text ->
                val v = FloatArray(dims)
                Regex("[a-z0-9]+").findAll(text.lowercase()).forEach { v[Math.floorMod(it.value.hashCode(), dims)] += 1f }
                val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat().takeIf { it > 0 } ?: 1f
                FloatArray(dims) { v[it] / n }
            }
        }
    }

    private class MemoryStore : VaultStore {
        val docs = LinkedHashMap<String, Triple<VaultDocument, List<VaultChunk>, ByteArray>>()
        override suspend fun documents() = docs.values.map { it.first }
        override suspend fun chunks(docId: String) = docs[docId]?.second.orEmpty()
        override suspend fun vectors(docId: String) = docs[docId]?.third
        override suspend fun put(document: VaultDocument, chunks: List<VaultChunk>, vectors: ByteArray) {
            docs[document.id] = Triple(document, chunks, vectors)
        }
        override suspend fun delete(docId: String) = docs.remove(docId) != null
        override suspend fun deleteAll() = docs.clear()
    }

    private object Run : ToolRunContext {
        override val callId = "c1"
        override val sessionId = "s1"
        override fun deferContext(text: String) = Unit
        override suspend fun log(event: SessionEvent) = Unit
        override suspend fun askUser(question: UserQuestion): List<String>? = null
    }

    private fun text(name: String, vararg pages: String, type: VaultFileType = VaultFileType.TEXT) =
        VaultText(name, type, pages.sumOf { it.length }.toLong(), pages.toList())

    // ---- chunker

    @Test
    fun shortTextIsOnePassage() {
        val chunks = VaultRules.chunk("d", listOf("  Hello world.\n\nSecond paragraph.  "))
        assertEquals(1, chunks.size)
        assertEquals("Hello world.\n\nSecond paragraph.", chunks[0].text)
        assertNull(chunks[0].page)
        assertTrue(VaultRules.chunk("d", listOf("   \n\n  ")).isEmpty())
    }

    @Test
    fun longTextSplitsAtParagraphsWithOverlapAndExactOffsets() {
        val paragraphs = (1..40).map { i -> "Paragraph $i " + "word ".repeat(60).trim() + "." }
        val joined = paragraphs.joinToString("\n\n")
        val chunks = VaultRules.chunk("d", listOf(joined), maxTokens = 200, overlapTokens = 90)
        assertTrue(chunks.size > 5)
        chunks.forEachIndexed { i, c ->
            assertEquals(i, c.index)
            assertEquals(joined.substring(c.start, c.end), c.text)
            assertTrue(VaultRules.estimateTokens(c.text) <= 200 + 2)
            assertTrue(c.text.startsWith("Paragraph"))
        }
        // Consecutive passages share their boundary paragraph, and together cover everything.
        for (i in 1 until chunks.size) assertTrue(chunks[i].start < chunks[i - 1].end)
        assertEquals(0, chunks.first().start)
        assertEquals(joined.length, chunks.last().end)
    }

    @Test
    fun oversizedParagraphsBreakAtSentencesThenWords() {
        val sentences = (1..30).joinToString(" ") { "Sentence number $it is here." }
        val chunks = VaultRules.chunk("d", listOf(sentences), maxTokens = 40, overlapTokens = 0)
        assertTrue(chunks.size > 3)
        assertTrue(chunks.all { it.text.startsWith("Sentence") && it.text.endsWith(".") })

        val oneWordSoup = "abc ".repeat(1000)
        val pieces = VaultRules.chunk("d", listOf(oneWordSoup), maxTokens = 50, overlapTokens = 0)
        assertTrue(pieces.all { VaultRules.estimateTokens(it.text) <= 50 && !it.text.endsWith(" ") })
        assertTrue(pieces.none { it.text.contains("ab ") || it.text.startsWith("bc") })
    }

    @Test
    fun cjkCountsOneTokenPerCharacterAndPagesAreRecorded() {
        assertEquals(11, VaultRules.estimateTokens("日本語のテキストです。"))
        assertEquals(3, VaultRules.estimateTokens("hello world"))
        val chunks = VaultRules.chunk("d", listOf("Page one text.", "Page two text.", "Page three."), maxTokens = 5, overlapTokens = 0)
        assertEquals(listOf(1, 2, 3), chunks.map { it.page })
    }

    @Test
    fun idsAndHashesAreStable() {
        assertEquals(VaultRules.documentId("content://a/1"), VaultRules.documentId("content://a/1"))
        assertFalse(VaultRules.documentId("content://a/1") == VaultRules.documentId("content://a/2"))
        assertTrue(VaultRules.documentId("x").matches(Regex("d-[0-9a-f]{12}")))
        assertEquals(VaultRules.contentHash(listOf("a", "b")), VaultRules.contentHash(listOf("a", "b")))
        assertFalse(VaultRules.contentHash(listOf("ab")) == VaultRules.contentHash(listOf("a", "b")))
    }

    // ---- vectors and ranking

    @Test
    fun int8CodecKeepsCosineClose() {
        val a = VectorCodec.fit(FloatArray(512) { (it % 7 - 3).toFloat() + 0.1f }, null)
        val b = VectorCodec.fit(FloatArray(512) { ((it * 3) % 5 - 2).toFloat() }, null)
        val packed = VectorCodec.encode(listOf(a, b), 512)
        assertEquals(2 * 516, packed.size)
        val exact = a.indices.sumOf { (a[it] * b[it]).toDouble() }
        assertTrue(abs(VectorCodec.dot(a, packed, 1, 512) - exact) < 0.01)
        assertTrue(abs(VectorCodec.dot(a, packed, 0, 512) - 1.0) < 0.01)
        // Matryoshka cut: shorter and normalised again.
        val cut = VectorCodec.fit(a, 64)
        assertEquals(64, cut.size)
        assertEquals(1.0, sqrt(cut.sumOf { (it * it).toDouble() }), 1e-5)
    }

    private fun doc(id: String, dims: Int) = VaultDocument(id, "$id.txt", VaultFileType.TEXT, 1, "h", 3, 0, "m", dims, 0, 0)

    @Test
    fun searchRanksByCosineAndMmrDropsNearDuplicates() {
        val e = { i: Int -> FloatArray(4).also { it[i] = 1f } }
        val best = VectorCodec.fit(floatArrayOf(0.9f, 0f, 0f, 0.436f), null)
        val near = VectorCodec.fit(floatArrayOf(0.89f, 0.1f, 0f, 0.44f), null)
        val mixed = VectorCodec.fit(floatArrayOf(0.8f, 0f, 0.6f, 0f), null)
        val index = VectorIndex(
            listOf(
                VectorIndex.Entry(doc("a", 4), VectorCodec.encode(listOf(best, near, e(3)), 4)),
                VectorIndex.Entry(doc("b", 4), VectorCodec.encode(listOf(mixed, e(1)), 4)),
                VectorIndex.Entry(doc("other-model", 8), VectorCodec.encode(listOf(FloatArray(8) { 1f / sqrt(8f) }), 8)),
            ),
        )
        assertEquals(6, index.size)
        val q = e(0)
        val plain = index.search(q, 3)
        assertEquals(listOf("a" to 0, "a" to 1, "b" to 0), plain.map { it.document.id to it.index })
        assertTrue(plain[0].score >= plain[1].score && plain[1].score >= plain[2].score)
        // With MMR the near-copy of the best passage loses to the different, still relevant one.
        val diverse = index.search(q, 2, mmrLambda = 0.5)
        assertEquals(listOf("a" to 0, "b" to 0), diverse.map { it.document.id to it.index })
        assertTrue(index.search(q, 0).isEmpty())
    }

    // ---- the vault

    @Test
    fun addSearchUnchangedUpdateAndRemove() = runBlocking {
        val store = MemoryStore()
        val embedder = WordEmbedder()
        val vault = Vault(store, embedder, clock = { 1000L })
        val progress = ArrayList<Pair<Int, Int>>()
        val added = vault.add(text("lease.txt", "The apartment rent is 950 euros per month.\n\nThe landlord is Hannah Schmidt."), { d, t -> progress += d to t })
        assertEquals(AddStatus.ADDED, added.status)
        assertEquals(progress.last().first, progress.last().second)
        val docId = added.document!!.id
        assertEquals(64, added.document!!.dims)
        assertTrue("passages are embedded with the file name", embedder.calls.last().first().startsWith("lease.txt\n\n"))
        vault.add(text("recipes.txt", "Pancakes need flour, eggs and milk.\n\nBake bread at 220 degrees."))

        val hits = vault.search("how much is the rent per month")
        assertEquals("lease.txt", hits.first().document.name)
        assertTrue(hits.first().chunk.text.contains("950 euros"))

        val calls = embedder.calls.size
        assertEquals(AddStatus.UNCHANGED, vault.add(text("lease.txt", "The apartment rent is 950 euros per month.\n\nThe landlord is Hannah Schmidt.")).status)
        assertEquals("unchanged text is not embedded again", calls, embedder.calls.size)
        val updated = vault.add(text("lease.txt", "The rent went up to 990 euros."))
        assertEquals(AddStatus.UPDATED, updated.status)
        assertEquals(docId, updated.document!!.id)
        assertTrue(vault.search("rent").first().chunk.text.contains("990"))

        assertTrue(vault.remove(docId))
        assertEquals(listOf("recipes.txt"), vault.documents().map { it.name })
        assertTrue(vault.search("rent").none { it.document.name == "lease.txt" })
        vault.clear()
        assertTrue(vault.documents().isEmpty())
        assertEquals(AddStatus.EMPTY, vault.add(text("blank.txt", "   ")).status)
    }

    @Test
    fun withoutAModelNothingIsIndexedAndAModelChangeMarksDocumentsStale() = runBlocking {
        val store = MemoryStore()
        val embedder = WordEmbedder(model = null)
        val vault = Vault(store, embedder)
        assertEquals(AddStatus.NO_MODEL, vault.add(text("a.txt", "alpha")).status)
        assertTrue(vault.search("alpha").isEmpty())

        embedder.model = "test-embed"
        vault.add(text("a.txt", "alpha beta gamma"))
        assertEquals(0, vault.status().stale)
        embedder.model = "other-embed"
        assertEquals(1, vault.status().stale)
        assertTrue("stale documents are not searched", vault.search("alpha").isEmpty())
        val progress = ArrayList<String>()
        assertEquals(1, vault.reindexStale { name, _, _ -> progress += name })
        assertEquals(listOf("a.txt", "a.txt"), progress)
        assertEquals(0, vault.status().stale)
        assertEquals("other-embed", store.docs.values.single().first.model)
        assertEquals("a.txt", vault.search("alpha").single().document.name)
    }

    @Test
    fun profilesAddTaskPrefixesAndCutDimensions() = runBlocking {
        val embedder = WordEmbedder(model = "catalog:qwen3-embedding-0.6b-q8_0", dims = 1024)
        val store = MemoryStore()
        val vault = Vault(store, embedder)
        val doc = vault.add(text("notes.md", "Meeting notes about the budget.", type = VaultFileType.MARKDOWN)).document!!
        assertEquals(512, doc.dims)
        assertEquals(VectorCodec.size(512), store.docs.values.single().third.size)
        assertEquals("notes.md\n\nMeeting notes about the budget.", embedder.calls.last().single())
        vault.search("budget")
        assertTrue(embedder.calls.last().single().startsWith("Instruct: "))
        assertTrue(embedder.calls.last().single().endsWith("\nQuery: budget"))
        assertEquals("search_query: ", EmbeddingProfile.of("nomic-embed-text-v1.5").queryPrefix)
    }

    // ---- the tool

    @Test
    fun toolCitesFileAndPositionAndMarksResultsPrivate() = runBlocking {
        val vault = Vault(MemoryStore(), WordEmbedder())
        val tool = VaultSearchTool(vault)
        assertTrue(tool.isConcurrencySafe(JsonObject(emptyMap())))
        assertEquals("vault", tool.privateSource!!.label)
        assertEquals("The vault is empty: the user has not added any documents yet.", tool.execute(args("rent"), Run).content)

        vault.add(text("lease.pdf", "Cover page.", "The monthly rent is 950 euros, paid to Hannah Schmidt.", type = VaultFileType.PDF))
        val out = tool.execute(args("monthly rent"), Run)
        assertFalse(out.isError)
        assertTrue(out.content, out.content.startsWith("[1] lease.pdf · p. 1 · passage 1/1 · score "))
        assertTrue(out.content.contains("The monthly rent is 950 euros"))
        val stamp = out.meta!![TaintTracker.META_KEY]!!.jsonObject
        assertEquals("vault", stamp["label"]!!.jsonPrimitive.content)
        // What taint tracking indexes: the passages, not the headers.
        val private = tool.privateSource!!.clean(out.content)
        assertTrue(private.contains("Hannah Schmidt"))
        assertFalse(private.contains("score"))

        assertTrue(tool.execute(args(" "), Run).isError)
        assertEquals("NO_MODEL", VaultSearchTool(Vault(MemoryStore(), WordEmbedder(model = null))).execute(args("x"), Run).code)
    }

    private fun args(query: String) = buildJsonObject { put("query", query) }

    // ---- the iOS bridge

    @Test
    fun bridgedStoreRoundTripsThroughStrings() = runBlocking {
        val records = HashMap<String, Triple<String, String, String>>()
        val bridge = object : VaultStoreBridge {
            override fun documents() = records.values.map { it.first }
            override fun chunks(id: String) = records[id]?.second
            override fun vectors(id: String) = records[id]?.third
            override fun put(id: String, document: String, chunks: String, vectors: String) { records[id] = Triple(document, chunks, vectors) }
            override fun remove(id: String) = records.remove(id) != null
            override fun removeAll() = records.clear()
        }
        val floats = floatArrayOf(0.6f, 0.8f)
        val embedder = object : EmbedderBridge {
            override fun modelId() = "bridge-embed"
            override fun embed(texts: List<String>): List<String> = texts.map {
                val bytes = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(floats[0]).putFloat(floats[1]).array()
                Base64.getEncoder().encodeToString(bytes)
            }
            override fun lastError(): String? = null
        }
        val host = VaultHost(bridge, embedder)
        val result = host.vault.add(VaultText("a.md", VaultFileType.MARKDOWN, 3, listOf("one"), source = "a.md"))
        assertEquals(AddStatus.ADDED, result.status)
        val docs = host.vault.documents()
        assertEquals(result.document, docs.single())
        assertEquals("one", host.vault.chunks(docs.single().id).single().text)
        assertNotNull(BridgedVaultStore(bridge).vectors(docs.single().id))
        assertEquals(1.0, host.vault.search("one").single().score, 0.01)
        assertTrue(host.vault.remove(docs.single().id))
        assertTrue(records.isEmpty())
    }
}
