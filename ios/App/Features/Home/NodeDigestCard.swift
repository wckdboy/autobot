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
                return NodeDigests.shared.sample(now: Int64(Date().timeIntervalSince1970), failedReceipt: failed)
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
    @Environment(\.scheme) private var c
    @Environment(\.dynamicTypeSize) private var dynamicType
    private let words = NodeDigestWords.shared

    var body: some View {
        let needsYou = words.needsYou(d: state.digest)
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 8) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    NodeMark()
                    VStack(alignment: .leading, spacing: 2) {
                        Text(NodeDigestWords.shared.TITLE).textStyle(.titleSmall).foregroundStyle(c.onSurface)
                            .fixedSize(horizontal: false, vertical: true)
                        Text(state.node).textStyle(.micro).foregroundStyle(c.onSurfaceVariant).textLines(1, accessibility: 2)
                    }
                }
                .padding(.top, 6)
                .frame(maxWidth: .infinity, alignment: .leading)
                // The title, the node and the lines are read as one: the card's story, without the glyphs.
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(words.spoken(d: state.digest, node: state.node))
                .accessibilityAddTraits(.isHeader)
                IconAction(symbol: "xmark", label: "Dismiss: mark as seen", action: onDismiss)
            }
            // The lines take the card's whole width (large text wraps them less).
            VStack(alignment: .leading, spacing: 6) {
                ForEach(Array(words.lines(d: state.digest).enumerated()), id: \.offset) { _, line in lineView(line) }
            }
            .padding(.trailing, 8)
            .accessibilityHidden(true)
            actions(needsYou)
        }
        .padding(.leading, 12)
        .padding(.trailing, 4)
        .padding(.vertical, 10)
        .overlay(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall).strokeBorder(c.outlineVariant, lineWidth: Tokens.Stroke.border))
    }

    @ViewBuilder private func actions(_ needsYou: String?) -> some View {
        let details = TextAction("Details", color: c.onSurfaceVariant, action: onDetails).accessibilityLabel("Details: the node's activity")
        if dynamicType.isAccessibilitySize || (needsYou?.count ?? 0) > 20 {
            // Large text, or a long action ("2 approvals, 1 question waiting"): it gets the whole width, Details goes under it.
            VStack(alignment: .leading, spacing: 4) {
                if let needsYou { PaperButton(needsYou, action: onNeedsYou) }
                details
            }
            .padding(.trailing, 8)
        } else {
            HStack(spacing: 8) {
                if let needsYou { PaperButton(needsYou, action: onNeedsYou) }
                details
            }
            .padding(.trailing, 8)
        }
    }

    /// Words first; an error is in the error color and stands out, a warning's `!` is amber, a check stays quiet.
    @ViewBuilder private func lineView(_ line: NodeDigestLine) -> some View {
        switch line.tone {
        case .error:
            Text(line.text).textStyle(.bodyMedium).fontWeight(.medium).foregroundStyle(c.error).fixedSize(horizontal: false, vertical: true)
        case .ok:
            Text(line.text).textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
        case .warning:
            (Text(words.unmarked(text: line.text) + " ").foregroundStyle(c.onSurface) + Text("!").foregroundStyle(c.inks.amber).bold())
                .textStyle(.bodyMedium).fixedSize(horizontal: false, vertical: true)
        default:
            Text(line.text).textStyle(.bodyMedium).foregroundStyle(c.onSurface).fixedSize(horizontal: false, vertical: true)
        }
    }
}
