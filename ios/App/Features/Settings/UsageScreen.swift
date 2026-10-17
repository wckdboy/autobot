import AutobotShared
import SwiftUI

/**
 The spend and quota board (port of `UsageScreen.kt`): tokens and cost by period, a bar per day
 (week, month), the breakdown by provider and model, subscription windows with what is left, and
 the price table. Computed on this phone from the providers' usage reports; nothing is sent.
 */
struct UsageScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c
    @State private var periodKey = "day"
    @State private var seriesKey = "day"
    @State private var byModel = false
    @State private var editingPrice: UsageReportModel.PriceRow?
    @State private var editingWindow: WindowDraft?
    @State private var confirmClear = false

    var body: some View {
        VStack(spacing: 0) {
            AutobotTopBar(title: "Usage", onBack: { nav.back() })
            ScrollView {
                if let report = app.usage.report {
                    UsageBoardView(
                        report: report,
                        providers: app.providers.providers.filter { $0.kind != .local },
                        periodKey: $periodKey,
                        seriesKey: $seriesKey,
                        byModel: $byModel,
                        onEditPrice: { editingPrice = $0 },
                        onEditWindow: { editingWindow = $0 },
                        onAddOpenCodeGo: { app.usage.addOpenCodeGoWindows(provider: $0) },
                        onClear: { confirmClear = true },
                    )
                    .padding(16)
                } else {
                    MicroLabel("counting…").padding(16).frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        .background(c.background)
        .onAppear { app.usage.start() }
        .onDisappear { app.usage.stop() }
        .sheet(item: $editingPrice) { row in
            PriceEditorSheet(row: row) { i, o, cr, cw in
                app.usage.savePrice(provider: row.provider, model: row.model, input: i, output: o, cacheRead: cr, cacheWrite: cw)
                editingPrice = nil
            } onCancel: { editingPrice = nil }
        }
        .sheet(item: $editingWindow) { draft in
            WindowEditorSheet(initial: draft, providers: app.providers.providers.filter { $0.kind != .local }) { window in
                app.usage.saveWindow(window)
                editingWindow = nil
            } onDelete: {
                app.usage.deleteWindow(draft.window.id)
                editingWindow = nil
            } onCancel: { editingWindow = nil }
        }
        .risoAlert($confirmClear, title: "Clear usage history?", message: "The counted tokens are removed from this phone. Prices and windows stay.", confirm: "Clear") {
            app.usage.clearHistory()
        }
    }
}

/// A window being edited; [isNew] for one not saved yet.
struct WindowDraft: Identifiable, Equatable {
    var window: UsageReportModel.Plan.Window
    var isNew: Bool
    var id: String { window.id }
}

/// The board itself, from a report (testable without a runtime).
struct UsageBoardView: View {
    let report: UsageReportModel
    let providers: [Provider]
    @Binding var periodKey: String
    @Binding var seriesKey: String
    @Binding var byModel: Bool
    var onEditPrice: (UsageReportModel.PriceRow) -> Void = { _ in }
    var onEditWindow: (WindowDraft) -> Void = { _ in }
    var onAddOpenCodeGo: (String) -> Void = { _ in }
    var onClear: () -> Void = {}
    @Environment(\.scheme) private var c

    private func tokens(_ n: Int64) -> String { UsageFormat.shared.tokens(n: n) }

    var body: some View {
        let period = report.periods.first { $0.key == periodKey } ?? report.periods[0]
        let s = period.sums
        VStack(alignment: .leading, spacing: 12) {
            Segmented(options: report.periods.map(\.key), selected: period.key, label: { key in report.periods.first { $0.key == key }?.label ?? key }) { periodKey = $0 }

            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .top, spacing: 18) {
                    BigReadout(label: "tokens", value: tokens(s.tokens))
                    BigReadout(label: "cost · usd", value: s.costLine)
                    BigReadout(label: "requests", value: "\(s.requests)")
                }
                MicroLabel("\(tokens(s.input)) in · \(tokens(s.output)) out · \(tokens(s.cacheRead)) cached")
                if s.onDeviceTokens > 0 { MicroLabel("on this phone · \(tokens(s.onDeviceTokens)) tokens · $0") }
                if s.unpricedTokens > 0 { MicroLabel("\(tokens(s.unpricedTokens)) tokens without a price", color: AutobotColors.amber) }
            }

            let series = report.series.first { $0.key == seriesKey } ?? report.series[0]
            Panel(title: "per \(series.key)", trailing: {
                HStack(spacing: 6) {
                    ForEach(report.series, id: \.key) { item in
                        Tag(item.label, accent: item.key == series.key ? c.primary : c.onSurfaceVariant, filled: item.key == series.key) { seriesKey = item.key }
                    }
                }
            }) {
                UsageBars(series: series)
            }

            HStack {
                SectionLabel("by \(byModel ? "model" : "provider") · \(period.label)").frame(maxWidth: .infinity, alignment: .leading)
                Tag(byModel ? "providers" : "models") { byModel.toggle() }
            }
            let groups = byModel ? period.models : period.providers
            if groups.isEmpty { MicroLabel("nothing counted \(period.label)") }
            ForEach(groups) { groupRow($0) }

            SectionLabel("subscription windows").padding(.top, 8)
            ForEach(report.windows) { w in windowRow(w) }
            if let first = providers.first {
                ChatFlow(spacing: 8) {
                    Tag("+ window", accent: c.primary) {
                        onEditWindow(WindowDraft(window: .init(id: UUID().uuidString, provider: first.id, period: "FIVE_HOURS", anchor: nowMillis()), isNew: true))
                    }
                    ForEach(providers.filter { p in p.presetId == AutobotShared.ProviderPresets.shared.OPENCODE_GO && !report.plan.windows.contains { $0.provider == p.id } }) { p in
                        Tag("+ \(p.displayName) 5 h / week / month", accent: c.primary) { onAddOpenCodeGo(p.id) }
                    }
                }
            } else {
                // An empty flow would still take a row of spacing above this line.
                MicroLabel("add a cloud provider to track its plan")
            }

            SectionLabel("prices · usd per 1m tokens").padding(.top, 8)
            if report.prices.isEmpty { MicroLabel("models show up here once they have answered") }
            ForEach(report.prices) { priceRow($0) }

            Hairline().padding(.vertical, 4)
            Text("Counted on this phone from each provider's own usage report; nothing is sent. Costs use your price table: only Anthropic's published prices ship, every other model stays unknown until you enter its price. Incognito chats are not kept.")
                .textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
            if !report.empty { TextAction("Clear history", color: c.error, action: onClear) }
        }
    }

    private func groupRow(_ g: UsageReportModel.Group) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 8) {
                Text(g.model ?? g.name).textStyle(.code).foregroundStyle(c.onSurface).lineLimit(1).truncationMode(.middle)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if g.onDevice { Tag("on device", accent: c.primary) }
                Text(g.onDevice ? "$0" : g.sums.costLine).textStyle(.readout).foregroundStyle(c.onSurface)
            }
            MicroLabel([
                g.model != nil ? g.name : nil,
                "\(tokens(g.sums.tokens)) tokens",
                "\(g.sums.requests) req",
                g.sums.cacheRead > 0 ? "\(tokens(g.sums.cacheRead)) cached" : nil,
                g.sums.unreported > 0 ? "\(g.sums.unreported) without usage report" : nil,
            ].compactMap { $0 }.joined(separator: " · "), lines: 2)
        }
    }

    private func windowRow(_ w: UsageReportModel.Window) -> some View {
        let accent = w.over ? AutobotColors.amber : c.primary
        let label = report.plan.windows.first { $0.id == w.id }
        return Panel(title: "\(w.providerName) · \(windowLabel(w.period))\(w.rolling ? " · rolling" : "")", accent: accent, trailing: {
            Tag("edit") { if let label { onEditWindow(WindowDraft(window: label, isNew: false)) } }
        }) {
            if let f = w.fraction { InkProgressBar(fraction: min(max(f, 0), 1), height: 2, ink: accent) }
            Text(w.line).textStyle(.bodySmall).foregroundStyle(w.over ? AutobotColors.amber : c.onSurface).fixedSize(horizontal: false, vertical: true)
            if w.limit == nil { MicroLabel("no limit set · tap edit to enter your plan's limit", lines: 2) }
        }
    }

    private func priceRow(_ row: UsageReportModel.PriceRow) -> some View {
        Button { onEditPrice(row) } label: {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 8) {
                    Text(row.model).textStyle(.code).foregroundStyle(c.onSurface).lineLimit(1).truncationMode(.middle)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    switch row.source {
                    case "DEFAULT": Tag("published")
                    case "YOURS": Tag("yours", accent: c.primary)
                    default: Tag("unknown", accent: AutobotColors.amber)
                    }
                }
                MicroLabel(Self.priceLine(row), lines: 2)
            }
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle())
    }

    static func priceLine(_ row: UsageReportModel.PriceRow) -> String {
        if row.input == nil && row.output == nil { return "\(row.providerName) · tap to enter" }
        func p(_ d: Double?) -> String { UsageFormat.shared.price(d: d.map { KotlinDouble(double: $0) }) }
        return [
            row.providerName,
            "in \(p(row.input).isEmpty ? "?" : p(row.input))",
            "out \(p(row.output).isEmpty ? "?" : p(row.output))",
            row.cacheRead.map { "cached \(p($0))" },
            row.cacheWrite.map { "cache write \(p($0))" },
        ].compactMap { $0 }.joined(separator: " · ")
    }

    private func windowLabel(_ period: String) -> String { WindowPeriod.entries.first { $0.name == period }?.label ?? period.lowercased() }
}

