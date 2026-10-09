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
}
