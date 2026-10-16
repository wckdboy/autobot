import Foundation
import Testing
@testable import Autobot

private final class CasesBundle {}

/**
 `shared/design/markdown-cases.json` is the chat markdown spec both apps share (Android:
 `MarkdownConformanceTest.kt`): each case's markdown, its block/span tree and what read-aloud
 speaks. The Swift parser must produce exactly the same.
 */
struct MarkdownConformanceTests {
    struct Case {
        let name: String
        let md: String
        let blocks: Any
        let speak: String
    }

    static func cases() throws -> [Case] {
        let url = try #require(Bundle(for: CasesBundle.self).url(forResource: "markdown-cases", withExtension: "json"))
        let json = try #require(try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [[String: Any]])
        return try json.map { c in
            Case(
                name: try #require(c["name"] as? String),
                md: try #require(c["md"] as? String),
                blocks: try #require(c["blocks"]),
                speak: try #require(c["speak"] as? String)
            )
        }
    }

    @Test func everyCaseParsesToItsTreeAndSpeech() throws {
        let cases = try Self.cases()
        #expect(cases.count >= 80)
        for c in cases {
            let got = try MdJson.canonical(MdJson.blocks(LiteMarkdown.parse(c.md)))
            let want = try MdJson.canonical(c.blocks)
            #expect(got == want, "\(c.name)")
            #expect(LiteMarkdown.speakable(c.md) == c.speak, "\(c.name)")
        }
    }

    /// Streaming (re-parsing only the tail) never changes the result: every prefix of every case.
    @Test func everyPrefixOfEveryCaseStreamsLikeAFullParse() throws {
        for c in try Self.cases() {
            var previous: LiteMarkdown.Parsed?
            var prefix = ""
            for ch in c.md {
                prefix.append(ch)
                let next = LiteMarkdown.parse(prefix, previous: previous)
                #expect(next.blocks == LiteMarkdown.parse(prefix), "\(c.name) at \(prefix.count)")
                previous = next
            }
        }
    }
}

/// The fixture's tree format: text spans are strings, everything else a one-key object.
enum MdJson {
    static func canonical(_ value: Any) throws -> String {
        String(decoding: try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys, .fragmentsAllowed, .withoutEscapingSlashes]), as: UTF8.self)
    }

    static func blocks(_ blocks: [MdBlock]) -> [Any] { blocks.map(block) }

    static func block(_ b: MdBlock) -> Any {
        switch b {
        case .paragraph(let s): ["p": spans(s)]
        case .heading(let level, let s): ["h": level, "spans": spans(s)]
        case .codeBlock(let language, let code, let closed): ["code": code, "lang": language.map { $0 as Any } ?? NSNull(), "closed": closed]
        case .quote(let inner): ["quote": blocks(inner)]
        case .list(let ordered, let start, let items):
            [
                "list": items.map { item -> [String: Any] in
                    var o: [String: Any] = ["blocks": blocks(item.blocks)]
                    if let task = item.task { o["task"] = task }
                    return o
                },
                "ordered": ordered,
                "start": start,
            ]
        case .rule: ["hr": true]
        case .table(let t):
            ["table": ["align": t.align.map(\.rawValue), "header": t.header.map(spans), "rows": t.rows.map { $0.map(spans) }]]
        }
    }

    static func spans(_ spans: [MdSpan]) -> [Any] { spans.map(span) }

    static func span(_ s: MdSpan) -> Any {
        switch s {
        case .text(let t): t
        case .inlineCode(let t): ["code": t]
        case .math(let t): ["math": t]
        case .strong(let c): ["strong": spans(c)]
        case .emphasis(let c): ["em": spans(c)]
        case .strike(let c): ["del": spans(c)]
        case .link(let url, let c): ["link": url, "spans": spans(c)]
        case .image(let alt, let url): ["image": url, "alt": alt]
        }
    }
}