/// One bar per day (week, month): ink for the cloud, an outline for what ran on this phone.
struct UsageBars: View {
    let series: UsageReportModel.Series
    @Environment(\.scheme) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Canvas { context, size in
                let n = max(series.bars.count, 1)
                let gap: CGFloat = 3
                let w = (size.width - gap * CGFloat(n - 1)) / CGFloat(n)
                for (i, bar) in series.bars.enumerated() {
                    let x = CGFloat(i) * (w + gap)
                    context.fill(Path(CGRect(x: x, y: size.height - 1, width: w, height: 1)), with: .color(c.outlineVariant))
                    let h = CGFloat(bar.fraction) * size.height
                    guard h > 0 else { continue }
                    let localH = bar.tokens > 0 ? h * CGFloat(bar.onDeviceTokens) / CGFloat(bar.tokens) : 0
                    context.fill(Path(CGRect(x: x, y: size.height - h, width: w, height: h - localH)), with: .color(c.primary))
                    if localH > 0 {
                        context.stroke(Path(CGRect(x: x + 0.5, y: size.height - localH + 0.5, width: w - 1, height: localH - 1)), with: .color(c.onSurfaceVariant), lineWidth: 1)
                    }
                }
            }
            .frame(height: 72)
            .accessibilityElement()
            .accessibilityLabel(series.bars.map { "\($0.label): \(UsageFormat.shared.tokens(n: $0.tokens))" }.joined(separator: ", "))
            HStack {
                MicroLabel(series.bars.first?.label ?? "").frame(maxWidth: .infinity, alignment: .leading)
                MicroLabel("peak \(UsageFormat.shared.tokens(n: series.bars.map(\.tokens).max() ?? 0))").frame(maxWidth: .infinity, alignment: .leading)
                MicroLabel(series.bars.last?.label ?? "")
            }
        }
    }
}

