import Foundation
import Observation

/**
 What a system entry point asked for: App Intents (Siri, Shortcuts, the Action button), the share
 extension, widgets and the Control Center control. Requests wait until the app is unlocked and
 set up. Only an App Intent the user runs ("Ask Autobot") sends a prompt; everything that arrives
 as a URL, which any app or web page can open, only fills the composer or the studio.
 */
enum EntryRequest: Equatable {
    /// A new chat. [send]: send [prompt] right away (App Intents only), else it is a draft.
    case ask(prompt: String?, send: Bool)
    /// A new chat in voice mode (listening starts on the voice button, never by itself).
    case voice
    /// The image studio with [prompt] filled in (not generated).
    case imagine(prompt: String?)
    /// Content from the share extension, waiting in [SharedInbox] under [id].
    case shared(id: String)
}

/// `autobot://` links (widgets, the control, the share extension).
enum EntryLink {
    static let scheme = "autobot"
    static let maxText = 20_000

    static let ask = URL(string: "autobot://ask")!
    static let voice = URL(string: "autobot://voice")!
    static let imagine = URL(string: "autobot://imagine")!

    static func shared(_ id: String) -> URL { URL(string: "autobot://share?id=\(id)")! }

    /// Never a sending request: a link only opens a screen or fills a draft.
    static func parse(_ url: URL) -> EntryRequest? {
        guard url.scheme?.lowercased() == scheme, let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        func value(_ name: String) -> String? {
            let raw = components.queryItems?.first { $0.name == name }?.value?.trimmingCharacters(in: .whitespacesAndNewlines)
            return raw.flatMap { $0.isEmpty ? nil : String($0.prefix(maxText)) }
        }
        switch components.host?.lowercased() {
        case "ask": return .ask(prompt: value("text"), send: false)
        case "voice": return .voice
        case "imagine": return .imagine(prompt: value("prompt"))
        case "share": return value("id").flatMap(UUID.init(uuidString:)).map { .shared(id: $0.uuidString) }
        default: return nil
        }
    }
}

/// What a new chat started from an entry point gets: a draft or a prompt to send, images, voice mode.
struct ChatStart: Equatable {
    var draft: String?
    var send: String?
    var images: [Data] = []
    var voice = false
}

/// Where a request lands.
enum EntryPlan: Equatable {
    case chat(ChatStart)
    case studio(prompt: String?)
    /// No chat model or provider yet: the Models tab.
    case setup
    /// Nothing to do (an expired or empty share).
    case none
}

/// Entry routing, kept pure for tests.
enum EntryRouting {
    /// [shared] reads a share extension item (once).
    static func plan(_ request: EntryRequest, canChat: Bool, shared: (String) -> SharedInbox.Item?) -> EntryPlan {
        switch request {
        case .imagine(let prompt):
            return .studio(prompt: prompt)
        case .voice:
            return canChat ? .chat(ChatStart(voice: true)) : .setup
        case .ask(let prompt, let send):
            guard canChat else { return .setup }
            return .chat(send ? ChatStart(send: prompt) : ChatStart(draft: prompt))
        case .shared(let id):
            guard let item = shared(id) else { return .none }
            return canChat ? .chat(ChatStart(draft: item.text, images: item.images)) : .setup
        }
    }
}

/**
 Holds the pending request and the hand-offs for chats it started (memory only: a request lost
 with the process is dropped, never replayed). New chats follow the "incognito by default" setting
 and start with tools off, like the RUN card's Chat.
 */
@MainActor
@Observable
final class EntryRouter {
    static let shared = EntryRouter()

    private(set) var pending: EntryRequest?
    @ObservationIgnored private var handoffs: [String: ChatStart] = [:]

    func receive(_ request: EntryRequest) { pending = request }

    func receive(url: URL) {
        if let request = EntryLink.parse(url) { pending = request }
    }

    /// The hand-off for [conversationId], once.
    func take(_ conversationId: String) -> ChatStart? { handoffs.removeValue(forKey: conversationId) }

    func put(_ start: ChatStart, for conversationId: String) { handoffs[conversationId] = start }

    /// Turns the pending request into navigation; call only while unlocked, set up and loaded.
    func open(app: AppContainer, nav: Navigator) {
        guard let request = pending, app.lock.state == .unlocked, app.dataLoaded else { return }
        pending = nil
        let target = RunState(app: app, device: nil).chat
        let inbox = SharedInbox.defaultRoot
        let plan = EntryRouting.plan(request, canChat: target != nil) { id in inbox.flatMap { SharedInbox.take(id, root: $0) } }
        switch plan {
        case .chat(let start):
            guard let target else { return }
            let conversation = app.conversations.create(incognito: app.settings.settings.incognitoByDefault, providerId: target.providerId, model: target.model)
            app.agent.setToolsEnabled(conversation.id, false)
            put(start, for: conversation.id)
            nav.reset(.run, [.chat(conversationId: conversation.id)])
        case .studio(let prompt):
            nav.reset(.run, [.imagine(prompt: prompt)])
        case .setup:
            nav.switchTo(.models)
        case .none:
            break
        }
    }
}
