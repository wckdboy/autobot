import AutobotShared
import SwiftUI

// Prompt tools (spec §1): the prompt with wildcard preview, weighted-token helper and the prompt
// library; negative presets; seed lock / walk; the X/Y grid builder and its result grid; controls
// rendered from a workflow's parameter schema.

/// The prompt as typed, with `{a|b}` expansion count, emphasis ± on the selection and the library.
struct PromptBox: View {
    @Binding var prompt: String
    @Binding var negative: String
    var placeholder = "Describe it…"
    var showNegative = true
    var negativePresets: [PromptSnippet] = PromptLibrary.shared.negativePresets
    @Environment(\.scheme) private var c
    @State private var selection: TextSelection?
    @State private var showLibrary = false

    var body: some View {
        let expansion = Wildcards.shared.hasWildcards(template: prompt) ? Wildcards.shared.expand(template: prompt, cap: 64) : nil
        VStack(alignment: .leading, spacing: 8) {
            TextField(text: $prompt, selection: $selection, prompt: Text(placeholder).foregroundColor(c.outline), axis: .vertical) { Text("Prompt") }
                .textStyle(.headlineSmall)
                .foregroundStyle(c.onSurface)
                .tint(c.primary)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .lineLimit(2 ... 8)
                .accessibilityLabel("Prompt")
            HStack(spacing: 6) {
                if let expansion {
                    let n = expansion.total
                    MicroLabel(n > 64 ? "wildcards · \(n) prompts · first 64" : "wildcards · \(n) prompt\(n == 1 ? "" : "s")", color: c.primary)
                        .accessibilityLabel("Wildcards expand to \(n) prompts")
                } else {
                    MicroLabel("~\(PromptSyntax.estimateTokens(prompt)) tokens")
                }
                Spacer(minLength: 4)
                Tag("( − )") { weight(-0.1) }.accessibilityLabel("Less emphasis on the selected words")
                Tag("( + )") { weight(0.1) }.accessibilityLabel("More emphasis on the selected words")
                Tag("library", accent: c.primary) { showLibrary = true }
            }
            if let expansion, expansion.prompts.count > 1 {
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(Array(expansion.prompts.prefix(3).enumerated()), id: \.offset) { _, p in
                        Text("· \(p)").textStyle(.codeBlock).foregroundStyle(c.onSurfaceVariant).lineLimit(1)
                    }
                    if expansion.prompts.count > 3 { MicroLabel("+ \(Int(expansion.total) - 3) more") }
                }
            }
            if showNegative {
                ConsoleTextField(text: $negative, placeholder: "what to avoid", label: "Negative", kind: .mono, minLines: 1, maxLines: 4)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        MicroLabel("presets")
                        ForEach(negativePresets, id: \.id) { p in
                            Tag(p.name) { negative = PromptLibrary.shared.joinNegative(current: negative, add: p.text) }
                                .accessibilityLabel("Add the \(p.name) negative preset")
                        }
                    }
                }
            }
        }
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
    @Environment(\.scheme) private var c
    @State private var tab = PromptSnippet.companion.STYLE
    @State private var newName = ""
    @State private var newText = ""

    var body: some View {
        let studio = app.studio
        let mine = studio.library.snippets.filter { $0.kind == tab }
        let builtIn = tab == PromptSnippet.companion.STYLE ? PromptLibrary.shared.builtInStyles : []
        ScrollView {
            InkStack(alignment: .leading, spacing: 12) {
                SectionLabel("prompt library")
                Segmented(options: [PromptSnippet.companion.STYLE, PromptSnippet.companion.SNIPPET], selected: tab, label: { $0 == PromptSnippet.companion.STYLE ? "styles" : "snippets" }) { tab = $0 }
                MicroLabel(tab == PromptSnippet.companion.STYLE ? "a style wraps your prompt ({prompt}) and adds its negatives" : "a snippet is inserted at the cursor", lines: 2)
                VStack(spacing: 0) {
                    ForEach(builtIn + mine, id: \.id) { s in
                        HStack(spacing: 10) {
                            Button { use(s) } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(s.name).textStyle(.titleSmall).foregroundStyle(c.onSurface)
                                    Text(s.text).textStyle(.codeBlock).foregroundStyle(c.onSurfaceVariant).lineLimit(2)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.vertical, 8)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(PressStyle())
                            .accessibilityLabel("Use \(s.name)")
                            if !s.id.hasPrefix("style-") {
                                IconAction(symbol: "trash", label: "Delete \(s.name)", size: 14) { studio.deleteSnippet(s.id) }
                            }
                        }
                        Hairline()
                    }
                    if builtIn.isEmpty && mine.isEmpty { MicroLabel("nothing saved yet").padding(.vertical, 8) }
                }
                SectionLabel("save")
                ConsoleTextField(text: $newName, placeholder: "name", kind: .prose)
                ConsoleTextField(text: $newText, placeholder: tab == PromptSnippet.companion.STYLE ? "{prompt}, film grain, 35mm" : "soft rim light", kind: .mono, minLines: 1, maxLines: 4)
                HStack(spacing: 8) {
                    GhostButton("From prompt") { newText = tab == PromptSnippet.companion.STYLE ? "{prompt}, " : prompt }
                    PrimaryButton("Save", enabled: !newName.trimmingCharacters(in: .whitespaces).isEmpty && !newText.trimmingCharacters(in: .whitespaces).isEmpty) {
                        studio.saveSnippet(PromptSnippet(id: StudioIds.shared.doNew(prefix: "s", random: KotlinRandom.companion), name: newName.trimmingCharacters(in: .whitespaces),
                                                         text: newText, kind: tab, negative: tab == PromptSnippet.companion.STYLE ? negative : ""))
                        newName = ""
                        newText = ""
                    }
                }
            }
            .padding(16)
        }
        .background(c.surfaceContainerLow)
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

