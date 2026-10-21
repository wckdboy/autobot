import SwiftUI
import Testing
import UIKit
@testable import Autobot

/**
 Renders a rich sample answer (headings, lists, tasks, a quote, a table, code, links, an image
 that is never loaded) in dark and light to PNGs for visual review. Skipped unless
 `AUTOBOT_SNAPSHOT_DIR` is set (`TEST_RUNNER_AUTOBOT_SNAPSHOT_DIR=… xcodebuild test`).
 */
@MainActor
struct MarkdownSnapshotTests {
    private var dir: String? { ProcessInfo.processInfo.environment["AUTOBOT_SNAPSHOT_DIR"] }

    @Test(.timeLimit(.minutes(2))) func renderRichAnswer() async throws {
        guard let dir else { return }
        for mode in [ThemeMode.dark, .light] {
            let scene = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
            let window = UIWindow(windowScene: scene)
            window.overrideUserInterfaceStyle = mode == .dark ? .dark : .light
            let root = Sample().autobotTheme(mode)
            window.rootViewController = UIHostingController(rootView: root)
            window.makeKeyAndVisible()
            try await Task.sleep(for: .milliseconds(1200))
            let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
                window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
            }
            try #require(image.pngData()).write(to: URL(fileURLWithPath: "\(dir)/markdown-ios-\(mode == .dark ? "dark" : "light").png"))
            window.isHidden = true
        }
    }

    private struct Sample: View {
        var body: some View {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    PromptLine(text: "When is the next train to **Berlin**?")
                    ChatBubble(text: MarkdownSnapshotTests.sample)
                }
                .padding(.horizontal, Tokens.Spacing.gutter)
                .padding(.top, 8)
            }
            .background(Color(.systemBackground).ignoresSafeArea())
        }
    }

    static let sample = """
    ## Next train to Berlin

    It arrives at **17:25** — the *ICE 527* from platform ~~4~~ **6**. Book on [bahn.de](https://int.bahn.de/en) or the [old site](http://bahn.example/old).

    1. Buy a ticket in the app.
    2. Validate it:
       - on the platform, or
       - on board (costs `€2` extra)
    3. Board the train.

    - [x] Seat reserved
    - [ ] Bike ticket

    > Evening trains are busy: book a seat.

    | Train | Departs | Price |
    |:------|:-------:|------:|
    | ICE 527 | 17:25 | €49.90 |
    | RE 7 | 18:02 | €12.00 |

    ```kotlin
    fun fare(km: Int) = km * 0.18
    ```

    ### Map
    ![Route map](https://maps.example/route.png) · energy $E = mc^2$, snake_case_kept.

    ---
    Sources: <https://example.com/timetable>
    """
}