private struct PriceEditorSheet: View {
    let row: UsageReportModel.PriceRow
    let onSave: (String, String, String, String) -> Void
    let onCancel: () -> Void
    @State private var input: String
    @State private var output: String
    @State private var cacheRead: String
    @State private var cacheWrite: String
    /// Saved once: a quick second tap while the sheet closes does nothing.
    @State private var saved = false
    @Environment(\.scheme) private var c

    init(row: UsageReportModel.PriceRow, onSave: @escaping (String, String, String, String) -> Void, onCancel: @escaping () -> Void) {
        self.row = row
        self.onSave = onSave
        self.onCancel = onCancel
        func p(_ d: Double?) -> String { UsageFormat.shared.price(d: d.map { KotlinDouble(double: $0) }) }
        _input = State(initialValue: p(row.input))
        _output = State(initialValue: p(row.output))
        _cacheRead = State(initialValue: p(row.cacheRead))
        _cacheWrite = State(initialValue: p(row.cacheWrite))
    }

    var body: some View {
        ScrollView {
            InkStack(alignment: .leading, spacing: 12) {
                SectionLabel(row.model)
                MicroLabel("\(row.providerName) · usd per 1m tokens")
                ConsoleTextField(text: $input, placeholder: "unknown", label: "Input", kind: .decimal)
                ConsoleTextField(text: $output, placeholder: "unknown", label: "Output", kind: .decimal)
                ConsoleTextField(text: $cacheRead, placeholder: "= input", label: "Cached input", kind: .decimal)
                ConsoleTextField(text: $cacheWrite, placeholder: "= input", label: "Cache write", kind: .decimal)
                Text("From the provider's pricing page. Blank stays unknown; blank cache prices count at the input price.")
                    .textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
                // A mistyped price is not saved as unknown.
                let valid = [input, output, cacheRead, cacheWrite].allSatisfy { Self.isPrice($0) }
                PrimaryButton("Save", enabled: valid && !saved) {
                    guard valid, !saved else { return }
                    saved = true
                    onSave(input, output, cacheRead, cacheWrite)
                }
                TextAction("Cancel", color: c.onSurfaceVariant, action: onCancel).frame(maxWidth: .infinity)
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 20)
        }
        .background(c.surfaceContainerLow.ignoresSafeArea())
        .presentationDetents([.large])
    }

    /// Blank (unknown) or a price [UsageFormat.parsePrice] reads.
    static func isPrice(_ text: String) -> Bool {
        text.trimmingCharacters(in: .whitespaces).isEmpty || UsageFormat.shared.parsePrice(text: text) != nil
    }
}

