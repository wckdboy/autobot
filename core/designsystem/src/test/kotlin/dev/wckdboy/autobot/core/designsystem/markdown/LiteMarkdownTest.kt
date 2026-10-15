package dev.wckdboy.autobot.core.designsystem.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class LiteMarkdownTest {

    @Test
    fun splitsParagraphsOnBlankLines() {
        val blocks = LiteMarkdown.parse("one\ntwo\n\nthree")
        assertEquals(
            listOf(
                MdBlock.Paragraph(listOf(MdSpan.Text("one\ntwo"))),
                MdBlock.Paragraph(listOf(MdSpan.Text("three"))),
            ),
            blocks,
        )
    }

    @Test
    fun parsesInlineCode() {
        assertEquals(
            listOf(MdSpan.Text("use "), MdSpan.InlineCode("map"), MdSpan.Text(" here")),
            LiteMarkdown.parseInline("use `map` here"),
        )
    }

    @Test
    fun unmatchedBacktickStaysText() {
        assertEquals(listOf(MdSpan.Text("a ` b")), LiteMarkdown.parseInline("a ` b"))
    }

    @Test
    fun parsesFencedCodeBlockWithLanguage() {
        val blocks = LiteMarkdown.parse("before\n```kotlin\nval x = 1\n\nval y = 2\n```\nafter")
        assertEquals(3, blocks.size)
        assertEquals(MdBlock.CodeBlock("kotlin", "val x = 1\n\nval y = 2", closed = true), blocks[1])
    }

    @Test
    fun unterminatedFenceIsOpenCodeBlock() {
        val blocks = LiteMarkdown.parse("```\npartial")
        assertEquals(listOf(MdBlock.CodeBlock(null, "partial", closed = false)), blocks)
    }

    @Test
    fun emptyInputHasNoBlocks() {
        assertEquals(emptyList<MdBlock>(), LiteMarkdown.parse(""))
    }

    @Test
    fun streamingParseMatchesAFullParseAtEveryStep() {
        val samples = listOf(
            "Use `reversed()`.\n\n```kotlin\nval xs = listOf(1)\n\nprintln(xs)\n```\nThat's it.\n\nMore `code` and\ntext",
            "a\r\nb\r\n\r\n```\nx\n``\n```\n\n\n```py\nopen",
            "``` \n```\n```\n`` ` ```x\n\n  ```  \ny",
        )
        val random = java.util.Random(7)
        for (text in samples) {
            var previous: LiteMarkdown.Parsed? = null
            var at = 0
            while (at < text.length) {
                at = minOf(text.length, at + 1 + random.nextInt(6))
                val prefix = text.substring(0, at)
                val next = LiteMarkdown.parse(prefix, previous)
                assertEquals("prefix ${prefix.length}", LiteMarkdown.parse(prefix), next.blocks)
                previous = next
            }
        }
    }

    @Test
    fun streamingKeepsFinishedBlocksAsTheSameInstances() {
        val first = LiteMarkdown.parse("one\n\ntwo", null)
        val second = LiteMarkdown.parse("one\n\ntwo three", first)
        assertEquals(true, first.blocks[0] === second.blocks[0])
        assertEquals(MdBlock.Paragraph(listOf(MdSpan.Text("two three"))), second.blocks[1])
    }
}
