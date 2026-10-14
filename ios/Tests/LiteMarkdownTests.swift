import Testing
@testable import Autobot

/// Port of `LiteMarkdownTest.kt`.
struct LiteMarkdownTests {
    @Test func splitsParagraphsOnBlankLines() {
        #expect(LiteMarkdown.parse("one\ntwo\n\nthree") == [.paragraph([.text("one\ntwo")]), .paragraph([.text("three")])])
    }

    @Test func parsesInlineCode() {
        #expect(LiteMarkdown.parseInline("use `map` here") == [.text("use "), .inlineCode("map"), .text(" here")])
    }

    @Test func unmatchedBacktickStaysText() {
        #expect(LiteMarkdown.parseInline("a ` b") == [.text("a ` b")])
    }

    @Test func parsesFencedCodeBlockWithLanguage() {
        let blocks = LiteMarkdown.parse("before\n```kotlin\nval x = 1\n\nval y = 2\n```\nafter")
        #expect(blocks.count == 3)
        #expect(blocks[1] == .codeBlock(language: "kotlin", code: "val x = 1\n\nval y = 2", closed: true))
    }

    @Test func unterminatedFenceIsOpenCodeBlock() {
        #expect(LiteMarkdown.parse("```\npartial") == [.codeBlock(language: nil, code: "partial", closed: false)])
    }

    @Test func emptyInputHasNoBlocks() {
        #expect(LiteMarkdown.parse("").isEmpty)
    }

    @Test func emptyInlineCodeStaysLiteral() {
        #expect(LiteMarkdown.parseInline("a `` b") == [.text("a "), .text("``"), .text(" b")])
    }
}