/// Seed: random each time, locked, or walking from a base (`seed`, `seed+1`, …).
struct SeedControl: View {
    @Binding var mode: SeedMode
    @Binding var seed: Int64
    @Environment(\.scheme) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            MicroLabel("seed")
            Segmented(options: [SeedMode.random, .locked, .walk], selected: mode, label: { $0 == .random ? "random" : $0 == .locked ? "lock" : "walk" }) { mode = $0 }
            if mode != .random {
                HStack {
                    Text("\(seed)").textStyle(.readout).foregroundStyle(c.onSurface).frame(maxWidth: .infinity, alignment: .leading)
                    IconAction(.dice, label: "New seed") { seed = Int64.random(in: 0 ... 4_294_967_295) }
                }
            }
        }
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
    @Environment(\.scheme) private var c
    @State private var x: GridParam?
    @State private var y: GridParam?
    @State private var xText = ""
    @State private var yText = ""

    var body: some View {
        let xs = x.map { GridPlanner.shared.parseValues(text: xText, integer: $0.integer) } ?? []
        let ys = y.map { GridPlanner.shared.parseValues(text: yText, integer: $0.integer) } ?? []
        let cells = xs.count * max(ys.count, 1)
        ScrollView {
            InkStack(alignment: .leading, spacing: 12) {
                SectionLabel("x/y grid")
                MicroLabel("one job per cell · values like 10, 20, 30 · 5-9 · 1-2 (+0.25) · euler, dpm++ 2m", lines: 2)
                SelectField(label: "X axis", value: x?.label ?? "choose", options: params, optionLabel: { $0.label }) { x = $0; if xText.isEmpty { xText = $0.hint } }
                if let x { ConsoleTextField(text: $xText, placeholder: x.hint, label: "\(x.label) values", kind: .mono) }
                SelectField(label: "Y axis (optional)", value: y?.label ?? "none", options: [GridParam(name: "", label: "none", integer: false)] + params.filter { $0 != x },
                            optionLabel: { $0.label }) { p in
                    y = p.name.isEmpty ? nil : p
                    if let p = y, yText.isEmpty { yText = p.hint }
                }
                if let y { ConsoleTextField(text: $yText, placeholder: y.hint, label: "\(y.label) values", kind: .mono) }
                MicroLabel(cells > 64 ? "\(cells) cells · the first 64 run" : "\(cells) cell\(cells == 1 ? "" : "s")", color: cells > 64 ? c.error : c.onSurfaceVariant)
                PrimaryButton("Queue grid", enabled: x != nil && !xs.isEmpty && (y == nil || !ys.isEmpty)) {
                    guard let x else { return }
                    onRun(GridAxis(param: x.name, values: xs, label: x.label), y.map { GridAxis(param: $0.name, values: ys, label: $0.label) })
                }
            }
            .padding(16)
        }
        .background(c.surfaceContainerLow)
    }
}

/// A grid run's results, labelled on both axes; cells fill in as their jobs finish.
struct GridResultView: View {
    let run: GridRun
    var onOpen: (StudioAsset) -> Void = { _ in }
    @Environment(AppContainer.self) private var app
    @Environment(\.scheme) private var c

