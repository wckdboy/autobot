import AutobotShared
import Charts
import SwiftUI

/**
 The spend and quota board (port of `UsageScreen.kt`): tokens and cost by period, a bar per day
 (week, month), the breakdown by provider and model, subscription windows with what is left, and
 the price table. Computed on this phone from the providers' usage reports; nothing is sent.
 */
struct UsageScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @State private var periodKey = "day"
    @State private var seriesKey = "day"
    @State private var byModel = false
    @State private var editingPrice: UsageReportModel.PriceRow?
    @State private var editingWindow: WindowDraft?
    @State private var confirmClear = false

    var body: some View {
        VStack(spacing: 0) {
            AutobotTopBar(title: "Usage", onBack: { nav.back() }) {
                RowInfo(title: "Usage", text: UsageWords.shared.ABOUT)
            }
            if let report = app.usage.report {
                List {
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
                }
                .listStyle(.insetGrouped)
            } else {
                ProgressView("counting…").frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
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
        .confirmAlert($confirmClear, title: "Clear usage history?", message: "The counted tokens are removed from this phone. Prices and windows stay.", confirm: "Clear") {
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

/// The board itself, from a report (testable without a runtime): the sections of a `List`.
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

    private func tokens(_ n: Int64) -> String { UsageFormat.shared.tokens(n: n) }

    var body: some View {
        let period = report.periods.first { $0.key == periodKey } ?? report.periods[0]
        let s = period.sums
        Section {
            Picker("Period", selection: $periodKey) {
                ForEach(report.periods, id: \.key) { Text($0.label).tag($0.key) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .top, spacing: 18) { readouts(s) }
                VStack(alignment: .leading, spacing: 8) { readouts(s) }
            }
            VStack(alignment: .leading, spacing: 2) {
                Text("\(tokens(s.input)) in · \(tokens(s.output)) out · \(tokens(s.cacheRead)) cached")
                if s.onDeviceTokens > 0 { Text("on this phone · \(tokens(s.onDeviceTokens)) tokens · $0") }
                if s.unpricedTokens > 0 { Text("\(tokens(s.unpricedTokens)) tokens without a price").foregroundStyle(.orange) }
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }

        let series = report.series.first { $0.key == seriesKey } ?? report.series[0]
        Section {
            UsageBars(series: series)
        } header: {
            HStack {
                Text("Per \(series.key)")
                Spacer()
                Picker("Series", selection: $seriesKey) {
                    ForEach(report.series, id: \.key) { Text($0.label).tag($0.key) }
                }
                .pickerStyle(.menu)
                .labelsHidden()
                .textCase(nil)
            }
        }

        let groups = byModel ? period.models : period.providers
        Section {
            if groups.isEmpty { Text("nothing counted \(period.label)").foregroundStyle(.secondary) }
            ForEach(groups) { groupRow($0) }
        } header: {
            HStack {
                Text("By \(byModel ? "model" : "provider") · \(period.label)")
                Spacer()
                Button(byModel ? "providers" : "models") { byModel.toggle() }
                    .font(.subheadline)
                    .textCase(nil)
            }
        }

        Section("Subscription windows") {
            ForEach(report.windows) { w in windowRow(w) }
            if let first = providers.first {
                Button("+ window", systemImage: "plus") {
                    onEditWindow(WindowDraft(window: .init(id: UUID().uuidString, provider: first.id, period: "FIVE_HOURS", anchor: nowMillis()), isNew: true))
                }
                ForEach(providers.filter { p in p.presetId == AutobotShared.ProviderPresets.shared.OPENCODE_GO && !report.plan.windows.contains { $0.provider == p.id } }) { p in
                    Button("+ \(p.displayName) 5 h / week / month", systemImage: "plus") { onAddOpenCodeGo(p.id) }
                }
            } else {
                Text("add a cloud provider to track its plan").foregroundStyle(.secondary)
            }
        }

        Section(UsageWords.shared.PRICES.sentenceCased) {
            if report.prices.isEmpty { Text("models show up here once they have answered").foregroundStyle(.secondary) }
            ForEach(report.prices) { priceRow($0) }
        }

        if !report.empty {
            Section {
                Button("Clear history", role: .destructive, action: onClear).foregroundStyle(.red)
            }
        }
    }

    @ViewBuilder private func readouts(_ s: UsageReportModel.Sums) -> some View {
        readout("tokens", tokens(s.tokens))
        readout(UsageWords.shared.COST, s.costLine)
        readout("requests", "\(s.requests)")
    }

    private func readout(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).font(.title2.weight(.semibold)).monospacedDigit().lineLimit(1).minimumScaleFactor(0.6)
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
    }

    private func groupRow(_ g: UsageReportModel.Group) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 8) {
                Text(g.model ?? g.name).font(g.model != nil ? .callout.monospaced() : .body).lineLimit(1).truncationMode(.middle)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if g.onDevice { CapsuleTag("on device") }
                Text(g.onDevice ? "$0" : g.sums.costLine).monospacedDigit()
            }
            Text([
                g.model != nil ? g.name : nil,
                "\(tokens(g.sums.tokens)) tokens",
                "\(g.sums.requests) req",
                g.sums.cacheRead > 0 ? "\(tokens(g.sums.cacheRead)) cached" : nil,
                g.sums.unreported > 0 ? "\(g.sums.unreported) without usage report" : nil,
            ].compactMap { $0 }.joined(separator: " · "))
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(2)
        }
    }

    private func windowRow(_ w: UsageReportModel.Window) -> some View {
        let label = report.plan.windows.first { $0.id == w.id }
        return VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("\(w.providerName) · \(windowLabel(w.period))\(w.rolling ? " · rolling" : "")").font(.subheadline.weight(.semibold))
                Spacer()
                Button("Edit") { if let label { onEditWindow(WindowDraft(window: label, isNew: false)) } }
                    .buttonStyle(.borderless)
            }
            if let f = w.fraction {
                ProgressView(value: min(max(f, 0), 1)).tint(w.over ? .orange : nil)
            }
            Text(w.line).font(.subheadline).foregroundStyle(w.over ? Color.orange : .primary).fixedSize(horizontal: false, vertical: true)
            if w.limit == nil { Text("no limit set · tap edit to enter your plan's limit").font(.caption).foregroundStyle(.secondary) }
        }
    }

    private func priceRow(_ row: UsageReportModel.PriceRow) -> some View {
        Button { onEditPrice(row) } label: {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 8) {
                    Text(row.model).font(.callout.monospaced()).lineLimit(1).truncationMode(.middle)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    switch row.source {
                    case "DEFAULT": CapsuleTag("published", color: .secondary)
                    case "YOURS": CapsuleTag("yours")
                    default: CapsuleTag("unknown", color: .orange)
                    }
                }
                Text(Self.priceLine(row)).font(.caption).foregroundStyle(.secondary).lineLimit(2)
            }
            .contentShape(Rectangle())
        }
        .foregroundStyle(.primary)
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

