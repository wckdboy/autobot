package dev.wckdboy.autobot.agent.runtime

import dev.wckdboy.autobot.agent.core.TurnTrace
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.tools.ToolOutput
import dev.wckdboy.autobot.agent.core.tools.ToolRunContext
import dev.wckdboy.autobot.agent.core.tools.UserQuestion
import dev.wckdboy.autobot.agent.runtime.workspace.FileTools
import dev.wckdboy.autobot.agent.runtime.workspace.Workspace
import dev.wckdboy.autobot.agent.runtime.workspace.WorkspaceJournal
import dev.wckdboy.autobot.shared.session.ChangeOp
import dev.wckdboy.autobot.shared.session.FileChange
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkspaceJournalTest {

    private lateinit var dir: File
    private lateinit var workspace: Workspace
    private lateinit var journal: WorkspaceJournal
    private lateinit var undoDir: File
    private lateinit var tools: FileTools
    private val logged = mutableListOf<SessionEvent>()

    private fun run(id: String, session: String = "s") = object : ToolRunContext {
        override val callId = id
        override val sessionId = session
        override fun deferContext(text: String) = Unit
        override suspend fun log(event: SessionEvent) {
            logged += event
        }
        override suspend fun askUser(question: UserQuestion): List<String>? = null
    }

    private fun args(vararg pairs: Pair<String, Any>) = JsonObject(
        pairs.associate { (k, v) -> k to if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString()) },
    )

    /** Runs tool [name] as call [id] of [turn]. */
    private suspend fun call(turn: Int, id: String, name: String, vararg pairs: Pair<String, Any>, session: String = "s"): ToolOutput =
        withContext(TurnTrace("s", turn)) { tools.all.single { it.name == name }.execute(args(*pairs), run(id, session)) }

    private fun file(path: String) = File(dir, path)
    private fun text(path: String) = file(path).takeIf { it.exists() }?.readText()

    private fun setUp(maxSession: Long = WorkspaceJournal.MAX_SESSION_BYTES, maxFile: Long = WorkspaceJournal.MAX_FILE_BYTES) {
        dir = Files.createTempDirectory("ws").toFile()
        undoDir = Files.createTempDirectory("undo").toFile()
        workspace = Workspace(dir.toOkioPath())
        journal = WorkspaceJournal(workspace, undoDir.toOkioPath(), maxSession, maxFile)
        tools = FileTools(workspace, journal)
    }

    @Before
    fun before() = setUp()

    @Test
    fun writeReportsADiffAndUndoRestoresTheOriginal() = runTest {
        file("notes.md").writeText("one\ntwo\n")
        call(1, "r", "read", "file_path" to "notes.md")
        val out = call(1, "w", "write", "file_path" to "notes.md", "content" to "one\nTWO\nthree\n")
        val change = FileChange.from(out.meta)!!
        assertEquals(ChangeOp.MODIFIED, change.op)
        assertEquals(listOf("@@ -1,2 +1,3 @@", " one", "-two", "+TWO", "+three"), change.diff)
        assertEquals(2 to 1, change.added to change.removed)
        assertTrue(change.undoable)

        val result = journal.revertCalls("s", listOf("w"))!!
        assertEquals("one\ntwo\n", text("notes.md"))
        assertEquals(listOf("notes.md"), result.restored)
        assertEquals(listOf("w"), result.groups)

        // The revert is journaled: redo puts the agent's version back.
        val redo = journal.redo("s", result.id)!!
        assertEquals(result.id, redo.redoOf)
        assertEquals("one\nTWO\nthree\n", text("notes.md"))
        assertNull("a redo is not repeatable", journal.redo("s", result.id))
        // ... and the step can be undone again.
        assertNotNull(journal.revertCalls("s", listOf("w")))
        assertEquals("one\ntwo\n", text("notes.md"))
    }

    @Test
    fun undoTurnRestoresInReverseOrderAndDeletesCreatedFiles() = runTest {
        file("a.md").writeText("original\n")
        call(2, "r", "read", "file_path" to "a.md")
        call(2, "e1", "edit", "file_path" to "a.md", "old_string" to "original", "new_string" to "first")
        call(2, "e2", "edit", "file_path" to "a.md", "old_string" to "first", "new_string" to "second")
        call(2, "w", "write", "file_path" to "dir/b.md", "content" to "new file")
        assertEquals(ChangeOp.CREATED, FileChange.from(call(3, "w3", "write", "file_path" to "c.md", "content" to "turn 3").meta)!!.op)

        val result = journal.revertTurn("s", 2)!!
        assertEquals("original\n", text("a.md"))
        assertFalse(file("dir/b.md").exists())
        assertEquals("turn 3", text("c.md"))
        assertEquals(listOf("a.md"), result.restored)
        assertEquals(listOf("dir/b.md"), result.deleted)
        assertEquals(2, result.turn)
        assertEquals(listOf("e1", "e2", "w"), result.groups)
        assertNull("nothing left to undo in turn 2", journal.revertTurn("s", 2))
    }

    @Test
    fun undoingAStepAlsoUndoesLaterChangesToTheSameFile() = runTest {
        call(1, "w1", "write", "file_path" to "a.md", "content" to "v1")
        call(1, "r", "read", "file_path" to "a.md")
        call(2, "w2", "write", "file_path" to "a.md", "content" to "v2")
        call(2, "w3", "write", "file_path" to "b.md", "content" to "b")

        val result = journal.revertCalls("s", listOf("w1"))!!
        assertFalse(file("a.md").exists())
        assertEquals("b", text("b.md"))
        assertEquals(listOf("w1", "w2"), result.groups)
        assertEquals(listOf("a.md"), result.deleted)
    }

    @Test
    fun deleteThenRecreateIsUndoneStepByStep() = runTest {
        file("x.md").writeText("keep me")
        call(1, "r", "read", "file_path" to "x.md")
        val deleted = FileChange.from(call(1, "d", "delete", "file_path" to "x.md").meta)!!
        assertEquals(ChangeOp.DELETED, deleted.op)
        assertEquals(listOf("@@ -1,1 +0,0 @@", "-keep me"), deleted.diff)
        call(1, "w", "write", "file_path" to "x.md", "content" to "replacement")

        // Undo only the re-create: the file is gone again (as after the delete).
        journal.revertCalls("s", listOf("w"))!!
        assertFalse(file("x.md").exists())
        // Undo the delete: the original is back.
        val back = journal.revertCalls("s", listOf("d"))!!
        assertEquals("keep me", text("x.md"))
        assertEquals(listOf("x.md"), back.restored)
    }

    @Test
    fun subagentCallsCountForTheParentTurn() = runTest {
        call(4, "sub-call", "write", "file_path" to "s.md", "content" to "from a subagent", session = "child")
        val result = journal.revertTurn("s", 4)!!
        assertFalse(file("s.md").exists())
        assertEquals(listOf("child:sub-call"), result.groups)
    }

    @Test
    fun sizeCapPrunesOldestTurnsAndSkipsHugeFiles() = runTest {
        setUp(maxSession = 100, maxFile = 60)
        repeat(3) { i -> file("f$i.md").writeText("x".repeat(40)) }
        repeat(3) { i -> call(i + 1, "r$i", "read", "file_path" to "f$i.md") }
        call(1, "w0", "write", "file_path" to "f0.md", "content" to "a")
        call(2, "w1", "write", "file_path" to "f1.md", "content" to "b")
        // 80 bytes kept; turn 3 needs 40 more: turn 1 goes.
        call(3, "w2", "write", "file_path" to "f2.md", "content" to "c")
        assertEquals(listOf(SessionEvent.WorkspacePruned(listOf(1), "undo keeps at most 50 MB per chat")), logged)
        assertNull("turn 1's copies are gone", journal.revertTurn("s", 1))
        assertNotNull(journal.revertTurn("s", 2))
        assertEquals("x".repeat(40), text("f1.md"))

        file("big.bin").writeText("y".repeat(70))
        call(4, "rb", "read", "file_path" to "big.bin")
        val big = FileChange.from(call(4, "wb", "write", "file_path" to "big.bin", "content" to "small").meta)!!
        assertFalse(big.undoable)
        assertTrue(big.note!!.startsWith("too large"))
        val result = journal.revertCalls("s", listOf("wb"))!!
        assertEquals(listOf("big.bin"), result.missing)
        assertEquals("small", text("big.bin"))
    }

    @Test
    fun journalSurvivesARestart() = runTest {
        call(1, "w", "write", "file_path" to "a.md", "content" to "a")
        val reopened = WorkspaceJournal(workspace, undoDir.toOkioPath())
        assertNotNull(reopened.revertCalls("s", listOf("w")))
        assertFalse(file("a.md").exists())
        reopened.drop("s")
        assertNull(reopened.revertCalls("s", listOf("w")))
    }
}
