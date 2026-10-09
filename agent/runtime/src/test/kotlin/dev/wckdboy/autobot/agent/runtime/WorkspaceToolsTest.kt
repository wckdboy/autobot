package dev.wckdboy.autobot.agent.runtime

import dev.wckdboy.autobot.agent.core.llm.LlmErrorCode
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.tools.ToolOutput
import dev.wckdboy.autobot.agent.core.tools.ToolRunContext
import dev.wckdboy.autobot.agent.core.tools.UserQuestion
import dev.wckdboy.autobot.agent.runtime.ProviderLlmAdapter.Companion.toFailure
import dev.wckdboy.autobot.agent.runtime.workspace.FileTools
import dev.wckdboy.autobot.agent.runtime.workspace.Workspace
import dev.wckdboy.autobot.agent.runtime.workspace.WorkspaceContexts
import dev.wckdboy.autobot.providers.remote.ChatEvent
import dev.wckdboy.autobot.providers.remote.ErrorKind
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkspaceToolsTest {

    private lateinit var dir: File
    private lateinit var workspace: Workspace
    private lateinit var tools: FileTools

    private val run = object : ToolRunContext {
        override val callId = "c"
        override val sessionId = "s"
        override fun deferContext(text: String) = Unit
        override suspend fun log(event: SessionEvent) = Unit
        override suspend fun askUser(question: UserQuestion): List<String>? = null
    }

    private fun args(vararg pairs: Pair<String, Any>) = JsonObject(
        pairs.associate { (k, v) ->
            k to when (v) {
                is Boolean -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        },
    )

    private suspend fun call(name: String, vararg pairs: Pair<String, Any>): ToolOutput =
        tools.all.single { it.name == name }.execute(args(*pairs), run)

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("ws").toFile()
        workspace = Workspace(dir)
        tools = FileTools(workspace)
    }

    @Test
    fun pathsCannotEscapeTheWorkspace() {
        assertEquals(File(workspace.root, "a/b.md"), workspace.resolve("a/b.md"))
        assertEquals(File(workspace.root, "a.md"), workspace.resolve("/workspace/a.md"))
        assertEquals(workspace.root, workspace.resolve("/workspace"))
        listOf("../x", "/etc/passwd", "a/../../x", "/workspace/../x").forEach { bad ->
            assertTrue(bad, runCatching { workspace.resolve(bad) }.exceptionOrNull() is Workspace.EscapeException)
        }
        assertEquals("/workspace/a/b.md", workspace.display(File(workspace.root, "a/b.md")))
    }

    @Test
    fun writeCreatesButOverwriteRequiresARecentRead() = runTest {
        assertFalse(call("write", "file_path" to "notes/a.md", "content" to "one").isError)
        assertEquals("one", File(workspace.root, "notes/a.md").readText())

        val fresh = FileTools(workspace) // a different session has not read it
        val denied = fresh.all.single { it.name == "write" }.execute(args("file_path" to "notes/a.md", "content" to "two"), run)
        assertEquals("NOT_OBSERVED", denied.code)

        assertTrue(call("read", "file_path" to "notes/a.md").content.contains("1→one"))
        assertFalse(call("write", "file_path" to "notes/a.md", "content" to "two").isError)
    }

    @Test
    fun editNeedsAUniqueMatchUnlessReplaceAll() = runTest {
        File(workspace.root, "f.txt").writeText("x y x")
        call("read", "file_path" to "f.txt")
        assertEquals("AMBIGUOUS", call("edit", "file_path" to "f.txt", "old_string" to "x", "new_string" to "z").code)
        assertEquals("NO_MATCH", call("edit", "file_path" to "f.txt", "old_string" to "q", "new_string" to "z").code)
        assertFalse(call("edit", "file_path" to "f.txt", "old_string" to "x", "new_string" to "z", "replace_all" to true).isError)
        assertEquals("z y z", File(workspace.root, "f.txt").readText())
    }

    @Test
    fun readPaginatesAndRejectsBinary() = runTest {
        File(workspace.root, "long.txt").writeText((1..10).joinToString("\n") { "line$it" })
        val page = call("read", "file_path" to "long.txt", "offset" to 3, "limit" to 2).content
        assertTrue(page.contains("3→line3"))
        assertTrue(page.contains("4→line4"))
        assertTrue(page.contains("6 more lines"))

        File(workspace.root, "bin").writeBytes(byteArrayOf(0, 1, 2, -1, -2))
        assertEquals("BINARY", call("read", "file_path" to "bin").code)
    }

    @Test
    fun globAndGrepFindFiles() = runTest {
        File(workspace.root, "a").mkdirs()
        File(workspace.root, "a/one.md").writeText("alpha\nneedle here")
        File(workspace.root, "two.txt").writeText("needle")
        assertEquals("/workspace/a/one.md", call("glob", "pattern" to "**/*.md").content)
        val grep = call("grep", "pattern" to "need+le", "include" to "*.md").content
        assertEquals("/workspace/a/one.md:2: needle here", grep)
    }

    @Test
    fun skillsAreDiscoveredFromFrontmatter() {
        val skillDir = File(workspace.root, ".agents/skills/release").apply { mkdirs() }
        File(skillDir, "SKILL.md").writeText("---\nname: release\ndescription: Cut a release\n---\nSteps…")
        File(workspace.root, ".agents/skills/hidden.md").writeText("---\nname: hidden\ndisable-model-invocation: true\n---\nx")
        val skills = WorkspaceContexts(workspace).skills()
        assertEquals(listOf("release"), skills.map { it.name })
        assertEquals("Cut a release", skills.single().description)
    }

    @Test
    fun htmlIsReducedToText() {
        val text = WebFetchTool.htmlToText(
            "<html><head><title>t</title><style>x{}</style></head><body><h1>Hi</h1><p>a &amp; b</p><script>evil()</script></body></html>",
        )
        assertEquals("Hi\n\na & b", text)
    }

    @Test
    fun providerErrorsMapToRetryClasses() {
        assertEquals(LlmErrorCode.RATE_LIMIT, ChatEvent.Error(ErrorKind.RATE_LIMITED, "429").toFailure().code)
        assertEquals(LlmErrorCode.BLOCKED, ChatEvent.Error(ErrorKind.BLOCKED, "offline").toFailure().code)
        assertEquals(
            LlmErrorCode.CONTEXT_WINDOW_EXCEEDED,
            ChatEvent.Error(ErrorKind.BAD_REQUEST, "HTTP 400: This model's maximum context length is 65536 tokens").toFailure().code,
        )
    }
}
