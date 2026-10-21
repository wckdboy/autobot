import AutobotShared
import Foundation
import Observation

/**
 What a tap on an A2UI component asks the app to do, after the shared core ([A2uiHost.dispatch])
 resolved it. The Swift mirror of Kotlin's `A2uiActionResult`.
 */
enum A2uiAction: Equatable, Sendable {
    /// Send [messageJson] (a renderer-to-agent `action` message) to the agent that owns the
    /// surface; attach [dataModelJson] as `a2uiRendererDataModel` metadata when it is set.
    /// [userMessage] is the text to show in the chat for the user's action, when the agent gave one.
    /// [snapshotJson] is the surface's whole data model at the tap (obscured fields left out): what
    /// the chat keeps with the turn so the sent form shows its input after a restart.
    case send(surfaceId: String, name: String, messageJson: String, dataModelJson: String?, userMessage: String?, snapshotJson: String)
    /// Open this http(s) link — only after the user confirmed it ([A2uiSurfaceView] asks).
    case openUrl(URL)
    /// A local function ran; nothing else to do.
    case done
    /// Nothing happened, and why (a failing check, no action, a non-http link).
    case refused(String)

    init(_ result: A2uiActionResult) {
        switch result {
        case let r as A2uiActionResult.SendEvent:
            self = .send(surfaceId: r.surfaceId, name: r.name, messageJson: r.messageJson, dataModelJson: r.dataModelJson, userMessage: r.userMessage, snapshotJson: r.snapshotJson)
        case let r as A2uiActionResult.OpenUrl:
            // The core only returns http(s); check again before the system sees it.
            if let url = URL(string: r.url), let scheme = url.scheme?.lowercased(), scheme == "https" || scheme == "http", url.host() != nil {
                self = .openUrl(url)
            } else {
                self = .refused("not an http(s) link")
            }
        case let r as A2uiActionResult.Refused:
            self = .refused(r.reason)
        default:
            self = .done
        }
    }
}

/**
 One A2UI surface for SwiftUI: wraps the shared [A2uiHost] (validation, data model, expressions,
 actions all live in Kotlin) and publishes the surface's rendered [root] whenever the host
 re-renders it — after agent messages and after every input write-back.

 Several models may share one host (a chat's surfaces); each listens for its own [surfaceId].
 Call everything on the main actor: the host is not thread-safe.

 ```swift
 let model = A2uiSurfaceModel(surfaceId: "contact_form_1")
 model.receive(agentJsonl)
 A2uiSurfaceView(model: model) { action in send(action) }
 ```
 */
@MainActor
@Observable
final class A2uiSurfaceModel {
    let host: A2uiHost
    let surfaceId: String
    /// The surface's tree; nil until a `root` component arrived (or after deleteSurface).
    private(set) var root: RenderNode?
    /// True once the agent deleted the surface.
    private(set) var isDeleted = false
    /// The last errors the host reported for this surface (or for messages without one).
    private(set) var errors: [A2uiError] = []
    /// Bumped on every update, so views can observe re-renders cheaply.
    private(set) var revision = 0

    @ObservationIgnored private var listener: Listener?

    init(host: A2uiHost = A2uiHost(), surfaceId: String) {
        self.host = host
        self.surfaceId = surfaceId
        root = host.render(surfaceId: surfaceId)
        let listener = Listener(owner: self)
        self.listener = listener
        host.addListener(listener: listener)
    }

    isolated deinit {
        if let listener { host.removeListener(listener: listener) }
    }

    /// Applies agent output (JSONL, a JSON array or one message) to the host; returns its errors.
    @discardableResult
    func receive(_ text: String) -> [A2uiError] {
        host.receive(text: text)
    }

    // MARK: Input write-back (through the node's binding for `value`)

    @discardableResult func set(_ node: RenderNode, string: String, prop: String = "value") -> Bool {
        host.setString(node: node, prop: prop, value: string)
    }

    @discardableResult func set(_ node: RenderNode, number: Double, prop: String = "value") -> Bool {
        host.setNumber(node: node, prop: prop, value: number)
    }

    @discardableResult func set(_ node: RenderNode, bool: Bool, prop: String = "value") -> Bool {
        host.setBoolean(node: node, prop: prop, value: bool)
    }

    @discardableResult func set(_ node: RenderNode, strings: [String], prop: String = "value") -> Bool {
        host.setStrings(node: node, prop: prop, value: strings)
    }

    /// Fires [node]'s action (a Button tap). The core resolves the context and checks.
    func dispatch(_ node: RenderNode) -> A2uiAction {
        A2uiAction(host.dispatch(node: node))
    }

    /// Read-only (a sent form): the host refuses input and agent events. Changes re-render the
    /// surface, so reading it next to [root] stays current.
    var isReadOnly: Bool {
        _ = revision
        return host.isReadOnly(surfaceId: surfaceId)
    }

    /// The surface's data model as JSON text (debugging, tests).
    var dataModelJson: String? { host.surface(surfaceId: surfaceId)?.dataModelJson() }

    /// Finds the first node with component [id] in the current tree (tests, previews).
    func node(id: String) -> RenderNode? {
        guard let root else { return nil }
        return Self.find(root) { $0.id == id }
    }

    static func find(_ node: RenderNode, where match: (RenderNode) -> Bool) -> RenderNode? {
        if match(node) { return node }
        for child in node.children {
            if let hit = find(child, where: match) { return hit }
        }
        return nil
    }

    fileprivate func updated(_ root: RenderNode?) {
        self.root = root
        isDeleted = false
        revision += 1
    }

    fileprivate func deleted() {
        root = nil
        isDeleted = true
        revision += 1
    }

    fileprivate func failed(_ errors: [A2uiError]) {
        let mine = errors.filter { $0.surfaceId == nil || $0.surfaceId == surfaceId }
        if !mine.isEmpty { self.errors = mine }
    }

    /// The Kotlin listener; the host calls it synchronously on the thread that called in (main).
    private final class Listener: NSObject, A2uiListener, @unchecked Sendable {
        weak var owner: A2uiSurfaceModel?
        private let surfaceId: String

        init(owner: A2uiSurfaceModel) {
            self.owner = owner
            surfaceId = owner.surfaceId
        }

        func onSurfaceUpdated(surfaceId: String, root: RenderNode?) {
            guard surfaceId == self.surfaceId else { return }
            nonisolated(unsafe) let root = root
            MainActor.assumeIsolated { owner?.updated(root) }
        }

        func onSurfaceDeleted(surfaceId: String) {
            guard surfaceId == self.surfaceId else { return }
            MainActor.assumeIsolated { owner?.deleted() }
        }

        func onErrors(errors: [A2uiError]) {
            nonisolated(unsafe) let errors = errors
            MainActor.assumeIsolated { owner?.failed(errors) }
        }
    }
}
