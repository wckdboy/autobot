import AutobotShared
import SwiftUI

// Prompt tools (spec §1): the prompt with wildcard preview, weighted-token helper and the prompt
// library; negative presets; seed lock / walk; the X/Y grid builder and its result grid; controls
// rendered from a workflow's parameter schema. Rows for the rooms' Forms.

/// The prompt as typed, with `{a|b}` expansion count, emphasis ± on the selection and the library.
struct PromptBox: View {
    @Binding var prompt: String
    @Binding var negative: String
    var placeholder = "Describe it…"
    var showNegative = true
    var negativePresets: [PromptSnippet] = PromptLibrary.shared.negativePresets
    @State private var selection: TextSelection?
    @State private var showLibrary = false

    var body: some View {
        let expansion = Wildcards.shared.hasWildcards(template: prompt) ? Wildcards.shared.expand(template: prompt, cap: 64) : nil
        VStack(alignment: .leading, spacing: 10) {
            TextField(text: $prompt, selection: $selection, prompt: Text(placeholder), axis: .vertical) { Text("Prompt") }
                .font(.title3)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .lineLimit(2 ... 8)
                .accessibilityLabel("Prompt")
            HStack(spacing: 6) {
                Group {
                    if let expansion {
                        let n = expansion.total
                        Text(n > 64 ? "Wildcards · \(n) prompts · first 64" : "Wildcards · \(n) prompt\(n == 1 ? "" : "s")")
                            .accessibilityLabel("Wildcards expand to \(n) prompts")
                    } else {
                        Text("~\(PromptSyntax.estimateTokens(prompt)) tokens")
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                Spacer(minLength: 4)
                StudioChip("( − )") { weight(-0.1) }.accessibilityLabel("Less emphasis on the selected words")
                StudioChip("( + )") { weight(0.1) }.accessibilityLabel("More emphasis on the selected words")
                StudioChip("Library", active: true) { showLibrary = true }
            }
            if let expansion, expansion.prompts.count > 1 {
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(Array(expansion.prompts.prefix(3).enumerated()), id: \.offset) { _, p in
                        Text("· \(p)").font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                    if expansion.prompts.count > 3 { Text("+ \(Int(expansion.total) - 3) more").font(.caption).foregroundStyle(.secondary) }
                }
            }
            if showNegative {
                Divider()
                TextField("Negative", text: $negative, prompt: Text("What to avoid"), axis: .vertical)
                    .lineLimit(1 ... 4)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        Text("Presets").font(.caption).foregroundStyle(.secondary)
                        ForEach(negativePresets, id: \.id) { p in
                            StudioChip(p.name) { negative = PromptLibrary.shared.joinNegative(current: negative, add: p.text) }
                                .accessibilityLabel("Add the \(p.name) negative preset")
                        }
                    }
                }
            }
        }
        .padding(.vertical, 4)
        .sheet(isPresented: $showLibrary) {
            PromptLibrarySheet(prompt: $prompt, negative: $negative, cursor: cursor) { showLibrary = false }
                .presentationDetents([.medium, .large])
        }
    }

    /// The selection (or the caret) as UTF-16 offsets into the prompt.
    private var range: (Int, Int) {
        guard let selection else { return (prompt.utf16.count, prompt.utf16.count) }
        switch selection.indices {
        case .selection(let r):
            return (r.lowerBound.utf16Offset(in: prompt), r.upperBound.utf16Offset(in: prompt))
        default:
            return (prompt.utf16.count, prompt.utf16.count)
        }
    }

    private var cursor: Int { range.1 }

    private func weight(_ delta: Double) {
        let (s, e) = range
        let result = PromptLibrary.shared.adjustWeight(prompt: prompt, start: Int32(s), end: Int32(e), delta: delta)
        prompt = result.first as String? ?? prompt
        if let start = result.second?.intValue, let end = result.third?.intValue {
            let u = prompt.utf16
            if let a = u.index(u.startIndex, offsetBy: start, limitedBy: u.endIndex), let b = u.index(u.startIndex, offsetBy: end, limitedBy: u.endIndex),
               let lo = String.Index(a, within: prompt), let hi = String.Index(b, within: prompt) {
                selection = TextSelection(range: lo ..< hi)
            }
        }
    }
}

