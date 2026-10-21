import SwiftUI
import Testing
import UIKit
@testable import Autobot

/// Regressions from the stability pass after the native UI landed (ios-solid).
@MainActor
struct SolidPassTests {
    // MARK: Composer drafts

    @Test func draftsAreKeptPerChatAndClearedWhenSent() {
        let drafts = ComposerDrafts()
        drafts.set("a", "half a thought")
        drafts.set("b", "another")
        #expect(drafts.text("a") == "half a thought")
        #expect(drafts.text("b") == "another")
        #expect(drafts.text("c") == "")
        // Sending empties the field: the draft goes.
        drafts.set("a", "")
        #expect(drafts.text("a") == "")
        // Only whitespace is nothing to keep.
        drafts.set("b", "  \n")
        #expect(drafts.text("b") == "")
        drafts.set("a", "x")
        drafts.removeAll()
        #expect(drafts.text("a") == "")
    }

    @Test func theContainerOwnsTheDrafts() {
        // A panic wipe replaces the container, and its drafts with it.
        let one = AppContainer()
        one.drafts.set("chat", "secret words")
        let two = AppContainer()
        #expect(two.drafts.text("chat") == "")
        #expect(one.drafts.text("chat") == "secret words")
    }

    // MARK: Markdown: the capsule renderer only where there is code

    @Test func codeRunsAreFoundAtAnyDepth() {
        #expect(!MdText.hasCodeRun(LiteMarkdown.parseInline("plain **bold** and *it* with a [link](https://a.example)")))
        #expect(MdText.hasCodeRun(LiteMarkdown.parseInline("call `reverse()` here")))
        #expect(MdText.hasCodeRun(LiteMarkdown.parseInline("**bold `code` inside**")))
        #expect(MdText.hasCodeRun(LiteMarkdown.parseInline("[the `x` link](https://a.example)")))
        #expect(MdText.hasCodeRun(LiteMarkdown.parseInline("an image ![alt](https://a.example/i.png)")))
        #expect(MdText.hasCodeRun(LiteMarkdown.parseInline("math $x^2$ too")))
    }

    // MARK: The chat's chips at the largest text size

    @Test func policyChipsHoldAPhoneWidthAtAX5() {
        var settings = SessionSettings()
        settings.toolsEnabled = true
        settings.thinkingSupport = "SWITCH"
        settings.redaction = "ON"
        settings.cascade = "ASK"
        let width: CGFloat = 375
        for size in [DynamicTypeSize.large, .accessibility3, .accessibility5] {
            let host = UIHostingController(rootView: PolicyLine(settings: settings, contextUsed: 12_000, contextWindow: 128_000).autobotTheme(.dark).dynamicTypeSize(size))
            let fit = host.sizeThatFits(in: CGSize(width: width, height: .greatestFiniteMagnitude))
            #expect(fit.width <= width + 0.5, "the policy line is \(fit.width) pt wide at \(size), over \(width): it widens the whole chat")
        }
    }

    // MARK: The transcript stack

    @Test func onlyShortChatsAtAccessibilitySizesAreLaidOutWhole() {
        typealias Stack = TranscriptStack<EmptyView>
        #expect(!Stack.eager(rows: 8, dynamicType: .large), "the default sizes stay lazy (a heavy chat whole costs ~15 ms and 1.5 MB a row)")
        #expect(!Stack.eager(rows: 40, dynamicType: .xxxLarge))
        #expect(Stack.eager(rows: 8, dynamicType: .accessibility5), "a short chat at AX5 is laid out whole (the lazy stack drew nothing)")
        #expect(!Stack.eager(rows: 40, dynamicType: .accessibility5))
    }

    // MARK: Labels

    @Test func modelSourcesAreSpelledAsNames() {
        #expect(ModelsTab.allCases.map(\.label) == ["For this phone", "Hugging Face", "Civitai"])
    }
}

@MainActor
struct SolidPassBannerTests {
    /// The chat's banner at AX5: the button moves under the words, which keep the full width.
    @Test func bannerStacksAtAccessibilitySizes() {
        let text = "Offline mode: this provider is remote, so the kill switch blocks it."
        let width: CGFloat = 375
        func fit(_ size: DynamicTypeSize) -> CGSize {
            UIHostingController(rootView: Banner(text: text, action: "Privacy") {}.autobotTheme(.dark).dynamicTypeSize(size))
                .sizeThatFits(in: CGSize(width: width, height: .greatestFiniteMagnitude))
        }
        let ax5 = fit(.accessibility5)
        #expect(ax5.width <= width + 0.5)
        // Beside the button the words had what the button left (about half the width): taller than
        // the whole stacked banner, words and button together.
        let beside = UIHostingController(rootView: Label(text, systemImage: "exclamationmark.circle").font(.footnote).dynamicTypeSize(.accessibility5))
            .sizeThatFits(in: CGSize(width: width / 2, height: .greatestFiniteMagnitude))
        #expect(ax5.height < beside.height, "the banner is \(ax5.height) pt tall, its words in half the width \(beside.height)")
    }
}