/// One bar per day (week, month), a Swift Charts bar chart: the tint for the cloud, grey for what
/// ran on this phone.
struct UsageBars: View {
    let series: UsageReportModel.Series

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Chart {
                ForEach(Array(series.bars.enumerated()), id: \.offset) { i, bar in
                    BarMark(x: .value("Bar", i), y: .value("Tokens", bar.tokens - bar.onDeviceTokens))
                        .foregroundStyle(by: .value("Where", "cloud"))
                    if bar.onDeviceTokens > 0 {
                        BarMark(x: .value("Bar", i), y: .value("Tokens", bar.onDeviceTokens))
                            .foregroundStyle(by: .value("Where", "this phone"))
                    }
                }
            }
            .chartForegroundStyleScale(["cloud": Color.accentColor, "this phone": Color.secondary])
            .chartLegend(.hidden)
            .chartXAxis(.hidden)
            .chartYAxis(.hidden)
            .frame(height: 72)
            .accessibilityElement()
            .accessibilityLabel(series.bars.map { "\($0.label): \(UsageFormat.shared.tokens(n: $0.tokens))" }.joined(separator: ", "))
            HStack {
                Text(series.bars.first?.label ?? "").frame(maxWidth: .infinity, alignment: .leading)
                Text("peak \(UsageFormat.shared.tokens(n: series.bars.map(\.tokens).max() ?? 0))").frame(maxWidth: .infinity, alignment: .center)
                Text(series.bars.last?.label ?? "").frame(maxWidth: .infinity, alignment: .trailing)
            }
            .font(.caption)
            .foregroundStyle(.secondary)
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
        // A mistyped price is not saved as unknown.
        let valid = [input, output, cacheRead, cacheWrite].allSatisfy { Self.isPrice($0) }
        NavigationStack {
            Form {
                Section {
                    FormField("Input", text: $input, prompt: "unknown", kind: .decimal)
                    FormField("Output", text: $output, prompt: "unknown", kind: .decimal)
                    FormField("Cached input", text: $cacheRead, prompt: "= input", kind: .decimal)
                    FormField("Cache write", text: $cacheWrite, prompt: "= input", kind: .decimal)
                } header: {
                    Text("\(row.providerName) · \(UsageWords.shared.PRICE_UNIT)")
                } footer: {
                    Text("From the provider's pricing page. Blank stays unknown; blank cache prices count at the input price.")
                }
            }
            .formStyle(.grouped)
            .navigationTitle(row.model)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onCancel) }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        guard valid, !saved else { return }
                        saved = true
                        onSave(input, output, cacheRead, cacheWrite)
                    }
                    .disabled(!valid || saved)
                }
            }
        }
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
        // Blank is no limit; a mistyped limit is not (Android's `parseLimit`).
        let blank = limit.trimmingCharacters(in: .whitespaces).isEmpty
        let parsed = UsageLimit.parse(limit, unit: window.unit)
        NavigationStack {
            Form {
                Section {
                    Picker("Provider", selection: $window.provider) {
                        ForEach(providers) { Text($0.displayName).tag($0.id) }
                    }
                    Picker("Length", selection: $window.period) {
                        ForEach(Self.periods, id: \.self) { Text(Self.short($0)).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    Picker("Counts", selection: Binding(get: { window.rolling }, set: {
                        window.rolling = $0
                        if window.anchor == nil { window.anchor = nowMillis() }
                    })) {
                        Text("rolling").tag(true)
                        Text("fixed").tag(false)
                    }
                    .pickerStyle(.segmented)
                    if !window.rolling {
                        Text("resets every \(short) from \(Date(timeIntervalSince1970: Double(window.anchor ?? nowMillis()) / 1000).formatted(date: .abbreviated, time: .shortened))")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                        Button("it reset just now") { window.anchor = nowMillis() }
                    }
                }
                Section {
                    Picker("Unit", selection: $window.unit) {
                        ForEach(Self.units, id: \.self) { Text($0.lowercased()).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    FormField("Limit", text: $limit, prompt: "none", kind: .decimal)
                } header: {
                    Text("Limit")
                } footer: {
                    Text("Enter the limit from your plan's page. Rolling counts the last \(short); fixed starts over at the same time each \(short).")
                }
                if !isNew {
                    Section {
                        Button("Delete window", role: .destructive) {
                            guard !saved else { return }
                            saved = true
                            onDelete()
                        }
                        .foregroundStyle(.red)
                    }
                }
            }
            .formStyle(.grouped)
            .navigationTitle(isNew ? "New window" : "Window")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onCancel) }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        guard blank || parsed != nil, !saved else { return }
                        saved = true
                        var w = window
                        w.limit = parsed
                        onSave(w)
                    }
                    .disabled(!(blank || parsed != nil) || saved)
                }
            }
        }
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
