package dev.wckdboy.autobot.shared.memory

import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory [MemoryStore] for tests. */
class FakeMemoryStore(notes: List<MemoryNote> = emptyList()) : MemoryStore {
    val notes = LinkedHashMap<String, MemoryNote>().apply { notes.forEach { put(it.id, it) } }

    override suspend fun all(): List<MemoryNote> = synchronized(notes) { notes.values.toList() }
    override suspend fun get(id: String): MemoryNote? = synchronized(notes) { notes[id] }
    override suspend fun upsert(note: MemoryNote) = synchronized(notes) { notes[note.id] = note }
    override suspend fun delete(id: String): Boolean = synchronized(notes) { notes.remove(id) != null }
    override suspend fun deleteAll() = synchronized(notes) { notes.clear() }
}

class MemoryTest {
    private fun note(id: String, text: String, tags: List<String> = emptyList(), updatedAt: Long = 1) =
        MemoryNote(id, text, tags, createdAt = 1, updatedAt = updatedAt)

    @Test
    fun tokensDropStopwordsAndFoldPlurals() {
        assertEquals(listOf("cat", "like", "fish"), MemoryRules.tokens("The cats like FISH!"))
        assertEquals(listOf("glass", "3", "kotlin"), MemoryRules.tokens("glass, 3 × Kotlin"))
        assertEquals(listOf("münchen", "straße"), MemoryRules.tokens("München Straße"))
    }

    @Test
    fun keywordRankerPrefersRareTermsTagsAndNewerNotes() {
        val notes = listOf(
            note("m-1", "The user prefers dark roast coffee in the morning"),
            note("m-2", "The user's project is a Kotlin Multiplatform agent called Autobot", listOf("project")),
            note("m-3", "The user drinks coffee", updatedAt = 5),
            note("m-4", "Allergic to peanuts"),
        )
        val project = KeywordRanker.score("how is my kotlin project going", notes, 3)
        assertEquals("m-2", project.first().note.id)
        assertEquals(1, project.size)

        // One match each: BM25 favours the shorter note; a second query term lifts m-1 above it.
        assertEquals(listOf("m-3", "m-1"), KeywordRanker.score("coffee", notes, 5).map { it.note.id })
        assertEquals("m-1", KeywordRanker.score("dark coffee", notes, 5).first().note.id)

        assertTrue(KeywordRanker.score("the and of", notes, 5).isEmpty())
        assertTrue(KeywordRanker.score("unrelated words", notes, 5).isEmpty())
        assertEquals(1, KeywordRanker.score("coffee", notes, 1).size)
    }

    @Test
    fun tagsAreSearchable() {
        val notes = listOf(note("m-1", "Weekly sync is on Tuesdays", listOf("work")), note("m-2", "Gym on Mondays"))
        assertEquals(listOf("m-1"), KeywordRanker.score("work", notes, 5).map { it.note.id })
        val ties = listOf(note("m-old", "Coffee beans", updatedAt = 1), note("m-new", "Coffee mugs", updatedAt = 9))
        assertEquals(listOf("m-new", "m-old"), KeywordRanker.score("coffee", ties, 5).map { it.note.id })
    }

    @Test
    fun normalisationAndCaps() {
        assertEquals("a b\n\nc", MemoryRules.normalizeText("  a \t  b \r\n\n\n\n c  "))
        assertNull(MemoryRules.normalizeText(" \n\t "))
        assertEquals(MemoryRules.MAX_TEXT, MemoryRules.normalizeText("x".repeat(5000))!!.length)
        assertEquals(listOf("work", "side-project", "c"), MemoryRules.normalizeTags(listOf("#Work", "side project", "work", "c++", "", "!!")))
        assertEquals(MemoryRules.MAX_TAGS, MemoryRules.normalizeTags((1..20).map { "t$it" }).size)
        assertEquals(listOf("a", "b", "c"), MemoryRules.parseTags("a, b c"))
        val id = MemoryRules.newId(Random(1))
        assertTrue(MemoryRules.isValidId(id) && id.startsWith("m-") && id.length == 12)
        assertFalse(MemoryRules.isValidId("../x"))
    }