/// Saved snippets (inserted at the cursor) and styles (wrap the prompt, add negatives): built-in and the user's.
struct PromptLibrarySheet: View {
    @Binding var prompt: String
    @Binding var negative: String
    let cursor: Int
    let onDone: () -> Void
    @Environment(AppContainer.self) private var app
    @State private var tab = PromptSnippet.companion.STYLE
    @State private var newName = ""
    @State private var newText = ""

    var body: some View {
        let studio = app.studio
        let mine = studio.library.snippets.filter { $0.kind == tab }
        let builtIn = tab == PromptSnippet.companion.STYLE ? PromptLibrary.shared.builtInStyles : []
        let isStyle = tab == PromptSnippet.companion.STYLE
        NavigationStack {
            Form {
                Section {
                    Picker("Kind", selection: $tab) {
                        Text("Styles").tag(PromptSnippet.companion.STYLE)
                        Text("Snippets").tag(PromptSnippet.companion.SNIPPET)
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                } footer: {
                    Text(isStyle ? "A style wraps your prompt ({prompt}) and adds its negatives." : "A snippet is inserted at the cursor.")
                }
                Section {
                    ForEach(builtIn + mine, id: \.id) { s in
                        Button { use(s) } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(s.name).font(.headline)
                                Text(s.text).font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .tint(.primary)
                        .accessibilityLabel("Use \(s.name)")
                        .swipeActions {
                            if !s.id.hasPrefix("style-") {
                                Button("Delete \(s.name)", systemImage: "trash", role: .destructive) { studio.deleteSnippet(s.id) }
                            }
                        }
                        .contextMenu {
                            if !s.id.hasPrefix("style-") {
                                Button("Delete \(s.name)", systemImage: "trash", role: .destructive) { studio.deleteSnippet(s.id) }
                            }
                        }
                    }
                    if builtIn.isEmpty && mine.isEmpty { Text("Nothing saved yet").foregroundStyle(.secondary) }
                }
                Section("Save") {
                    TextField("Name", text: $newName)
                    TextField("Text", text: $newText, prompt: Text(isStyle ? "{prompt}, film grain, 35mm" : "soft rim light"), axis: .vertical)
                        .lineLimit(1 ... 4)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    Button("From prompt") { newText = isStyle ? "{prompt}, " : prompt }
                    Button("Save") {
                        studio.saveSnippet(PromptSnippet(id: StudioIds.shared.doNew(prefix: "s", random: KotlinRandom.companion), name: newName.trimmingCharacters(in: .whitespaces),
                                                         text: newText, kind: tab, negative: isStyle ? negative : ""))
                        newName = ""
                        newText = ""
                    }
                    .disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty || newText.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .navigationTitle("Prompt library")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done", action: onDone) } }
        }
    }

    private func use(_ s: PromptSnippet) {
        if s.kind == PromptSnippet.companion.STYLE {
            let r = PromptLibrary.shared.applyStyle(prompt: prompt, negative: negative, style: s)
            prompt = (r.first as String?) ?? prompt
            negative = (r.second as String?) ?? negative
        } else {
            let u = prompt.utf16.count
            let r = PromptLibrary.shared.insert(prompt: prompt, cursor: Int32(min(cursor, u)), snippet: s.text)
            prompt = (r.first as String?) ?? prompt
        }
        onDone()
    }
}

/// Seed: random each time, locked, or walking from a base (`seed`, `seed+1`, …). A Form section.
struct SeedControl: View {
    @Binding var mode: SeedMode
    @Binding var seed: Int64

    var body: some View {
        Section("Seed") {
            Picker("Seed", selection: $mode) {
                Text("Random").tag(SeedMode.random)
                Text("Lock").tag(SeedMode.locked)
                Text("Walk").tag(SeedMode.walk)
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            if mode != .random {
                LabeledContent("Seed") {
                    HStack(spacing: 12) {
                        Text("\(seed)").monospacedDigit()
                        Button { seed = Int64.random(in: 0 ... 4_294_967_295) } label: { Image(systemName: "dice") }
                            .buttonStyle(.borderless)
                            .accessibilityLabel("New seed")
                    }
                }
            }
        }
    }
}

/// A menu picker over [options] (a value not among them still shows as chosen).
struct OptionPicker<T: Hashable>: View {
    let label: String
    let selection: T?
    let options: [T]
    var optionLabel: (T) -> String = { "\($0)" }
    let onSelect: (T) -> Void

    init(_ label: String, selection: T?, options: [T], optionLabel: @escaping (T) -> String = { "\($0)" }, onSelect: @escaping (T) -> Void) {
        self.label = label
        self.selection = selection
        self.options = options
        self.optionLabel = optionLabel
        self.onSelect = onSelect
    }

    var body: some View {
        if let first = options.first {
            Picker(label, selection: Binding(get: { selection ?? first }, set: onSelect)) {
                if let selection, !options.contains(selection) { Text(optionLabel(selection)).tag(selection) }
                ForEach(options, id: \.self) { Text(optionLabel($0)).tag($0) }
            }
            .pickerStyle(.menu)
        } else {
            LabeledContent(label, value: selection.map(optionLabel) ?? "—")
        }
    }
}

/// A text field under its label (a Form row), with the keyboard of its [kind].
struct StackedField: View {
    let label: String
    @Binding var text: String
    var prompt = ""
    var kind: FieldKind = .prose
    var lines: ClosedRange<Int>? = nil
    var code = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.caption).foregroundStyle(.secondary)
            Group {
                if kind == .secret {
                    SecureField(label, text: $text, prompt: Text(prompt)).textContentType(.oneTimeCode)
                } else if let lines {
                    TextField(label, text: $text, prompt: Text(prompt), axis: .vertical).lineLimit(lines)
                } else {
                    TextField(label, text: $text, prompt: Text(prompt))
                }
            }
            .font(code ? .callout.monospaced() : .body)
            .autocorrectionDisabled(kind != .prose)
            .textInputAutocapitalization(kind == .prose ? .sentences : .never)
            .keyboardType(kind == .url ? .URL : kind == .number ? .numberPad : kind == .decimal ? .numbersAndPunctuation : .default)
            .privacySensitive(kind == .secret)
        }
        .padding(.vertical, 2)
    }
}

// MARK: - X/Y grid

/// A parameter a grid can vary for an engine, with whether its values are integers.
struct GridParam: Hashable {
    let name: String
    let label: String
    let integer: Bool
    var hint: String = "10, 20, 30"
}

/// Build an X/Y grid: one or two params over value lists (`10, 20` · `5-9` · `1-2 (+0.25)`).
struct GridBuilderSheet: View {
    let params: [GridParam]
    let onRun: (GridAxis, GridAxis?) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var x: GridParam?
    @State private var y: GridParam?
    @State private var xText = ""
    @State private var yText = ""

