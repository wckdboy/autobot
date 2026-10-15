package dev.wckdboy.autobot.agent.runtime

import dev.wckdboy.autobot.agent.runtime.workspace.LineDiff
import dev.wckdboy.autobot.agent.runtime.workspace.LineDiff.Op
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LineDiffTest {

    /** Applying the script to `a` must give `b`; equal lines must really be equal. */
    private fun check(a: List<String>, b: List<String>): List<LineDiff.Edit> {
        val edits = LineDiff.diff(a, b)
        assertEquals(a, edits.filter { it.op != Op.INSERT }.map { it.line })
        assertEquals(b, edits.filter { it.op != Op.DELETE }.map { it.line })
        return edits
    }

    @Test
    fun minimalScriptForAClassicExample() {
        val edits = check("ABCABBA".map { "$it" }, "CBABAC".map { "$it" })
        // Myers' paper: D = 5.
        assertEquals(5, edits.count { it.op != Op.EQUAL })
    }

    @Test
    fun emptyAndIdenticalInputs() {
        assertEquals(emptyList<LineDiff.Edit>(), check(emptyList(), emptyList()))
        assertTrue(check(listOf("a", "b"), listOf("a", "b")).all { it.op == Op.EQUAL })
        assertTrue(check(emptyList(), listOf("x", "y")).all { it.op == Op.INSERT })
        assertTrue(check(listOf("x", "y"), emptyList()).all { it.op == Op.DELETE })
    }

    @Test
    fun randomEditsRoundTrip() {
        val random = Random(7)
        repeat(200) {
            val a = List(random.nextInt(0, 30)) { "l${random.nextInt(0, 6)}" }
            val b = a.toMutableList().apply {
                repeat(random.nextInt(0, 8)) {
                    when (random.nextInt(3)) {
                        0 -> if (isNotEmpty()) removeAt(random.nextInt(size))
                        1 -> add(random.nextInt(size + 1), "n${random.nextInt(0, 6)}")
                        else -> if (isNotEmpty()) set(random.nextInt(size), "m${random.nextInt(0, 6)}")
                    }
                }
            }
            check(a, b)
        }
    }

    @Test
    fun hunksShowContextAndCounts() {
        val before = (1..20).joinToString("\n") { "line $it" } + "\n"
        val after = before.replace("line 5\n", "line five\n").replace("line 18\n", "")
        val h = LineDiff.hunks(before, after)
        assertEquals(1, h.added)
        assertEquals(2, h.removed)
        assertEquals(
            listOf(
                "@@ -3,5 +3,5 @@", " line 3", " line 4", "-line 5", "+line five", " line 6", " line 7",
                "@@ -16,5 +16,4 @@", " line 16", " line 17", "-line 18", " line 19", " line 20",
            ),
            h.lines,
        )
        assertFalse(h.truncated)
    }

    @Test
    fun newFileIsAllAddedAndLongDiffsAreCapped() {
        val created = LineDiff.hunks(null, "a\nb\n")
        assertEquals(listOf("@@ -0,0 +1,2 @@", "+a", "+b"), created.lines)
        val big = LineDiff.hunks(null, (1..500).joinToString("\n"))
        assertEquals(500, big.added)
        assertEquals(LineDiff.MAX_LINES, big.lines.size)
        assertTrue(big.truncated)
    }
}
