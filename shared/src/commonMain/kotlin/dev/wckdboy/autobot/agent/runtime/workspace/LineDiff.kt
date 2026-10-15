package dev.wckdboy.autobot.agent.runtime.workspace

/**
 * Compact line diffs for the "what did this step change" view: Myers' O(ND) algorithm on lines
 * after trimming the common prefix and suffix, rendered as unified-diff hunks with a little
 * context. Very different inputs (more than [MAX_EDITS] edits) fall back to "replace the middle",
 * which is still a correct diff, just not a minimal one.
 */
object LineDiff {

    enum class Op { EQUAL, DELETE, INSERT }

    data class Edit(val op: Op, val line: String)

    /**
     * A rendered diff: `@@ -1,3 +1,4 @@`, ` context`, `-removed`, `+added` lines. [rewritten]: the
     * changed part was too large to diff ([MAX_DIFF_LINES]); [lines] is empty and [added] /
     * [removed] count the changed region's lines.
     */
    data class Hunks(val lines: List<String>, val added: Int, val removed: Int, val truncated: Boolean, val rewritten: Boolean = false)

    /** Edit distance searched before falling back to "replace the middle" (the trace is O(D²) memory). */
    const val MAX_EDITS = 1000

    /** Changed lines (both sides, after the common prefix and suffix) above which no diff is computed. */
    const val MAX_DIFF_LINES = 3000
    const val CONTEXT = 2
    const val MAX_LINES = 160
    const val MAX_LINE_CHARS = 300

    /** Splits text into lines; a trailing newline does not make an extra empty line. */
    fun lines(text: String): List<String> = if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split('\n').map { it.removeSuffix("\r") }

    /** The edit script turning [a] into [b]. */
    fun diff(a: List<String>, b: List<String>): List<Edit> {
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++
        val midA = a.subList(prefix, a.size - suffix)
        val midB = b.subList(prefix, b.size - suffix)
        val middle = myers(midA, midB) ?: (midA.map { Edit(Op.DELETE, it) } + midB.map { Edit(Op.INSERT, it) })
        return a.subList(0, prefix).map { Edit(Op.EQUAL, it) } + deletesFirst(middle) + a.subList(a.size - suffix, a.size).map { Edit(Op.EQUAL, it) }
    }

    /** Within each run of changes, removed lines come before added ones (as in unified diffs). */
    private fun deletesFirst(edits: List<Edit>): List<Edit> {
        val out = ArrayList<Edit>(edits.size)
        val inserts = ArrayList<Edit>()
        for (e in edits) {
            when (e.op) {
                Op.DELETE -> out += e
                Op.INSERT -> inserts += e
                Op.EQUAL -> {
                    out += inserts
                    inserts.clear()
                    out += e
                }
            }
        }
        out += inserts
        return out
    }

    /** Unified hunks of [before] → [after] (null = the file did not exist). */
    fun hunks(before: String?, after: String?, context: Int = CONTEXT, maxLines: Int = MAX_LINES): Hunks {
        val a = lines(before.orEmpty())
        val b = lines(after.orEmpty())
        val (changedA, changedB) = changedRegion(a, b)
        // A (near) full rewrite of a big file: no edit script (megabytes of objects for a diff
        // nobody reads in full), just the counts and "file rewritten".
        if (changedA + changedB > MAX_DIFF_LINES) return Hunks(emptyList(), changedB, changedA, truncated = true, rewritten = true)
        return render(diff(a, b), context, maxLines)
    }

    /** Lines of [a] and [b] between their common prefix and suffix. */
    private fun changedRegion(a: List<String>, b: List<String>): Pair<Int, Int> {
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++
        return (a.size - prefix - suffix) to (b.size - prefix - suffix)
    }

