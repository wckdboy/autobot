import AutobotShared
import CoreGraphics
import Testing
@testable import Autobot

/// The keyboard pattern: what counts as "up", and what a screen keeps while typing (shared rules).
@MainActor
struct KeyboardTests {
    private let screen = CGRect(x: 0, y: 0, width: 402, height: 874)

    @Test func aSoftwareKeyboardIsUpAndTheHardwareShortcutBarIsNot() {
        #expect(KeyboardTracker.isUp(frame: CGRect(x: 0, y: 538, width: 402, height: 336), screen: screen))
        // A hardware keyboard leaves only the shortcut bar (about 55 pt) on screen.
        #expect(!KeyboardTracker.isUp(frame: CGRect(x: 0, y: 819, width: 402, height: 55), screen: screen))
        // Lowered: the frame ends below the screen.
        #expect(!KeyboardTracker.isUp(frame: CGRect(x: 0, y: 874, width: 402, height: 336), screen: screen))
    }

    @Test func whileTypingOnlyTheFieldAndWhatItAnswersStay() {
        let c = Composer.shared
        #expect(c.shows(part: .field, keyboardUp: true))
        #expect(c.shows(part: .attachments, keyboardUp: true))
        #expect(c.shows(part: .answering, keyboardUp: true))
        for part in [Composer.Part.banner, .chips, .plan, .decor] {
            #expect(!c.shows(part: part, keyboardUp: true))
            #expect(c.shows(part: part, keyboardUp: false))
        }
    }

    @Test func theButtonSendsQueuesOrStopsAsOnAndroid() {
        let c = Composer.shared
        #expect(c.action(text: "", attachments: 0, running: false) == .send)
        #expect(c.action(text: "", attachments: 0, running: true) == .stop)
        #expect(c.action(text: "next", attachments: 0, running: true) == .queue)
        #expect(!c.enabled(action: .send, text: " ", attachments: 0, ready: true))
        #expect(c.label(action: .queue) == "Queue")
        #expect(c.placeholder(running: false, node: false) == "Message")
        #expect(c.MAX_LINES == 5)
    }

    @Test func aChatShowsOneBannerTheMostPressingFirst() {
        let cloud = Provider(id: "c", kind: .openAICompatible, displayName: "OpenRouter", baseUrl: "https://openrouter.ai/api/v1", defaultModel: "m")
        let banners = ChatBanners.list(loaded: true, noProviders: false, showOffline: true, provider: cloud, needsKey: true, local: .fine)
        #expect(banners.first?.id == "offline")
        #expect(banners.first?.text == ChatBannerWords.shared.OFFLINE)
    }
}
