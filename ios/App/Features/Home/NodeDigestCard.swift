import AutobotShared
import Observation
import SwiftUI

/// The "While you were away" card's content: [digest] of [node] (its "My node" provider [providerId]).
struct NodeDigestCardState: Equatable {
    let providerId: String
    let node: String
    let digest: NodeDigest

    static func == (a: Self, b: Self) -> Bool { a.providerId == b.providerId && a.node == b.node && a.digest == b.digest }
}

/**
 Loads the paired node's digest when the Run screen shows (port of `NodeDigestViewModel.kt`), and
 keeps it only when there is something new. Dismissing it, or opening what it points at, marks
 everything up to now as seen, per node, in `UserDefaults`.
 */
@MainActor
@Observable
final class NodeDigestModel {
    /// What the node did after a time (Unix seconds); nil when it cannot be asked.
    typealias Fetch = @MainActor (_ providerId: String, _ since: Int64) async -> NodeDigest?

    private(set) var card: NodeDigestCardState?
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private let now: () -> Int64
    @ObservationIgnored private var loading: Task<Void, Never>?

    init(defaults: UserDefaults = .standard, now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970) }) {
        self.defaults = defaults
        self.now = now
    }

    func lastSeen(_ providerId: String) -> Int64? {
        let key = NodeDigests.shared.seenKey(providerId: providerId)
        return defaults.object(forKey: key) == nil ? nil : Int64(defaults.integer(forKey: key))
    }

    /// Asks [node]'s node again; no card when there is no node, nothing new, or no answer.
    func refresh(node: (id: String, name: String)?, fetch: @escaping Fetch) async {
        loading?.cancel()
        guard let node else { return card = nil }
        let since = NodeDigests.shared.since(lastSeen: lastSeen(node.id).map { KotlinLong(value: $0) }, now: now())
        let task = Task { @MainActor in
            let digest = await fetch(node.id, since)
            guard !Task.isCancelled else { return }
            card = digest.flatMap { $0.hasNews ? NodeDigestCardState(providerId: node.id, node: node.name, digest: $0) : nil }
        }
        loading = task
        await task.value
    }

    /// Dismissed or opened: everything up to now is seen, and the card goes.
    func markSeen() {
        guard let card else { return }
        loading?.cancel()
        defaults.set(Int(now()), forKey: NodeDigests.shared.seenKey(providerId: card.providerId))
        self.card = nil
    }

    /// The paired node to ask: the first "My node" provider.
    static func pairedNode(_ app: AppContainer) -> (id: String, name: String)? {
        app.providers.providers.first { $0.kind == .invincibleNode }.map { ($0.id, $0.displayName) }
    }

    /// The real source: the node through the shared runtime, with the recipes for their result chats.
    /// Debug builds' demo (`-AutobotDemo`) shows sample data (`-AutobotDemoDigest failed` adds a receipt that failed).
    static func fetch(_ app: AppContainer) -> Fetch {
        { providerId, since in
            #if DEBUG
            if DebugFlags.demo {
                let failed = ProcessInfo.processInfo.arguments.contains("-AutobotDemoDigest") &&
                    ProcessInfo.processInfo.arguments.contains("failed")
                let now = Int64(Date().timeIntervalSince1970)
                // The demo node has three bots (BotsDemo): the card says which bot did what.
                return failed ? NodeDigests.shared.sample(now: now, failedReceipt: true) : NodeDigests.shared.sampleWithBots(now: now)
            }
            #endif
            guard let runtime = app.agent as? SharedAgentRuntime else { return nil }
            return await runtime.nodeDigest(providerId, since: since, recipes: app.recipes.recipes.map(\.json)).digest
        }
    }
}

/**
 "While you were away": what the paired node did since the owner last looked, in a few plain lines,
 with what needs them first as the primary action. Invincible's i marks it as the node's.
 */
struct NodeDigestCard: View {
    let state: NodeDigestCardState
    let onNeedsYou: () -> Void
    let onDetails: () -> Void
    let onDismiss: () -> Void
    @Environment(\.dynamicTypeSize) private var dynamicType
    private let words = NodeDigestWords.shared

    var body: some View {
        let needsYou = words.needsYou(d: state.digest)
        GroupBox {
            VStack(alignment: .leading, spacing: 10) {
                // The lines take the card's whole width (large text wraps them less).
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(Array(words.lines(d: state.digest).enumerated()), id: \.offset) { _, line in lineView(line) }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityHidden(true)
                actions(needsYou)
            }
            .padding(.top, 4)
        } label: {
            HStack(alignment: .top, spacing: 8) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    NodeMark()
                    VStack(alignment: .leading, spacing: 2) {
                        Text(NodeDigestWords.shared.TITLE).font(.headline)
                            .fixedSize(horizontal: false, vertical: true)
                        Text(state.node).font(.caption).foregroundStyle(.secondary).lineLimit(dynamicType.isAccessibilitySize ? 2 : 1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                // The title, the node and the lines are read as one: the card's story, without the glyphs.
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(words.spoken(d: state.digest, node: state.node))
                .accessibilityAddTraits(.isHeader)
                Button(action: onDismiss) {
                    Image(systemName: "xmark").font(.footnote.weight(.semibold)).foregroundStyle(.secondary)
                        .frame(width: 32, height: 32)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("Dismiss: mark as seen")
            }
        }
    }

    @ViewBuilder private func actions(_ needsYou: String?) -> some View {
        let details = Button("Details", action: onDetails)
            .secondaryActionStyle()
            .accessibilityLabel("Details: the node's activity")
        let primary = needsYou.map { text in
            Button(action: onNeedsYou) { Text(text).frame(maxWidth: .infinity) }
                .primaryActionStyle()
        }
        if dynamicType.isAccessibilitySize || (needsYou?.count ?? 0) > 20 {
            // Large text, or a long action ("2 approvals, 1 question waiting"): it gets the whole width, Details goes under it.
            VStack(alignment: .leading, spacing: 8) {
                primary
                details
            }
        } else {
            HStack(spacing: 8) {
                primary
                details
            }
        }
    }

    /// Words first; an error is in red and stands out, a warning's `!` is orange, a check stays quiet.
    @ViewBuilder private func lineView(_ line: NodeDigestLine) -> some View {
        switch line.tone {
        case .error:
            Text(line.text).font(.callout.weight(.medium)).foregroundStyle(.red).fixedSize(horizontal: false, vertical: true)
        case .ok:
            Text(line.text).font(.footnote).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
        case .warning:
            (Text(words.unmarked(text: line.text) + " ") + Text("!").foregroundStyle(.orange).bold())
                .font(.callout).fixedSize(horizontal: false, vertical: true)
        default:
            Text(line.text).font(.callout).fixedSize(horizontal: false, vertical: true)
        }
    }
}
