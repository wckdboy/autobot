package dev.wckdboy.autobot.core.models

import dev.wckdboy.autobot.shared.Cancellable
import dev.wckdboy.autobot.shared.HttpRequestSpec
import dev.wckdboy.autobot.shared.HttpSink
import dev.wckdboy.autobot.shared.HttpTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HubsTest {

    /** Answers every request with one canned response and records what was sent. */
    private class Canned(
        private val status: Int,
        private val body: String,
        private val headers: Map<String, String> = emptyMap(),
        private val failure: Pair<Boolean, String>? = null,
    ) : HttpTransport {
        val sent = mutableListOf<HttpRequestSpec>()

        override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable {
            sent += request
            when {
                failure != null -> sink.onFailure(failure.first, failure.second)
                status !in 200..299 -> sink.onResponse(status, headers, body)
                else -> {
                    sink.onResponse(status, headers, null)
                    body.lines().forEach(sink::onLine)
                    sink.onComplete()
                }
            }
            return Cancellable { }
        }
    }

    private suspend fun hubError(block: suspend () -> Unit): HubException {
        try {
            block()
        } catch (e: HubException) {
            return e
        }
        fail("expected a HubException")
        error("unreachable")
    }

    @Test
    fun huggingFaceSearchRequestAndParsing() = runBlocking {
        val body = """[
            {"id":"unsloth/Qwen3.5-4B-GGUF","downloads":120,"likes":7,"gated":false,"tags":["gguf","license:apache-2.0"]},
            {"id":"meta/llama","gated":"manual","tags":[]},
            {"downloads":3}
        ]"""
        val transport = Canned(200, body)
        val results = HuggingFaceHub(transport).search("qwen 4b", gguf = true, token = "hf_x")

        assertEquals(listOf("unsloth/Qwen3.5-4B-GGUF", "meta/llama"), results.map { it.id })
        assertEquals("apache-2.0", results[0].license)
        assertEquals(120L, results[0].downloads)
        assertFalse(results[0].gated)
        assertTrue(results[1].gated)
        val sent = transport.sent.single()
        assertEquals(
            "https://huggingface.co/api/models?search=qwen%204b&sort=downloads&direction=-1&limit=30&filter=gguf" +
                "&expand%5B%5D=downloads&expand%5B%5D=likes&expand%5B%5D=gated&expand%5B%5D=tags",
            sent.url,
        )
        assertEquals("Bearer hf_x", sent.headers["Authorization"])
        assertEquals("models:huggingface", sent.tag)
        assertEquals("GET", sent.method)

        // Publisher listings drop the type filter; Core ML search filters by library.
        assertFalse("filter=" in HuggingFaceApi.searchRequest("", gguf = false, limit = 30, author = "xororz", coreML = false, token = null).url)
        assertTrue("filter=core-ml" in HuggingFaceApi.searchRequest("", gguf = false, limit = 30, author = null, coreML = true, token = null).url)
        assertNull(HuggingFaceApi.searchRequest("", gguf = true, limit = 30, author = null, coreML = false, token = " ").headers["Authorization"])
    }

    @Test
    fun huggingFaceRepoListing() = runBlocking {
        val body = """{"id":"unsloth/Qwen3-VL-2B-Instruct-GGUF","sha":"abc123","gated":false,"cardData":{"license":"apache-2.0"},"siblings":[
            {"rfilename":"Qwen3-VL-2B-Instruct-Q4_0.gguf","size":10,"lfs":{"sha256":"AA","size":10}},
            {"rfilename":"mmproj-F16.gguf","lfs":{"sha256":"bb","size":5}},
            {"rfilename":"README.md","size":1}
        ]}"""
        val transport = Canned(200, body)
        val repo = HuggingFaceHub(transport).repo("unsloth/Qwen3-VL-2B-Instruct-GGUF")
        assertEquals("https://huggingface.co/api/models/unsloth/Qwen3-VL-2B-Instruct-GGUF?blobs=true", transport.sent.single().url)
        assertEquals("abc123", repo.commit)
        assertEquals("apache-2.0", repo.license)
        assertEquals(HfFile("Qwen3-VL-2B-Instruct-Q4_0.gguf", 10, "aa"), repo.files[0])
        assertEquals(5L, repo.files[1].sizeBytes)

        val plan = HuggingFaceApi.plan(repo, repo.files[0], ModelKind.CHAT, AppPlatform.IOS)
        assertEquals(EngineKind.LLAMA, plan.engine)
        assertEquals(listOf(FileRole.MODEL, FileRole.MMPROJ), plan.manifest.files.map { it.role })
        assertEquals("https://huggingface.co/unsloth/Qwen3-VL-2B-Instruct-GGUF/resolve/abc123/mmproj-F16.gguf", plan.manifest.files[1].url)
        assertEquals("https://huggingface.co/a/b/resolve/main/dir/my%20file%23.gguf", HuggingFaceApi.resolveUrl("a/b", null, "dir/my file#.gguf"))
    }

    @Test
    fun hubFailuresAreTyped() = runBlocking {
        val hub = HuggingFaceHub(Canned(403, "", mapOf("x-error-code" to "GatedRepo")))
        assertEquals(HubException.Code.GATED, hubError { hub.repo("meta/llama") }.code)
        assertEquals(HubException.Code.UNAUTHORIZED, hubError { HuggingFaceHub(Canned(401, "")).whoami("bad") }.code)
        assertEquals(HubException.Code.NOT_FOUND, hubError { HuggingFaceHub(Canned(404, "")).repo("x/y") }.code)
        assertEquals(HubException.Code.RATE_LIMITED, hubError { HuggingFaceHub(Canned(429, "")).repo("x/y") }.code)
        assertEquals("HTTP 500", hubError { HuggingFaceHub(Canned(500, "")).repo("x/y") }.message)
        assertEquals("Unexpected response", hubError { HuggingFaceHub(Canned(200, "<html>")).repo("x/y") }.message)
        assertEquals("Unexpected response", hubError { HuggingFaceHub(Canned(200, "[]")).repo("x/y") }.message)
        val blocked = hubError { HuggingFaceHub(Canned(0, "", failure = true to "Offline")).search("", gguf = true) }
        assertEquals(HubException.Code.BLOCKED, blocked.code)
        assertEquals("Offline", blocked.message)
        assertEquals(HubException.Code.FAILED, hubError { HuggingFaceHub(Canned(0, "", failure = false to "reset")).search("", gguf = true) }.code)
        assertEquals("Unexpected whoami response", hubError { HuggingFaceHub(Canned(200, "{}")).whoami("t") }.message)
        assertEquals(HfUser("me", "Me"), HuggingFaceHub(Canned(200, """{"name":"me","fullname":"Me"}""")).whoami("t"))
    }

    @Test
    fun civitaiSearchUsesTheChosenHostAndFiltersResults() = runBlocking {
        val body = """{"items":[
            {"id":1,"name":"Safe","type":"Checkpoint","nsfw":false,"modelVersions":[]},
            {"id":2,"name":"Mature","type":"Checkpoint","nsfw":true,"modelVersions":[]},
            {"id":3,"name":"Person","type":"LORA","nsfw":false,"poi":true,"modelVersions":[]}
        ],"metadata":{"nextCursor":"c2"}}"""
        val transport = Canned(200, body)
        val page = CivitaiHub(transport).search(CivitaiHost.RED, "anime", listOf("Checkpoint", "LORA"), listOf("SD 1.5"), cursor = "c1", key = "k")
        assertEquals(listOf("Safe"), page.items.map { it.name })
        assertEquals("c2", page.nextCursor)
        val sent = transport.sent.single()
        assertEquals(
            "https://civitai.red/api/v1/models?query=anime&types=Checkpoint&types=LORA&baseModels=SD%201.5&sort=Most%20Downloaded" +
                "&limit=20&nsfw=false&primaryFileOnly=true&cursor=c1",
            sent.url,
        )
        assertEquals("Bearer k", sent.headers["Authorization"])
        assertEquals(listOf("Safe", "Mature"), CivitaiApi.parseSearch(HubResponse(200, emptyMap(), body), showMature = true).items.map { it.name })
        assertEquals("https://civitai.com/api/v1/me", CivitaiApi.meRequest(CivitaiHost.COM, "k").url)
        assertEquals("u", CivitaiHub(Canned(200, """{"username":"u"}""")).me(CivitaiHost.COM, "k"))
    }
}