    @Test
    fun duplicatesAreDetectedByTextAndTokenOverlap() {
        val notes = listOf(note("m-1", "The user's dog is called Rex."), note("m-2", "Prefers metric units"))
        assertEquals("m-1", MemoryRules.findDuplicate("the user's dog is called rex", notes)?.id)
        assertEquals("m-2", MemoryRules.findDuplicate("Prefers metric unit", notes)?.id)
        assertNull(MemoryRules.findDuplicate("The user's cat is called Rex", notes))
        assertNull(MemoryRules.findDuplicate("the user's dog is called rex", notes, ignoreId = "m-1"))
    }

    @Test
    fun saveCreatesMergesUpdatesAndRespectsTheCap() = runBlocking {
        val store = FakeMemoryStore()
        var now = 100L
        val memory = Memory(store, clock = { now })

        val created = memory.save("  Likes  hiking ", listOf("Outdoor"), sessionId = "s1") as SaveResult.Created
        assertEquals("Likes hiking", created.note.text)
        assertEquals(listOf("outdoor"), created.note.tags)
        assertEquals("s1", created.note.sessionId)

        now = 200L
        val dup = memory.save("likes hiking", listOf("weekend"), sessionId = "s2") as SaveResult.Duplicate
        assertEquals(created.note.id, dup.note.id)
        assertEquals(listOf("outdoor", "weekend"), dup.note.tags)
        assertEquals(200L, dup.note.updatedAt)
        assertEquals(1, store.all().size)

        val updated = memory.save("Likes hiking and climbing", emptyList(), id = created.note.id) as SaveResult.Updated
        assertEquals(100L, updated.note.createdAt)
        assertEquals("Likes hiking and climbing", store.get(created.note.id)!!.text)

        assertEquals(SaveResult.Empty, memory.save("   ", emptyList()))
        assertEquals(SaveResult.NotFound, memory.save("x", emptyList(), id = "m-nope"))
        assertNull((memory.save("Incognito fact", emptyList(), sessionId = "incognito-1") as SaveResult.Created).note.sessionId)

        val full = FakeMemoryStore((1..MemoryRules.MAX_NOTES).map { note("m-$it", "distinct note number $it about topic$it") })
        assertEquals(SaveResult.Full, Memory(full).save("something entirely new", emptyList()))
    }

    @Test
    fun recallIsShortDelimitedAndCarriesIds() = runBlocking {
        val store = FakeMemoryStore(
            listOf(
                note("m-a", "Prefers answers in British English"),
                note("m-b", "Works on Autobot: " + "detail ".repeat(100)),
                note("m-c", "Lives in Lisbon"),
            ),
        )
        val memory = Memory(store)
        val text = memory.recall("Can you help with Autobot in British English?")!!
        assertTrue(text.startsWith("<memory>\n") && text.endsWith("\n</memory>"))
        assertTrue(text.contains("[m-a] Prefers answers in British English"))
        assertTrue(text.contains("[m-b] Works on Autobot"))
        assertFalse(text.contains("m-c"))
        assertTrue(text.lines().all { it.length <= 300 })
        assertNull(memory.recall("weather tomorrow?"))
    }

    @Test
    fun sanitizeDropsUnusableImports() {
        assertNull(MemoryRules.sanitize(note("../evil", "x")))
        assertNull(MemoryRules.sanitize(note("m-1", "  ")))
        assertEquals(listOf("a"), MemoryRules.sanitize(note("m-1", " ok ", listOf("#A")))!!.tags)
    }

    @Test
    fun screenFilterMatchesTextAndTagsNewestFirst() {
        val notes = listOf(note("m-1", "Coffee black", listOf("food"), updatedAt = 1), note("m-2", "Tea green", listOf("food"), updatedAt = 2))
        assertEquals(listOf("m-2", "m-1"), MemoryRules.filter(notes, "#food").map { it.id })
        assertEquals(listOf("m-1"), MemoryRules.filter(notes, "COFFEE bl").map { it.id })
        assertEquals(2, MemoryRules.filter(notes, " ").size)
    }
}
