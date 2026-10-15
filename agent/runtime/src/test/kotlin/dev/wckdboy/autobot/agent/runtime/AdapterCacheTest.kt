package dev.wckdboy.autobot.agent.runtime

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AdapterCacheTest {
    private class Adapter(val provider: String, val key: String)

    private val decrypts = AtomicInteger()
    private val builds = AtomicInteger()

    /** Stands in for AgentHost's resolver: provider row → Keystore decrypt → client + adapter. */
    private val cache = AdapterCache { id ->
        if (id == "gone") return@AdapterCache null
        val key = "sk-$id-${decrypts.incrementAndGet()}"
        builds.incrementAndGet()
        Adapter(id, key)
    }

    @Test
    fun manyStepsBuildOneAdapterAndDecryptOneKey() = runBlocking {
        // An agent asks for its adapter several times a step (call, retries, helper model, title).
        val first = cache.get("openrouter")
        repeat(50) { assertSame(first, cache.get("openrouter")) }
        assertEquals(1, builds.get())
        assertEquals(1, decrypts.get())
    }

    @Test
    fun parallelStepsShareOneAdapter() = runBlocking {
        val all = (1..16).map { async(Dispatchers.Default) { cache.get("p") } }.awaitAll()
        assertEquals(1, all.toSet().size)
    }

    @Test
    fun changesRebuild() = runBlocking {
        val before = cache.get("p")
        cache.clear()
        val after = cache.get("p")
        assertNotSame(before, after)
        assertEquals(2, decrypts.get())
        assertSame(after, cache.get("p"))
    }

    @Test
    fun unknownProviderIsNotCached() = runBlocking {
        assertNull(cache.get("gone"))
        assertEquals(0, builds.get())
    }
}
