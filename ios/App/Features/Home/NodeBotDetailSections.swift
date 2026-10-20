import AutobotShared
import SwiftUI
import UniformTypeIdentifiers

/**
 Bot detail's node parts (as on Android): its personality, a choice of the node's; what it knows
 about the owner (its USER.md, read-only here); and what it did, today and the last week, as words
 and flat bars. A part an older node does not have is left out.
 */
struct BotDetailSections: View {
    let detail: NodeBotDetail
    let bot: String
    let node: String
    /// A personality being set, or nil.
    let setting: String?
    let onPersonality: (String?) -> Void

    var body: some View {
        if let problem = detail.problem {
            Section { Text(problem).foregroundStyle(.red).lineLimit(3) }
        }
        if let p = detail.personalities { personality(p) }
        if let p = detail.profile { BotProfileSection(profile: p, bot: bot, node: node) }
        if detail.insights != nil || detail.usage != nil { usage(detail.insights, today: detail.usage?.today) }
    }

    private func personality(_ p: NodePersonalities) -> some View {
        let words = NodeBotDetails.shared
        let chosen = words.personality(p: p)
        return Section {
            // The node's names as it writes them (never re-cased).
            Picker("Personality", selection: Binding(get: { chosen }, set: { name in
                if name != chosen { onPersonality(name == words.NONE ? nil : name) }
            })) {
                ForEach([words.NONE] + p.list.map(\.name), id: \.self) { Text($0).tag($0) }
            }
            .disabled(setting != nil)
        } header: {
            Text("Personality")
        } footer: {
            Text(words.personalityLine(p: p) ?? (p.active == nil ? "No personality: it speaks as its SOUL.md says." : "For every turn from now on."))
        }
        .accessibilityIdentifier("bot-personality")
    }

    private func usage(_ i: NodeInsights?, today: UsageTotals?) -> some View {
        let words = NodeBotDetails.shared
        return Section {
            if let i { Text(words.summary(i: i)).fixedSize(horizontal: false, vertical: true) }
            if let today {
                let line = words.today(t: today)
                LabeledContent("Today", value: line.hasSuffix(".") ? String(line.dropLast()) : line)
            }
            if let i, i.turns > 0 {
                VStack(alignment: .leading, spacing: 6) {
                    LabeledContent("Model calls", value: words.share(i: i))
                    ProgressView(value: min(1, max(0, i.localCalls)))
                        .accessibilityLabel("local \(words.percent(share: i.localCalls))")
                }
                if let cost = words.costLine(i: i) { LabeledContent("Cost", value: cost) }
                bars("Tools", words.toolBars(i: i, max: 5))
                bars("Models", words.modelBars(i: i, max: 4))
                if let failures = words.failures(f: i.failures) {
                    LabeledContent("Went wrong") { Text(failures).foregroundStyle(.red) }
                }
            }
        } header: {
            Text(i.map { "Usage · last \(words.plural(n: $0.days, word: "day"))" } ?? "Usage")
        }
        .accessibilityIdentifier("bot-usage")
    }

    @ViewBuilder private func bars(_ label: String, _ bars: [InsightBar]) -> some View {
        if !bars.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text(label).font(.subheadline).foregroundStyle(.secondary)
                ForEach(bars, id: \.label) { bar in
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(alignment: .firstTextBaseline) {
                            // Tool and model names: code.
                            Text(bar.label).font(.callout.monospaced()).lineLimit(2).frame(maxWidth: .infinity, alignment: .leading)
                            Text(bar.value).font(.caption).foregroundStyle(.secondary)
                        }
                        ProgressView(value: min(1, max(0, bar.fraction)))
                            .tint(.secondary)
                    }
                    .accessibilityElement(children: .combine)
                }
            }
        }
    }
}

/// What the bot keeps about how the owner works: its entries, and the command to edit USER.md on the node.
struct BotProfileSection: View {
    let profile: NodeProfile
    let bot: String
    let node: String
    @State private var copied = false

    var body: some View {
        Section {
            if profile.entries.isEmpty {
                Text("Nothing yet. It keeps what it learns about how you work as it goes.")
                    .foregroundStyle(.secondary)
            }
            ForEach(Array(profile.entries.enumerated()), id: \.offset) { _, entry in
                Text(entry).fixedSize(horizontal: false, vertical: true)
            }
            // Read-only here: it is edited where it lives, in the node's terminal.
            Button {
                UIPasteboard.general.setItems([[UTType.plainText.identifier: NodeBotDetails.shared.profileEditCommand(bot: bot)]], options: [.localOnly: true])
                copied = true
            } label: {
                Label(copied ? "Copied" : "Copy command", systemImage: copied ? "checkmark" : "doc.on.doc")
            }
            .accessibilityHint("Copies the command that opens USER.md in an editor on the node")
        } header: {
            Text("What it knows about you")
        } footer: {
            Text("edit USER.md on \(node)")
        }
        .accessibilityIdentifier("bot-profile")
        .task(id: copied) {
            guard copied else { return }
            try? await Task.sleep(for: .seconds(1.5))
            copied = false
        }
    }
}
