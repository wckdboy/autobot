package dev.wckdboy.autobot.agent.runtime

import dev.wckdboy.autobot.agent.core.TurnTrace
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.tools.ToolRunContext
import dev.wckdboy.autobot.agent.core.tools.UserQuestion
import dev.wckdboy.autobot.agent.runtime.workspace.FileTools
import dev.wckdboy.autobot.agent.runtime.workspace.LineDiff
import dev.wckdboy.autobot.agent.runtime.workspace.Workspace
import dev.wckdboy.autobot.agent.runtime.workspace.WorkspaceContexts
import dev.wckdboy.autobot.agent.runtime.workspace.WorkspaceJournal
import dev.wckdboy.autobot.shared.session.FileChange
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** S10 (journal log, diff cap) and S11 (context caches) of the performance audit. */
class PerfWorkspaceTest {
    private val dir = Files.createTempDirectory("ws").toFile()
    private val undo = Files.createTempDirectory("undo").toFile()
    private val workspace = Workspace(dir.toOkioPath())

    private fun journal() = WorkspaceJournal(workspace, undo.toOkioPath())

    private fun sessionDir() = File(undo, "s")

    @Test
    fun journalAppendsALinePerChangeInsteadOfRewritingTheIndex() {
        val journal = journal()
        val file = File(dir, "a.md")
        file.writeText("v0")
        journal.record("s", 1, "c0", workspace.resolve(file.name))
        // The first record writes a log line; there is no index snapshot to rewrite yet.
        val index = File(sessionDir(), "index.json")
        val log = File(sessionDir(), "journal.log")
        assertFalse(index.exists())
        for (i in 1 until 50) {
            file.writeText("v$i")
            journal.record("s", 1 + i / 10, "c$i", workspace.resolve(file.name))
        }
        assertFalse(index.exists())
        assertEquals(50, log.readLines().count { it.isNotBlank() })

        // A fresh journal (process restart) replays the log: undo still works.
        file.writeText("v-final")
        val reloaded = journal()
        val result = reloaded.revertCalls("s", listOf("c10"))!!
        assertEquals(listOf("a.md"), result.restored)
        assertEquals("v10", file.readText())
        // And redo, from the same log.
        assertNotNull(reloaded.redo("s", result.id))
        assertEquals("v-final", file.readText())
        assertEquals(52, log.readLines().count { it.isNotBlank() })
    }

    @Test
    fun journalFoldsTheLogIntoASnapshotNowAndThen() {
        val journal = journal()
        val file = File(dir, "b.md")
        repeat(WorkspaceJournal.COMPACT_EVERY + 5) { i ->
            file.writeText("v$i")
            journal.record("s", 1, "c$i", workspace.resolve(file.name))
        }
        val index = File(sessionDir(), "index.json")
        val log = File(sessionDir(), "journal.log")
        assertTrue(index.exists())
        assertTrue(log.readLines().count { it.isNotBlank() } < WorkspaceJournal.COMPACT_EVERY)
        // Snapshot + remaining lines give the same state after a restart.
        file.writeText("now")
        val result = journal().revertCalls("s", listOf("c0"))!!
        assertEquals(listOf("b.md"), result.restored)
        assertEquals("v0", file.readText())
    }

    @Test
    fun aTornLastLineIsSkipped() {
        val journal = journal()
        val file = File(dir, "c.md")
        file.writeText("old")
        journal.record("s", 1, "c1", workspace.resolve(file.name))
        File(sessionDir(), "journal.log").appendText("{\"gen\":0,\"next\":9,\"rec")
        file.writeText("new")
        assertEquals(listOf("c.md"), journal().revertCalls("s", listOf("c1"))!!.restored)
        assertEquals("old", file.readText())
    }

    @Test
    fun hugeRewritesSkipTheDiff() {
        val before = (1..20_000).joinToString("\n") { "line $it" }
        val after = (1..20_000).joinToString("\n") { "LINE $it" }
        val started = TimeSource.Monotonic.markNow()
        val hunks = LineDiff.hunks(before, after)
        assertTrue("took ${started.elapsedNow()}", started.elapsedNow() < 2.seconds)
        assertTrue(hunks.rewritten)
        assertTrue(hunks.lines.isEmpty())
        assertEquals(20_000 to 20_000, hunks.added to hunks.removed)
        // Small edits in a big file still diff (the common prefix and suffix are trimmed first).
        val edited = before.replace("line 10000\n", "line ten thousand\n")
        val small = LineDiff.hunks(before, edited)
        assertFalse(small.rewritten)
        assertEquals(1 to 1, small.added to small.removed)
    }

    @Test
    fun fileToolsSayAHugeRewriteWasRewritten() = runBlocking {
        val tools = FileTools(workspace, journal())
        File(dir, "big.txt").writeText((1..4_000).joinToString("\n") { "a$it" })
        val run = object : ToolRunContext {
            override val callId = "w"
            override val sessionId = "s"
            override fun deferContext(text: String) = Unit
            override suspend fun log(event: SessionEvent) = Unit
            override suspend fun askUser(question: UserQuestion): List<String>? = null
        }
        tools.all.single { it.name == "read" }.execute(JsonObject(mapOf("file_path" to JsonPrimitive("big.txt"))), run)
        val out = withContext(TurnTrace("s", 1)) {
            tools.all.single { it.name == "write" }.execute(
                JsonObject(mapOf("file_path" to JsonPrimitive("big.txt"), "content" to JsonPrimitive((1..4_000).joinToString("\n") { "b$it" }))),
                run,
            )
        }
        val change = FileChange.from(out.meta)!!
        assertTrue(change.diff.isEmpty())
        assertEquals(4_000, change.added)
        assertTrue(change.note.orEmpty(), change.note.orEmpty().startsWith("file rewritten"))
    }

    @Test
    fun skillsAndInstructionsAreReReadOnlyWhenTheirFilesChange() {
        val contexts = WorkspaceContexts(workspace)
        val skills = File(dir, ".agents/skills").apply { mkdirs() }
        File(skills, "a.md").writeText("---\nname: alpha\ndescription: first\n---\nbody")
        File(dir, "AGENTS.md").writeText("be brief")
        val first = contexts.skills()
        assertEquals(listOf("alpha"), first.map { it.name })
        assertSame(first, contexts.skills())
        assertEquals("be brief", contexts.instructions())

        File(skills, "b.md").writeText("---\nname: beta\ndescription: second\n---\nbody")
        val second = contexts.skills()
        assertNotSame(first, second)
        assertEquals(listOf("alpha", "beta"), second.map { it.name })

        val agents = File(dir, "AGENTS.md")
        agents.writeText("be very brief")
        agents.setLastModified(agents.lastModified() + 2_000)
        assertEquals("be very brief", contexts.instructions())
        agents.delete()
        assertEquals(null, contexts.instructions())
    }
}
