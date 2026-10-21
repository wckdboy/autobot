import SwiftUI
import Testing
import UIKit
@testable import Autobot

/**
 What the inline-code capsule renderer costs on paragraphs that have no code: 120 wrapped
 paragraphs laid out and drawn with and without it (`inlineCodeCapsules`). Prints `PERF renderer`
 lines; skipped unless `AUTOBOT_PERF` is set.
 */
@MainActor
struct MarkdownRenderPerfTests {
    @Test(.timeLimit(.minutes(2))) func capsuleRendererOnlyWhereNeeded() async throws {
        guard ProcessInfo.processInfo.environment["AUTOBOT_PERF"] != nil else { return }
        let blocks = LiteMarkdown.parse(String(repeating: "A plain paragraph of an answer with **bold** words and a [link](https://example.com), long enough to wrap over a few lines on a phone.\n\n", count: 120))
        let scene = try await TestScene.first()
        for round in 0 ..< 3 {
            for capsules in [true, false] {
                let view = ScrollView { VStack(alignment: .leading, spacing: 8) {
                    ForEach(Array(blocks.enumerated()), id: \.offset) { _, b in
                        if case .paragraph(let spans) = b {
                            MdText.text(spans, style: .body).font(.body).inlineCodeCapsules(capsules).fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }.padding() }
                let window = UIWindow(windowScene: scene)
                let clock = ContinuousClock()
                let t = clock.measure {
                    window.rootViewController = UIHostingController(rootView: view)
                    window.makeKeyAndVisible()
                    window.layoutIfNeeded()
                    _ = UIGraphicsImageRenderer(bounds: CGRect(x: 0, y: 0, width: 440, height: 4000)).image { _ in window.drawHierarchy(in: window.bounds, afterScreenUpdates: true) }
                }
                print("PERF renderer round=\(round) capsules=\(capsules) \(t)")
                window.isHidden = true
            }
        }
    }
}