    fun render(edits: List<Edit>, context: Int = CONTEXT, maxLines: Int = MAX_LINES): Hunks {
        val added = edits.count { it.op == Op.INSERT }
        val removed = edits.count { it.op == Op.DELETE }
        val changed = edits.indices.filter { edits[it].op != Op.EQUAL }
        if (changed.isEmpty()) return Hunks(emptyList(), 0, 0, false)

        // Group changed indices whose context windows touch.
        val ranges = mutableListOf<IntRange>()
        var start = changed.first()
        var end = start
        for (i in changed.drop(1)) {
            if (i - end > 2 * context + 1) {
                ranges += (start - context).coerceAtLeast(0)..(end + context).coerceAtMost(edits.lastIndex)
                start = i
            }
            end = i
        }
        ranges += (start - context).coerceAtLeast(0)..(end + context).coerceAtMost(edits.lastIndex)

        // Line numbers before each edit index.
        val oldAt = IntArray(edits.size + 1)
        val newAt = IntArray(edits.size + 1)
        edits.forEachIndexed { i, e ->
            oldAt[i + 1] = oldAt[i] + if (e.op != Op.INSERT) 1 else 0
            newAt[i + 1] = newAt[i] + if (e.op != Op.DELETE) 1 else 0
        }

        val out = mutableListOf<String>()
        var truncated = false
        loop@ for (range in ranges) {
            val oldCount = oldAt[range.last + 1] - oldAt[range.first]
            val newCount = newAt[range.last + 1] - newAt[range.first]
            val header = "@@ -${oldAt[range.first] + if (oldCount > 0) 1 else 0},$oldCount +${newAt[range.first] + if (newCount > 0) 1 else 0},$newCount @@"
            if (out.size >= maxLines) {
                truncated = true
                break
            }
            out += header
            for (i in range) {
                if (out.size >= maxLines) {
                    truncated = true
                    break@loop
                }
                val e = edits[i]
                val mark = when (e.op) {
                    Op.EQUAL -> ' '
                    Op.DELETE -> '-'
                    Op.INSERT -> '+'
                }
                out += mark + if (e.line.length > MAX_LINE_CHARS) e.line.take(MAX_LINE_CHARS) + "…" else e.line
            }
        }
        return Hunks(out, added, removed, truncated)
    }

    /**
     * Myers' greedy algorithm, keeping each round's frontier for the backtrack (O(D²) memory).
     * Null when the inputs need more than [MAX_EDITS] edits.
     */
    private fun myers(a: List<String>, b: List<String>): List<Edit>? {
        val n = a.size
        val m = b.size
        if (n == 0) return b.map { Edit(Op.INSERT, it) }
        if (m == 0) return a.map { Edit(Op.DELETE, it) }
        val max = n + m
        val offset = max + 1
        val v = IntArray(2 * max + 3)
        val trace = ArrayList<IntArray>()
        for (d in 0..minOf(max, MAX_EDITS)) {
            // Frontier before this round, for k in -(d+1)..(d+1).
            trace += v.copyOfRange(offset - d - 1, offset + d + 2)
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) v[offset + k + 1] else v[offset + k - 1] + 1
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) {
                    x++
                    y++
                }
                v[offset + k] = x
                if (x >= n && y >= m) return backtrack(a, b, trace, d)
                k += 2
            }
        }
        return null
    }

    private fun backtrack(a: List<String>, b: List<String>, trace: List<IntArray>, depth: Int): List<Edit> {
        val out = ArrayList<Edit>()
        var x = a.size
        var y = b.size
        for (d in depth downTo 0) {
            val frontier = trace[d]
            fun at(k: Int) = frontier[k + d + 1]
            val k = x - y
            val prevK = if (k == -d || (k != d && at(k - 1) < at(k + 1))) k + 1 else k - 1
            val prevX = at(prevK)
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) {
                out += Edit(Op.EQUAL, a[x - 1])
                x--
                y--
            }
            if (d > 0) {
                if (prevK == k + 1) {
                    out += Edit(Op.INSERT, b[y - 1])
                    y--
                } else {
                    out += Edit(Op.DELETE, a[x - 1])
                    x--
                }
            }
        }
        out.reverse()
        return out
    }
}
