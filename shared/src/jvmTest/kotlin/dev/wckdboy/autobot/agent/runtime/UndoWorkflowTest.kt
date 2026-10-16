package dev.wckdboy.autobot.agent.runtime

import dev.wckdboy.autobot.agent.core.TurnTrace
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.revertText
import dev.wckdboy.autobot.agent.core.tools.ToolOutput
import dev.wckdboy.autobot.agent.core.tools.ToolRunContext
import dev.wckdboy.autobot.agent.core.tools.UserQuestion
import dev.wckdboy.autobot.agent.runtime.workspace.FileTools
import dev.wckdboy.autobot.agent.runtime.workspace.Workspace
import dev.wckdboy.autobot.agent.runtime.workspace.WorkspaceJournal
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** Undo/redo of agent file changes per step and per turn, including files changed behind its back. */
class UndoWorkflowTest {
    private lateinit var dir: File
    private lateinit var journal: WorkspaceJournal
    private lateinit var tools: FileTools

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("ws").toFile()
        val workspace = Workspace(dir.toOkioPath())
        journal = WorkspaceJournal(workspace, Files.createTempDirectory("undo").toFile().toOkioPath())
        tools = FileTools(workspace, journal)
    }

    private fun run(id: String) = object : ToolRunContext {
        override val callId = id
        override val sessionId = "s"
        override fun deferContext(text: String) = Unit
        override suspend fun log(event: SessionEvent) = Unit
        override suspend fun askUser(question: UserQuestion): List<String>? = null
    }

    private suspend fun call(turn: Int, id: String, name: String, vararg pairs: Pair<String, String>): ToolOutput =
        withContext(TurnTrace("s", turn)) {
            tools.all.single { it.name == name }.execute(JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) }), run(id))
        }

    private fun file(path: String) = File(dir, path)
    private fun text(path: String) = file(path).takeIf { it.exists() }?.readText()

    @Test
    fun undoAndRedoACreatedFileStepByStep() = runTest(timeout = 10.seconds) {
        call(1, "w", "write", "file_path" to "new/a.md", "content" to "agent text")
        val undo = journal.revertCalls("s", listOf("w"))!!
        assertFalse(file("new/a.md").exists())
        assertEquals(listOf("new/a.md"), undo.deleted)
        assertNull("nothing left to undo", journal.revertCalls("s", listOf("w")))

        val redo = journal.redo("s", undo.id)!!
        assertEquals("agent text", text("new/a.md"))
        assertEquals(undo.id, redo.redoOf)
        assertNull("redo is one-shot", journal.redo("s", undo.id))
        assertNull("unknown revert", journal.redo("s", "revert:999"))
    }

    @Test
    fun undoAFileChangedExternallyRestoresTheOriginalAndRedoRestoresWhatWasThere() = runTest(timeout = 10.seconds) {
        // Intended: undo puts back the file as it was before the step; redo puts back exactly what
        // the undo replaced (here the user's own later edit), so nothing is ever lost.
        file("notes.md").writeText("original")
        call(1, "r", "read", "file_path" to "notes.md")
        call(1, "w", "write", "file_path" to "notes.md", "content" to "agent")
        file("notes.md").writeText("agent + user edit")

        val undo = journal.revertCalls("s", listOf("w"))!!
        assertEquals("original", text("notes.md"))
        assertEquals(listOf("notes.md"), undo.restored)
        journal.redo("s", undo.id)!!
        assertEquals("agent + user edit", text("notes.md"))
    }

    @Test
    fun undoACreatedFileTheUserAlreadyDeletedReportsNothingDeleted() = runTest(timeout = 10.seconds) {
        call(1, "w", "write", "file_path" to "tmp.md", "content" to "x")
        file("tmp.md").delete()
        val undo = journal.revertCalls("s", listOf("w"))!!
        assertTrue(undo.deleted.isEmpty())
        assertTrue(undo.restored.isEmpty())
        assertFalse(file("tmp.md").exists())
    }

    @Test
    fun undoADeletedFileBringsItBackEvenWithItsFolderGone() = runTest(timeout = 10.seconds) {
        file("docs").mkdirs()
        file("docs/keep.md").writeText("precious")
        call(2, "r", "read", "file_path" to "docs/keep.md")
        call(2, "d", "delete", "file_path" to "docs/keep.md")
        file("docs").delete()
        val undo = journal.revertCalls("s", listOf("d"))!!
        assertEquals("precious", text("docs/keep.md"))
        assertEquals(listOf("docs/keep.md"), undo.restored)
    }

    @Test
    fun undoAndRedoAWholeTurnOfMixedChanges() = runTest(timeout = 10.seconds) {
        file("a.md").writeText("a0")
        file("b.md").writeText("b0")
        call(1, "ra", "read", "file_path" to "a.md")
        call(1, "rb", "read", "file_path" to "b.md")
        call(1, "e", "edit", "file_path" to "a.md", "old_string" to "a0", "new_string" to "a1")
        call(1, "d", "delete", "file_path" to "b.md")
        call(1, "c", "write", "file_path" to "c.md", "content" to "c1")
        call(2, "later", "write", "file_path" to "z.md", "content" to "turn 2")

        val undo = journal.revertTurn("s", 1)!!
        assertEquals("a0" to "b0", text("a.md") to text("b.md"))
        assertFalse(file("c.md").exists())
        assertEquals("turn 2", text("z.md"))
        assertEquals(listOf("a.md", "b.md"), undo.restored)
        assertEquals(listOf("c.md"), undo.deleted)
        assertEquals(1, undo.turn)
        val line = revertText(undo.event())
        assertEquals("The user reverted turn 1's file changes: a.md restored, b.md restored, c.md deleted. Read a file again before editing it.", line)

        val redo = journal.redo("s", undo.id)!!
        assertEquals("a1", text("a.md"))
        assertFalse(file("b.md").exists())
        assertEquals("c1", text("c.md"))
        assertEquals(1, redo.turn)
        // After a redo the turn can be undone again.
        assertTrue(journal.revertTurn("s", 1) != null)
        assertEquals("a0", text("a.md"))
    }

    @Test
    fun undoingAnEarlierStepTakesLaterStepsOnTheSameFileAlongButNotOthers() = runTest(timeout = 10.seconds) {
        call(1, "w1", "write", "file_path" to "f.md", "content" to "1")
        call(1, "r", "read", "file_path" to "f.md")
        call(1, "w2", "edit", "file_path" to "f.md", "old_string" to "1", "new_string" to "2")
        call(1, "g", "write", "file_path" to "g.md", "content" to "g")
        val undo = journal.revertCalls("s", listOf("w1"))!!
        assertEquals(listOf("w1", "w2"), undo.groups)
        assertFalse(file("f.md").exists())
        assertEquals("g", text("g.md"))
    }
}
