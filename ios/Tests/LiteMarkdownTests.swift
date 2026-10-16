import Foundation
import Testing
@testable import Autobot

/// Parser behaviour beyond the shared corpus (`MarkdownConformanceTests`): streaming and speed. Port of `LiteMarkdownTest.kt`.
struct LiteMarkdownTests {
    @Test func streamingKeepsFinishedBlocks() {
        let first = LiteMarkdown.parse("one\n\ntwo", previous: nil)
        let second = LiteMarkdown.parse("one\n\ntwo three", previous: first)
        #expect(second.kept == 1)
        #expect(second.blocks == [.paragraph([.text("one")]), .paragraph([.text("two three")])])
    }

    @Test func anUnfinishedLineAfterAListCanStillJoinIt() {
        let first = LiteMarkdown.parse("1. a\n\n2", previous: nil)
        let second = LiteMarkdown.parse("1. a\n\n2. b", previous: first)
        #expect(second.blocks == LiteMarkdown.parse("1. a\n\n2. b"))
        #expect(second.blocks.count == 1)
    }

    @Test func streamedRichSamplesParseLikeTheWholeText() {
        for text in Self.samples + [Self.rich] {
            var previous: LiteMarkdown.Parsed?
            var prefix = ""
            for ch in text {
                prefix.append(ch)
                previous = LiteMarkdown.parse(prefix, previous: previous)
                #expect(previous?.blocks == LiteMarkdown.parse(prefix), "prefix \(prefix.count)")
            }
        }
    }

    @Test func plainTextDropsTheSyntax() {
        let blocks = LiteMarkdown.parse("# Title\n\nSome **bold** and [a link](https://a.example).\n\n- one\n- two\n\n| a | b |\n|---|---|\n| 1 | 2 |")
        #expect(LiteMarkdown.plainText(blocks) == "Title\nSome bold and a link.\none\ntwo\na\tb\n1\t2")
    }

    @Test func linkHostsAndInsecureLinks() {
        #expect(MdLinks.host("https://user@Example.com:8080/a?b#c") == "example.com")
        #expect(MdLinks.host("mailto:me@example.com?subject=hi") == "me@example.com")
        #expect(MdLinks.isInsecure("http://x.example"))
        #expect(!MdLinks.isInsecure("https://x.example"))
    }

    /**
     A 20 KB answer, streamed four characters per update: each update re-parses only the tail.
     Debug builds (tests) are unoptimized, so the bound here is loose; the printed numbers are the
     ones to read (release builds are several times faster).
     */
    @Test func aTwentyKilobyteAnswerStreamsQuickly() {
        var answer = ""
        while answer.utf8.count < 20_000 { answer += Self.rich + "\n\n" }
        _ = stream(answer)
        let times = stream(answer).sorted()
        let clock = ContinuousClock()
        var fulls: [Double] = []
        for _ in 0 ..< 10 {
            let d = clock.measure { _ = LiteMarkdown.parse(answer) }
            fulls.append(Double(d.components.attoseconds) / 1e15 + Double(d.components.seconds) * 1000)
        }
        let mean = times.reduce(0, +) / Double(times.count)
        let p99 = times[times.count * 99 / 100]
        print(String(format: "markdown (swift): 20 KB full parse %.3f ms; streamed %d updates, mean %.4f ms, p99 %.4f ms, max %.4f ms",
                     fulls.sorted()[5], times.count, mean, p99, times.last ?? 0))
        #expect(mean < 1.0)
        #expect(p99 < 2.0)
    }

    private func stream(_ answer: String) -> [Double] {
        let clock = ContinuousClock()
        var times: [Double] = []
        var previous: LiteMarkdown.Parsed?
        let bytes = Array(answer.utf8)
        var at = 0
        while at < bytes.count {
            at = min(bytes.count, at + 4)
            // Whole characters only (the € sign is three bytes).
            while at < bytes.count && (bytes[at] & 0xC0) == 0x80 { at += 1 }
            let prefix = String(decoding: bytes[..<at], as: UTF8.self)
            let d = clock.measure { previous = LiteMarkdown.parse(prefix, previous: previous) }
            times.append(Double(d.components.attoseconds) / 1e15 + Double(d.components.seconds) * 1000)
        }
        #expect(previous?.blocks == LiteMarkdown.parse(answer))
        return times
    }

    static let rich = """
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

    Escaped \\*stars\\*, snake_case_names, math $e^{i\\pi}+1=0$ and $5 for coffee.

    ---
    """

    static let samples = [
        "Use `reversed()`.\n\n```kotlin\nval xs = listOf(1)\n\nprintln(xs)\n```\nThat's it.\n\nMore `code` and\ntext",
        "a\r\nb\r\n\r\n```\nx\n``\n```\n\n\n```py\nopen",
        "``` \n```\n```\n`` ` ```x\n\n  ```  \ny",
        "1. a\n\n2. b\n\n   more\n\n10. c\n- d\n\n- e\n\nend",
        "Intro:\n| a | b |\n|---|---|\n| 1 | 2 |\n\n| x |\n|-|\n\nAfter\n> q\n> > r\n\n> s",
        "$$\nx\n$$\n\n$$\nunclosed\n\nnext **b** _i_ ~~s~~ [l](https://a.example) <https://b.example>",
        "- [ ] a\n- [x] b\n  - c\n\n    ```\n    d\n    ```\n# h\n***\nend",
    ]
}