    var body: some View {
        let xs = x.map { GridPlanner.shared.parseValues(text: xText, integer: $0.integer) } ?? []
        let ys = y.map { GridPlanner.shared.parseValues(text: yText, integer: $0.integer) } ?? []
        let cells = xs.count * max(ys.count, 1)
        let ready = x != nil && !xs.isEmpty && (y == nil || !ys.isEmpty)
        NavigationStack {
            Form {
                Section {
                    Picker("X axis", selection: Binding(get: { x }, set: { p in
                        x = p
                        if let p, xText.isEmpty { xText = p.hint }
                    })) {
                        Text("Choose").tag(GridParam?.none)
                        ForEach(params, id: \.self) { Text($0.label.sentenceCased).tag(GridParam?.some($0)) }
                    }
                    if let x { StackedField(label: "\(x.label.sentenceCased) values", text: $xText, prompt: x.hint, kind: .mono) }
                } footer: {
                    Text("One job per cell. Values like 10, 20, 30 · 5-9 · 1-2 (+0.25) · euler, dpm++ 2m")
                }
                Section {
                    Picker("Y axis", selection: Binding(get: { y }, set: { p in
                        y = p
                        if let p, yText.isEmpty { yText = p.hint }
                    })) {
                        Text("None").tag(GridParam?.none)
                        ForEach(params.filter { $0 != x }, id: \.self) { Text($0.label.sentenceCased).tag(GridParam?.some($0)) }
                    }
                    if let y { StackedField(label: "\(y.label.sentenceCased) values", text: $yText, prompt: y.hint, kind: .mono) }
                } footer: {
                    Text(cells > 64 ? "\(cells) cells · the first 64 run" : "\(cells) cell\(cells == 1 ? "" : "s")")
                        .foregroundStyle(cells > 64 ? AnyShapeStyle(.red) : AnyShapeStyle(.secondary))
                }
                Section {
                    Button("Queue grid") {
                        guard let x else { return }
                        onRun(GridAxis(param: x.name, values: xs, label: x.label), y.map { GridAxis(param: $0.name, values: ys, label: $0.label) })
                    }
                    .disabled(!ready)
                }
            }
            .navigationTitle("X/Y grid")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
    }
}

/// A grid run's results, labelled on both axes; cells fill in as their jobs finish.
struct GridResultView: View {
    let run: GridRun
    var onOpen: (StudioAsset) -> Void = { _ in }
    @Environment(AppContainer.self) private var app

