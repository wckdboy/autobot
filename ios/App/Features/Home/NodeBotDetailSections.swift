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
    @Environment(\.scheme) private var c

    var body: some View {
        if let problem = detail.problem {
            MicroLabel(problem, color: c.error, lines: 3).padding(.top, 16)
        }
        if let p = detail.personalities { personality(p) }
        if let p = detail.profile { BotProfileSection(profile: p, bot: bot, node: node) }
        if detail.insights != nil || detail.usage != nil { usage(detail.insights, today: detail.usage?.today) }
    }

    private func personality(_ p: NodePersonalities) -> some View {
        let words = NodeBotDetails.shared
        return VStack(alignment: .leading, spacing: 6) {
            SectionLabel("personality")
            ChatFlow(spacing: 8) {
                ForEach([words.NONE] + p.list.map(\.name), id: \.self) { name in
                    let selected = words.personality(p: p) == name
                    PersonalityChip(name: name, selected: selected, enabled: setting == nil) {
                        if !selected { onPersonality(name == words.NONE ? nil : name) }
                    }
                }
            }
            Text(words.personalityLine(p: p) ?? (p.active == nil ? "No personality: it speaks as its SOUL.md says." : "For every turn from now on."))
                .textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
        }
        .padding(.top, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityIdentifier("bot-personality")
    }

    private func usage(_ i: NodeInsights?, today: UsageTotals?) -> some View {
        let words = NodeBotDetails.shared
        return VStack(alignment: .leading, spacing: 8) {
            SectionLabel(i.map { "usage · last \(words.plural(n: $0.days, word: "day"))" } ?? "usage")
            if let i { Text(words.summary(i: i)).textStyle(.bodyMedium).foregroundStyle(c.onSurface).fixedSize(horizontal: false, vertical: true) }
            if let today {
                let line = words.today(t: today)
                MicroLabel("today · " + (line.hasSuffix(".") ? String(line.dropLast()) : line), lines: 2)
            }
            if let i, i.turns > 0 {
                VStack(alignment: .leading, spacing: 4) {
                    MicroLabel("model calls · " + words.share(i: i), lines: 2)
                    ShareBar(local: i.localCalls)
                    if let cost = words.costLine(i: i) { MicroLabel("cost · " + cost, lines: 2) }
                }
                bars("tools", words.toolBars(i: i, max: 5))
                bars("models", words.modelBars(i: i, max: 4))
                if let failures = words.failures(f: i.failures) { MicroLabel("went wrong · " + failures, lines: 2) }
            }
        }
        .padding(.top, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityIdentifier("bot-usage")
    }

    @ViewBuilder private func bars(_ label: String, _ bars: [InsightBar]) -> some View {
        if !bars.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                MicroLabel(label)
                ForEach(bars, id: \.label) { bar in
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(alignment: .firstTextBaseline) {
                            Text(bar.label).textStyle(.code).foregroundStyle(c.onSurface).textLines(2).frame(maxWidth: .infinity, alignment: .leading)
                            Text(bar.value).textStyle(.micro).foregroundStyle(c.onSurfaceVariant)
                        }
                        FlatBar(fraction: bar.fraction, height: 4, fill: c.onSurfaceVariant, track: c.surfaceContainerHigh)
                    }
                    .accessibilityElement(children: .combine)
                }
            }
            .padding(.top, 4)
        }
    }
}

/// What the bot keeps about how the owner works: its entries, and the command to edit USER.md on the node.
struct BotProfileSection: View {
    let profile: NodeProfile
    let bot: String
    let node: String
    @State private var copied = false
    @Environment(\.scheme) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            SectionLabel("what it knows about you")
            if profile.entries.isEmpty {
                Text("Nothing yet. It keeps what it learns about how you work as it goes.")
                    .textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
            }
            ForEach(Array(profile.entries.enumerated()), id: \.offset) { _, entry in
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text("–").textStyle(.bodyMedium).foregroundStyle(c.outline)
                    Text(entry).textStyle(.bodyMedium).foregroundStyle(c.onSurface).fixedSize(horizontal: false, vertical: true)
                }
                .accessibilityElement(children: .combine)
            }
            // Read-only here: it is edited where it lives, in the node's terminal.
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 12) { label; copy }
                VStack(alignment: .leading, spacing: 4) { label; copy }
            }
            .padding(.top, 2)
            Hairline().padding(.top, 6)
        }
        .padding(.top, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityIdentifier("bot-profile")
        .task(id: copied) {
            guard copied else { return }
            try? await Task.sleep(for: .seconds(1.5))
            copied = false
        }
    }

    private var label: some View { MicroLabel("edit USER.md on \(node)", lines: 3) }

    private var copy: some View {
        TextAction(copied ? "Copied" : "Copy command", color: c.onSurfaceVariant) {
            UIPasteboard.general.setItems([[UTType.plainText.identifier: NodeBotDetails.shared.profileEditCommand(bot: bot)]], options: [.localOnly: true])
            copied = true
        }
        .accessibilityHint("Copies the command that opens USER.md in an editor on the node")
    }
}

/**
 A personality to choose, by its name as the node writes it (never re-cased): the v4 selection,
 onSurface text over the 2 pt purple bar when chosen, onSurfaceVariant otherwise (Android's ChoiceChip).
 */
private struct PersonalityChip: View {
    let name: String
    let selected: Bool
    let enabled: Bool
    let action: () -> Void
    @Environment(\.scheme) private var c

    var body: some View {
        Button(action: action) {
            Text(name)
                .textStyle(.micro, weight: selected ? 600 : nil)
                .foregroundStyle(!enabled ? c.outline : selected ? c.onSurface : c.onSurfaceVariant)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .overlay(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall).strokeBorder(selected ? c.outline : c.outlineVariant, lineWidth: Tokens.Stroke.border))
                .selectionBar(selected)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle())
        .disabled(!enabled)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// The local share of a whole, flat: local in onSurfaceVariant, cloud in the hairline color.
private struct ShareBar: View {
    let local: Double
    @Environment(\.scheme) private var c

    var body: some View {
        FlatBar(fraction: local, height: 8, fill: c.onSurfaceVariant, track: c.outlineVariant, radius: AutobotShapes.extraSmall)
            .accessibilityLabel("local \(NodeBotDetails.shared.percent(share: local))")
    }
}

/// A flat bar: [fraction] of the width filled.
struct FlatBar: View {
    let fraction: Double
    let height: CGFloat
    let fill: Color
    let track: Color
    var radius: CGFloat = 0

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: radius).fill(track)
                RoundedRectangle(cornerRadius: radius).fill(fill).frame(width: max(1, g.size.width * min(1, max(0, fraction))))
            }
        }
        .frame(height: height)
    }
}