private struct WindowEditorSheet: View {
    let providers: [Provider]
    let isNew: Bool
    let onSave: (UsageReportModel.Plan.Window) -> Void
    let onDelete: () -> Void
    let onCancel: () -> Void
    @State private var window: UsageReportModel.Plan.Window
    @State private var limit: String
    @State private var saved = false
    @Environment(\.scheme) private var c

    init(initial: WindowDraft, providers: [Provider], onSave: @escaping (UsageReportModel.Plan.Window) -> Void, onDelete: @escaping () -> Void, onCancel: @escaping () -> Void) {
        self.providers = providers
        self.isNew = initial.isNew
        self.onSave = onSave
        self.onDelete = onDelete
        self.onCancel = onCancel
        _window = State(initialValue: initial.window)
        _limit = State(initialValue: initial.window.limit.map { initial.window.unit == "USD" ? UsageFormat.shared.price(d: KotlinDouble(double: $0)) : String(Int64($0)) } ?? "")
    }

    private static let periods = ["FIVE_HOURS", "DAY", "WEEK", "MONTH"]
    private static let units = ["TOKENS", "REQUESTS", "USD"]

    /// `5 h`, `day`, `week`, `month` (Kotlin `WindowPeriod.short`).
    static func short(_ period: String) -> String {
        switch period {
        case "DAY": "day"
        case "WEEK": "week"
        case "MONTH": "month"
        default: "5 h"
        }
    }

    var body: some View {
        let short = Self.short(window.period)
        ScrollView {
            InkStack(alignment: .leading, spacing: 12) {
                SectionLabel(isNew ? "new window" : "window")
                MicroLabel("provider")
                ChatFlow(spacing: 6) {
                    ForEach(providers) { p in
                        Tag(p.displayName, accent: p.id == window.provider ? c.primary : c.onSurfaceVariant, filled: p.id == window.provider) { window.provider = p.id }
                    }
                }
                MicroLabel("length")
                Segmented(options: Self.periods, selected: window.period, label: { Self.short($0) }) { window.period = $0 }
                MicroLabel("counts")
                Segmented(options: [true, false], selected: window.rolling, label: { $0 ? "rolling" : "fixed" }) {
                    window.rolling = $0
                    if window.anchor == nil { window.anchor = nowMillis() }
                }
                if !window.rolling {
                    MicroLabel("resets every \(short) from \(Date(timeIntervalSince1970: Double(window.anchor ?? nowMillis()) / 1000).formatted(date: .abbreviated, time: .shortened))", lines: 2)
                    Tag("it reset just now", accent: c.primary) { window.anchor = nowMillis() }
                }
                MicroLabel("limit")
                Segmented(options: Self.units, selected: window.unit, label: { $0.lowercased() }) { window.unit = $0 }
                ConsoleTextField(text: $limit, placeholder: "none", label: "Limit", kind: .decimal)
                Text("Enter the limit from your plan's page. Rolling counts the last \(short); fixed starts over at the same time each \(short).")
                    .textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
                // Blank is no limit; a mistyped limit is not (Android's `parseLimit`).
                let blank = limit.trimmingCharacters(in: .whitespaces).isEmpty
                let parsed = UsageLimit.parse(limit, unit: window.unit)
                PrimaryButton("Save", enabled: (blank || parsed != nil) && !saved) {
                    guard blank || parsed != nil, !saved else { return }
                    saved = true
                    var w = window
                    w.limit = parsed
                    onSave(w)
                }
                if !isNew {
                    TextAction("Delete window", color: c.error) {
                        guard !saved else { return }
                        saved = true
                        onDelete()
                    }
                    .frame(maxWidth: .infinity)
                }
                TextAction("Cancel", color: c.onSurfaceVariant, action: onCancel).frame(maxWidth: .infinity)
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 20)
        }
        .background(c.surfaceContainerLow.ignoresSafeArea())
        .presentationDetents([.large])
    }
}

/**
 A window limit as typed (port of Android's `parseLimit`): dollars like a price, token and request
 counts as whole numbers that may carry grouping (`1,000,000`, `1.000.000`, `1 000 000`). Nil: not
 a number (blank included).
 */
enum UsageLimit {
    static func parse(_ text: String, unit: String) -> Double? {
        if unit == "USD" { return UsageFormat.shared.parsePrice(text: text)?.doubleValue }
        let t = text.trimmingCharacters(in: .whitespaces)
        guard t.wholeMatch(of: wholeCount) != nil else { return nil }
        return Int64(String(t.filter { $0.isASCII && $0.isNumber })).map(Double.init)
    }

    /// `1500000`, or digits grouped by threes with one separator (`1,500,000`); `1.5` is not a count.
    nonisolated(unsafe) private static let wholeCount = /[0-9]+|[0-9]{1,3}([,. _'\u{00A0}\u{202F}])[0-9]{3}(\1[0-9]{3})*/
}