    var body: some View {
        let cols = max(run.columns.count, 1)
        VStack(alignment: .leading, spacing: 4) {
            Text("\(run.yLabel.map { "\($0) ↓ · " } ?? "")\(run.xLabel) →\(run.truncated ? " · truncated at 64" : "")")
                .font(.caption)
                .foregroundStyle(.secondary)
            ScrollView(.horizontal, showsIndicators: false) {
                Grid(horizontalSpacing: 3, verticalSpacing: 3) {
                    GridRow {
                        if run.yLabel != nil { Color.clear.frame(width: 44, height: 1) }
                        ForEach(run.columns.indices, id: \.self) { i in
                            Text(run.columns[i]).font(.caption2).foregroundStyle(.secondary).lineLimit(1).frame(width: cellSide(cols))
                        }
                    }
                    ForEach(run.jobIds.indices, id: \.self) { r in
                        GridRow {
                            if run.yLabel != nil {
                                Text(run.rows.indices.contains(r) ? run.rows[r] : "").font(.caption2).foregroundStyle(.secondary).lineLimit(2).frame(width: 44)
                            }
                            ForEach(run.jobIds[r], id: \.self) { id in
                                GridCell(jobId: id, onOpen: onOpen).frame(width: cellSide(cols), height: cellSide(cols))
                            }
                        }
                    }
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Grid of \(run.xLabel)\(run.yLabel.map { " by \($0)" } ?? "")")
    }

    private func cellSide(_ cols: Int) -> CGFloat { cols <= 2 ? 150 : cols <= 4 ? 84 : 64 }
}

private struct GridCell: View {
    let jobId: String
    let onOpen: (StudioAsset) -> Void
    @Environment(AppContainer.self) private var app

    var body: some View {
        let job = app.studio.job(jobId)
        let asset = job?.outputs.first.flatMap { app.studio.library.asset($0) }
        ZStack {
            if let asset {
                AssetThumb(asset: asset).onTapGesture { onOpen(asset) }.accessibilityAddTraits(.isButton)
            } else {
                Color(.tertiarySystemFill)
                if job?.state == .running {
                    if let job, job.steps > 0 {
                        ProgressView(value: Double(job.progress)).progressViewStyle(.circular)
                    } else {
                        ProgressView()
                    }
                }
                if job?.state == .failed { Image(systemName: "exclamationmark.triangle").foregroundStyle(.red) }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
        .accessibilityLabel(job?.label ?? "cell")
    }
}

// MARK: - Workflow parameters

/**
 What a number field shows while it is typed. Text that is not a number yet ("", "-", "1.", "1e")
 stays as typed instead of being replaced by the parsed value (or the default) on the next render,
 which used to eat characters; only complete numbers reach the parameter.
 */
enum NumberDraft {
    static func parse(_ text: String, integer: Bool) -> ParamValue? {
        let t = text.trimmingCharacters(in: .whitespaces)
        return integer ? Int64(t).map(ParamValue.int64) : Double(t).flatMap { $0.isFinite ? ParamValue.double($0) : nil }
    }

    /// Whether the typed [draft] still stands for [shown] (the parameter's value as displayed):
    /// false once the value changed from elsewhere (a reset, a preset), so the field follows it.
    static func keeps(_ draft: String, shown: String, integer: Bool) -> Bool {
        guard let parsed = parse(draft, integer: integer) else { return true }
        return parsed.display() == shown
    }
}

/// A workflow number typed as text (see [NumberDraft]).
private struct NumberParamField: View {
    let label: String
    let integer: Bool
    @Binding var value: ParamValue?
    let fallback: ParamValue?
    @State private var draft: String?

    var body: some View {
        let shown = (value ?? fallback)?.display() ?? ""
        LabeledContent(label) {
            TextField(label, text: Binding(get: { draft ?? shown }, set: { t in
                draft = t
                if let parsed = NumberDraft.parse(t, integer: integer) { value = parsed } else if t.isEmpty { value = nil }
            }))
            .keyboardType(.numbersAndPunctuation)
            .autocorrectionDisabled()
            .textInputAutocapitalization(.never)
            .multilineTextAlignment(.trailing)
            .monospacedDigit()
        }
        .onChange(of: shown) { _, now in
            if let d = draft, !NumberDraft.keeps(d, shown: now, integer: integer) { draft = nil }
        }
    }
}

/// One control for a workflow parameter, by its type (schema-rendered UI, spec §5): a Form row.
struct WorkflowParamControl: View {
    let param: WorkflowParam
    @Binding var value: ParamValue?
    @Environment(AppContainer.self) private var app

    var body: some View {
        let current = value ?? param.default_
        switch param.type {
        case .int_, .float_:
            if let lo = param.min?.doubleValue, let hi = param.max?.doubleValue, hi > lo, hi - lo <= 100_000 {
                let isInt = param.type == .int_
                LabeledSlider(param.label, value: Binding(get: { current?.asDouble()?.doubleValue ?? lo }, set: { value = isInt ? .int64(Int64($0.rounded())) : .double($0) }),
                              in: lo ... hi, step: param.step?.doubleValue ?? (isInt ? 1 : nil), format: { isInt ? "\(Int($0))" : String(format: "%.2f", $0) })
            } else {
                NumberParamField(label: param.label, integer: param.type == .int_, value: $value, fallback: param.default_)
            }
        case .seed:
            LabeledContent(param.label) {
                HStack(spacing: 12) {
                    let v = current?.asLong()?.int64Value ?? -1
                    Text(v < 0 ? "Random" : "\(v)").monospacedDigit()
                    Button { value = .int64(Int64.random(in: 0 ... 4_294_967_295)) } label: { Image(systemName: "dice") }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("New seed")
                    Button("Random") { value = .int64(-1) }.buttonStyle(.borderless)
                }
            }
        case .bool_:
            Toggle(param.label, isOn: Binding(get: { (current as? ParamValue.BoolValue)?.value ?? false }, set: { value = .flag($0) }))
        case .enum_:
            OptionPicker(param.label, selection: current?.display(), options: param.options) { value = .text($0) }
        case .image, .audio:
            AssetParamPicker(label: param.label, type: param.type == .image ? .image : .audio, role: param.role ?? param.name, value: $value)
        default:
            StackedField(label: param.label, text: Binding(get: { current?.display() ?? "" }, set: { value = .text($0) }), kind: .mono,
                         lines: param.multiline ? 2 ... 8 : nil)
        }
    }
}

/// Picks an input asset from the library for a workflow's image / audio parameter.
struct AssetParamPicker: View {
    let label: String
    let type: AssetType
    let role: String
    @Binding var value: ParamValue?
    @Environment(AppContainer.self) private var app

    var body: some View {
        let studio = app.studio
        let chosen = (value as? ParamValue.AssetValue).flatMap { studio.library.asset($0.ref.assetId) }
        let options = Array(studio.library.assets.filter { $0.type == type }.prefix(40))
        LabeledContent {
            HStack(spacing: 10) {
                if let chosen { AssetThumb(asset: chosen, size: 44) }
                Menu {
                    ForEach(options, id: \.id) { a in
                        Button(StudioText.describe(a)) { value = ParamValue.AssetValue(ref: AssetRef(assetId: a.id, role: role)) }
                    }
                    if chosen != nil { Button("None", role: .destructive) { value = nil } }
                } label: {
                    Text(options.isEmpty ? "No \(type == .image ? "images" : "clips") in the library" : chosen == nil ? "Choose" : "Change")
                }
                .disabled(options.isEmpty)
            }
        } label: {
            Text(label)
        }
    }
}

/// The values a workflow's form shows (Form rows), with "More" for the advanced ones.
struct WorkflowForm: View {
    let workflow: StudioWorkflow
    @Binding var values: [String: ParamValue]
    /// Parameters the room shows itself (the prompt box, the seed control).
    var exclude: (WorkflowParam) -> Bool = { _ in false }
    @State private var more = false

    var body: some View {
        let shown = workflow.params.filter { !exclude($0) }
        let basic = shown.filter { !$0.advanced }
        let advanced = shown.filter { $0.advanced }
        ForEach(basic, id: \.name) { p in control(p) }
        if !advanced.isEmpty {
            DisclosureGroup("More · \(advanced.count)", isExpanded: $more) {
                ForEach(advanced, id: \.name) { p in control(p) }
            }
        }
    }

    private func control(_ p: WorkflowParam) -> some View {
        WorkflowParamControl(param: p, value: Binding(get: { values[p.name] }, set: { values[p.name] = $0 }))
    }
}