    var body: some View {
        let cols = max(run.columns.count, 1)
        VStack(alignment: .leading, spacing: 4) {
            MicroLabel("\(run.yLabel.map { "\($0) ↓ · " } ?? "")\(run.xLabel) →\(run.truncated ? " · truncated at 64" : "")")
            ScrollView(.horizontal, showsIndicators: false) {
                Grid(horizontalSpacing: 3, verticalSpacing: 3) {
                    GridRow {
                        if run.yLabel != nil { Color.clear.frame(width: 44, height: 1) }
                        ForEach(run.columns.indices, id: \.self) { i in
                            Text(run.columns[i]).textStyle(.micro).foregroundStyle(c.primary).lineLimit(1).frame(width: cellSide(cols))
                        }
                    }
                    ForEach(run.jobIds.indices, id: \.self) { r in
                        GridRow {
                            if run.yLabel != nil {
                                Text(run.rows.indices.contains(r) ? run.rows[r] : "").textStyle(.micro).foregroundStyle(c.primary).lineLimit(2).frame(width: 44)
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
    @Environment(\.scheme) private var c

    var body: some View {
        let job = app.studio.job(jobId)
        let asset = job?.outputs.first.flatMap { app.studio.library.asset($0) }
        ZStack {
            if let asset {
                AssetThumb(asset: asset).onTapGesture { onOpen(asset) }
            } else {
                c.surfaceContainer
                InkPlate(ink: job?.state == .running ? .flow(progress: Double(job?.progress ?? 0), loading: job?.steps == 0) : .idle,
                         dot: c.primary, idleDot: c.outlineVariant, plate: c.primary, cell: 4)
                if job?.state == .failed { Image(systemName: "exclamationmark").foregroundStyle(c.error) }
            }
        }
        .clipped()
        .accessibilityLabel(job?.label ?? "cell")
    }
}

// MARK: - Workflow parameters

/// One control for a workflow parameter, by its type (schema-rendered UI, spec §5).
struct WorkflowParamControl: View {
    let param: WorkflowParam
    @Binding var value: ParamValue?
    @Environment(AppContainer.self) private var app
    @Environment(\.scheme) private var c

    var body: some View {
        let current = value ?? param.default_
        switch param.type {
        case .int_, .float_:
            if let lo = param.min?.doubleValue, let hi = param.max?.doubleValue, hi > lo, hi - lo <= 100_000 {
                let isInt = param.type == .int_
                ValueSlider(label: param.label, value: Binding(get: { current?.asDouble()?.doubleValue ?? lo }, set: { value = isInt ? .int64(Int64($0.rounded())) : .double($0) }),
                            range: lo ... hi, step: param.step?.doubleValue ?? (isInt ? 1 : nil), format: { isInt ? "\(Int($0))" : String(format: "%.2f", $0) })
            } else {
                ConsoleTextField(text: Binding(get: { current?.display() ?? "" }, set: { t in
                    if param.type == .int_ { value = Int64(t).map(ParamValue.int64) } else { value = Double(t).map(ParamValue.double) }
                }), label: param.label, kind: .number)
            }
        case .seed:
            VStack(alignment: .leading, spacing: 4) {
                MicroLabel(param.label)
                HStack {
                    let v = current?.asLong()?.int64Value ?? -1
                    Text(v < 0 ? "random" : "\(v)").textStyle(.readout).foregroundStyle(c.onSurface).frame(maxWidth: .infinity, alignment: .leading)
                    IconAction(.dice, label: "New seed") { value = .int64(Int64.random(in: 0 ... 4_294_967_295)) }
                    TextAction("Random") { value = .int64(-1) }
                }
            }
        case .bool_:
            HStack {
                Text(param.label).textStyle(.titleSmall).foregroundStyle(c.onSurface).frame(maxWidth: .infinity, alignment: .leading)
                RisoToggle(isOn: Binding(get: { (current as? ParamValue.BoolValue)?.value ?? false }, set: { value = .flag($0) }))
            }
        case .enum_:
            SelectField(label: param.label, value: current?.display() ?? "", options: param.options) { value = .text($0) }
        case .image, .audio:
            AssetParamPicker(label: param.label, type: param.type == .image ? .image : .audio, role: param.role ?? param.name, value: $value)
        default:
            ConsoleTextField(text: Binding(get: { current?.display() ?? "" }, set: { value = .text($0) }), label: param.label, kind: .mono,
                             minLines: param.multiline ? 2 : 1, maxLines: param.multiline ? 8 : 1)
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
    @Environment(\.scheme) private var c

    var body: some View {
        let studio = app.studio
        let chosen = (value as? ParamValue.AssetValue).flatMap { studio.library.asset($0.ref.assetId) }
        let options = Array(studio.library.assets.filter { $0.type == type }.prefix(40))
        VStack(alignment: .leading, spacing: 4) {
            MicroLabel(label)
            HStack(spacing: 10) {
                if let chosen { AssetThumb(asset: chosen, size: 56) }
                Menu {
                    ForEach(options, id: \.id) { a in
                        Button(StudioText.describe(a)) { value = ParamValue.AssetValue(ref: AssetRef(assetId: a.id, role: role)) }
                    }
                    if chosen != nil { Button("None", role: .destructive) { value = nil } }
                } label: {
                    Text(options.isEmpty ? "no \(type == .image ? "images" : "clips") in the library" : chosen == nil ? "choose from the library" : "change")
                        .textStyle(.labelMedium).foregroundStyle(c.primary)
                }
                .disabled(options.isEmpty)
            }
        }
    }
}

/// The values a workflow's form shows, with "More" for the advanced ones.
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
        VStack(alignment: .leading, spacing: 12) {
            ForEach(basic, id: \.name) { p in control(p) }
            if !advanced.isEmpty {
                TextAction(more ? "Less" : "More · \(advanced.count)") { withAnimation(.inkSlide) { more.toggle() } }
                if more { ForEach(advanced, id: \.name) { p in control(p) } }
            }
        }
    }

    private func control(_ p: WorkflowParam) -> some View {
        WorkflowParamControl(param: p, value: Binding(get: { values[p.name] }, set: { values[p.name] = $0 }))
    }
}
