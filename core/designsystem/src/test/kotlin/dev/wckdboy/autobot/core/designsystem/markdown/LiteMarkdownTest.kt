package dev.wckdboy.autobot.core.designsystem.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parser behaviour beyond the shared corpus (`MarkdownConformanceTest`): streaming and speed. */
class LiteMarkdownTest {

    @Test
    fun streamingParseMatchesAFullParseAtEveryStep() {
        val random = java.util.Random(7)
        for (text in SAMPLES) {
            var previous: LiteMarkdown.Parsed? = null
            var at = 0
            while (at < text.length) {
                at = minOf(text.length, at + 1 + random.nextInt(6))
                val prefix = text.substring(0, at)
                val next = LiteMarkdown.parse(prefix, previous)
                assertEquals("prefix ${prefix.length} of ${text.take(30)}", LiteMarkdown.parse(prefix), next.blocks)
                previous = next
            }
        }
    }

    @Test
    fun everyPrefixOfTheSamplesStreamsLikeAFullParse() {
        for (text in SAMPLES + listOf(RICH)) {
            var previous: LiteMarkdown.Parsed? = null
            for (end in 1..text.length) {
                val prefix = text.substring(0, end)
                val next = LiteMarkdown.parse(prefix, previous)
                assertEquals("prefix $end", LiteMarkdown.parse(prefix), next.blocks)
                previous = next
            }
        }
    }

    @Test
    fun streamingKeepsFinishedBlocksAsTheSameInstances() {
        val first = LiteMarkdown.parse("one\n\ntwo", null)
        val second = LiteMarkdown.parse("one\n\ntwo three", first)
        assertSame(first.blocks[0], second.blocks[0])
        assertEquals(MdBlock.Paragraph(listOf(MdSpan.Text("two three"))), second.blocks[1])
    }

    @Test
    fun anUnfinishedLineAfterAListCanStillJoinIt() {
        // "2" may become "2." (the list's next item): the list is re-parsed with it.
        val first = LiteMarkdown.parse("1. a\n\n2", null)
        val second = LiteMarkdown.parse("1. a\n\n2. b", first)
        assertEquals(LiteMarkdown.parse("1. a\n\n2. b"), second.blocks)
        assertEquals(1, second.blocks.size)
    }

    @Test
    fun plainTextDropsTheSyntax() {
        val blocks = LiteMarkdown.parse("# Title\n\nSome **bold** and [a link](https://a.example).\n\n- one\n- two\n\n| a | b |\n|---|---|\n| 1 | 2 |")
        assertEquals("Title\nSome bold and a link.\none\ntwo\na\tb\n1\t2", LiteMarkdown.plainText(blocks))
    }

    @Test
    fun linkHostsAndInsecureLinks() {
        assertEquals("example.com", MdLinks.host("https://user@Example.com:8080/a?b#c"))
        assertEquals("me@example.com", MdLinks.host("mailto:me@example.com?subject=hi"))
        assertTrue(MdLinks.isInsecure("http://x.example"))
        assertEquals(false, MdLinks.isInsecure("https://x.example"))
    }

    /**
     * A 20 KB answer, streamed a few characters per update: each update re-parses only the
     * tail. Budget: well under 1 ms per update on a laptop JVM (phones are slower, but the
     * cost is one block's parse, not the answer's).
     */
    @Test
    fun aTwentyKilobyteAnswerStreamsWellUnderAMillisecondPerUpdate() {
        val answer = buildString { while (length < 20_000) append(RICH).append("\n\n") }
        repeat(3) { streamAll(answer) } // JIT warm-up.
        val perUpdate = streamAll(answer)
        val full = (0 until 20).map { timeNanos { LiteMarkdown.parse(answer) } }.sorted()[10]
        println(
            "markdown: 20 KB full parse %.3f ms; streamed %d updates, mean %.4f ms, p99 %.4f ms, max %.4f ms".format(
                full / 1e6, perUpdate.size, perUpdate.average() / 1e6,
                perUpdate.sorted()[perUpdate.size * 99 / 100] / 1e6, perUpdate.max() / 1e6,
            ),
        )
        assertTrue("mean per update ${perUpdate.average() / 1e6} ms", perUpdate.average() < 1_000_000)
        assertTrue("p99 per update", perUpdate.sorted()[perUpdate.size * 99 / 100] < 2_000_000)
    }

    private fun streamAll(answer: String): List<Long> {
        val times = ArrayList<Long>()
        var previous: LiteMarkdown.Parsed? = null
        var at = 0
        while (at < answer.length) {
            at = minOf(answer.length, at + 4)
            val prefix = answer.substring(0, at)
            val start = System.nanoTime()
            previous = LiteMarkdown.parse(prefix, previous)
            times += System.nanoTime() - start
        }
        assertEquals(LiteMarkdown.parse(answer), previous!!.blocks)
        return times
    }

    private inline fun timeNanos(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return System.nanoTime() - start
    }

    companion object {
        val RICH = """
            ## Getting there

            The next train **ICE 527** leaves at *17:25* from platform ~~4~~ 6 — see [the timetable](https://int.bahn.de/en) or https://example.com/live.

            1. Buy a ticket in the app.
            2. Validate it:
               - on the platform, or
               - [ ] on board (costs `€2` extra)
            3. Board the train.

            > Trains in the evening are busy;
            > book a seat.

            | Train | Departs | Price |
            |:------|:-------:|------:|
            | ICE 527 | 17:25 | €49.90 |
            | RE 7 | 18:02 | €12.00 |

            ```kotlin
            fun fare(km: Int) = km * 0.18

            println(fare(120))
            ```

            Escaped \*stars\*, snake_case_names, math ${'$'}e^{i\pi}+1=0${'$'} and ${'$'}5 for coffee.

            ---
        """.trimIndent()

        val SAMPLES = listOf(
            "Use `reversed()`.\n\n```kotlin\nval xs = listOf(1)\n\nprintln(xs)\n```\nThat's it.\n\nMore `code` and\ntext",
            "a\r\nb\r\n\r\n```\nx\n``\n```\n\n\n```py\nopen",
            "``` \n```\n```\n`` ` ```x\n\n  ```  \ny",
            "1. a\n\n2. b\n\n   more\n\n10. c\n- d\n\n- e\n\nend",
            "Intro:\n| a | b |\n|---|---|\n| 1 | 2 |\n\n| x |\n|-|\n\nAfter\n> q\n> > r\n\n> s",
            "$$\nx\n$$\n\n$$\nunclosed\n\nnext **b** _i_ ~~s~~ [l](https://a.example) <https://b.example>",
            "- [ ] a\n- [x] b\n  - c\n\n    ```\n    d\n    ```\n# h\n***\nend",
        )
    }
}
