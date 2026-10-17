import AutobotShared
import PhotosUI
import SwiftUI

private let aspects: [(String, (Int, Int))] = [("1:1", (1, 1)), ("2:3", (2, 3)), ("3:2", (3, 2)), ("3:4", (3, 4)), ("4:3", (4, 3)), ("9:16", (9, 16)), ("16:9", (16, 9))]
private let bases = [512, 768, 1024]
private let defaultSamplers = ["DPM++ 2M", "DPM++ SDE", "Euler a", "Euler", "LCM", "DDIM", "UniPC"]

private func sizeLabel(_ w: Int, _ h: Int) -> String { w == h ? "\(w)²" : "\(w)×\(h)" }

private func aspectSize(_ base: Int, _ ratio: (Int, Int)) -> (Int, Int) {
    let area = Double(base) * Double(base)
    let w = (area * Double(ratio.0) / Double(ratio.1)).squareRoot()
    return (PromptSyntax.snap(Int(w)), PromptSyntax.snap(Int(area / w)))
}

/// The image studio (mockup 04): paper surface, big prompt, model chips, readouts, 2×2 proofs.
struct ImagineScreen: View {
    var handoffId: String? = nil
    var asInit = false
    var modelId: String? = nil
    var prompt: String? = nil
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c
    @State private var model: ImagineModel?
    @State private var showTune = false
    @State private var showMask = false
    @State private var selected = -1

    var body: some View {
        let _ = InkProbe.hit("body.imagine")
        Group {
            if let model {
                content(model)
            } else {
                c.background
            }
        }
        .background(c.background.ignoresSafeArea())
        // Dark status bar icons on paper while the studio is shown (Android's PaperTheme).
        .preferredColorScheme(.light)
        .task {
            if model == nil {
                let m = ImagineModel(app: app)
                model = m
                m.onAppear()
                if let modelId { m.useModel(modelId) }
                if let prompt { m.setPrompt(prompt) }
                if let handoffId {
                    m.reuseParams(handoffId)
                    if asInit { await m.useAsInit(handoffId) }
                }
            }
        }
    }

    private func content(_ m: ImagineModel) -> some View {
        let request = m.request
        return VStack(spacing: 0) {
            AutobotTopBar(title: "Image", onBack: { nav.back() }) {
                if let route = m.route { RouteTag(route: route) }
                IconAction(.image, label: "Gallery") { nav.push(.gallery()) }
                TextAction("Studio", color: c.primary) { nav.enterStudio(.image) }
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if let error = m.error { ErrorLine(message: error, onDismiss: m.dismissError) }
                    if m.backends.isEmpty {
                        // One short sentence, one action; a PC is the secondary path.
                        Text("No image model yet.")
                            .textStyle(.bodyMedium)
                            .foregroundStyle(c.onSurfaceVariant)
                        PrimaryButton("Get a model") { nav.switchTo(.models) }
                        TextAction("Use a PC instead", color: c.primary) { nav.push(.imageBackends) }
                    }
                    PromptField(text: Binding(get: { m.request.prompt }, set: { m.setPrompt($0) }), tokens: m.tokenEstimate, wildcards: m.wildcards)
                    if !m.isRunning && !m.request.prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { styleRow(m) }
                    if !m.chips.isEmpty { chipRow(m) }
                    if m.catalogStatus == .error, let error = m.catalogError {
                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                            Text("\(m.backend?.name ?? "Backend") unreachable · \(error)")
                                .textStyle(.bodySmall)
                                .foregroundStyle(c.error)
                                .fixedSize(horizontal: false, vertical: true)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            TextAction("Retry", color: c.primary) { m.refreshCatalog() }
                        }
                    }
                    Hairline(color: c.onBackground)
                    HStack {
                        BigReadout(label: "Steps", value: "\(request.steps)") { showTune = true }
                        Spacer()
                        BigReadout(label: "Seed", value: request.seed < 0 ? "rnd" : String(String(request.seed).suffix(6))) { showTune = true }
                        Spacer()
                        BigReadout(label: "Size", value: sizeLabel(request.width, request.height)) { showTune = true }
                        Spacer()
                        BigReadout(label: "Batch", value: "\(request.batchCount)") { showTune = true }
                    }
                    Hairline(color: c.onBackground)
                    if request.mode != .txt2img { InitStrip(model: m) { showMask = true } }
                    ProofGrid(model: m, selected: $selected)
                    if !m.isRunning, let result = m.results[imagineSafe: max(selected, 0)] {
                        HStack(spacing: 6) {
                            MicroLabel("seed \(result.seed) · \(result.width)×\(result.height) · \(String(format: "%.1f", Double(result.durationMs) / 1000)) s")
                                .frame(maxWidth: .infinity, alignment: .leading)
                            Tag("↺ seed", accent: c.primary) { m.setSeed(result.seed) }
                            Tag("→ img2img") { Task { await m.useAsInit(result.galleryId) } }
                        }
                    }
                    Spacer().frame(height: 8)
                }
                .padding(.horizontal, Tokens.Spacing.gutter)
            }
            .scrollDismissesKeyboard(.interactively)
            // No paper ground here: the prompt and the readouts fill the screen, and dots must not sit behind text.
            StudioBar(model: m) { showTune = true }
        }
        // One sync when a job starts or ends or a result arrives.
        .task(id: [m.isRunning ? 1 : 0, app.generator.lastResults.count]) { await m.syncResults() }
        .sheet(isPresented: $showTune) {
            TuneSheet(model: m) {
                showTune = false
                nav.push(.imageBackends)
            }
            .paperTheme()
            .presentationDetents([.large])
            .presentationBackground(AutobotScheme.light.surfaceContainerLow)
        }
        .fullScreenCover(isPresented: $showMask) {
            if let image = m.initImage {
                MaskEditor(image: image, model: m) { showMask = false }
            }
        }
    }

    /// Built-in styles: one tap wraps the prompt (and adds the style's negatives).
    private func styleRow(_ m: ImagineModel) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                MicroLabel("style")
                ForEach(ImagineModel.styles, id: \.id) { style in
                    Tag(style.name) { m.applyStyle(style) }
                        .accessibilityLabel("Apply the \(style.name) style")
                }
            }
        }
    }

    private func chipRow(_ m: ImagineModel) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                let current = m.selectedChip
                ForEach(m.chips, id: \.self) { chip in
                    let active = chip == current
                    Button { m.selectChip(chip) } label: {
                        Text(chip.label.uppercased())
                            .textStyle(.micro)
                            .lineLimit(1)
                            .foregroundStyle(active ? c.background : c.onBackground)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 5)
                            .background(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall).fill(active ? c.onBackground : c.background))
                            .overlay(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall).strokeBorder(c.onBackground, lineWidth: 1))
                    }
                    .buttonStyle(PressStyle())
                }
                Button { nav.switchTo(.models) } label: {
                    Text("+ MODEL").textStyle(.micro).foregroundStyle(c.primary).padding(.horizontal, 6).padding(.vertical, 5)
                }
                .buttonStyle(PressStyle())
            }
        }
    }
}

private extension Array {
    subscript(imagineSafe index: Int) -> Element? { indices.contains(index) ? self[index] : nil }
}

/// The prompt, set in the display face with a red caret (mockup 04).
private struct PromptField: View {
    @Binding var text: String
    let tokens: Int
    var wildcards: String? = nil
    @Environment(\.scheme) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            TextField(text: $text, prompt: Text("Describe an image…").foregroundColor(c.outline), axis: .vertical) { Text("Prompt") }
                .textStyle(.headlineMedium)
                .foregroundStyle(c.onBackground)
                .tint(c.primary)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .lineLimit(1 ... 12)
                .frame(minHeight: 96, alignment: .topLeading)
            if let wildcards {
                MicroLabel(wildcards, color: c.primary).accessibilityLabel(wildcards.replacingOccurrences(of: "·", with: ","))
            } else if tokens > 60 {
                // Only worth saying near the limit.
                MicroLabel("~\(tokens) / 75 tokens", color: tokens > 75 ? AutobotColors.redDeep : c.onSurfaceVariant)
            }
        }
    }
}

private struct ErrorLine: View {
    let message: String
    let onDismiss: () -> Void
    @Environment(\.scheme) private var c

    var body: some View {
        HStack {
            Text(message).textStyle(.bodySmall).foregroundStyle(c.onPrimary).frame(maxWidth: .infinity, alignment: .leading)
            IconAction(symbol: "xmark", label: "Dismiss", tint: c.onPrimary, size: 14, action: onDismiss)
        }
        .padding(.leading, 12)
        .background(c.primary)
    }
}

/// 2×2 proofs: the ink plate flows while sampling, develops into previews and prints each result.
private struct ProofGrid: View {
    let model: ImagineModel
    @Binding var selected: Int
    @Environment(\.scheme) private var c
    /// The latest latent preview (its luma), for the image being sampled.
    @State private var preview: (image: Int, ink: PlateImage)?
    @State private var previewSerial = 0
    /// Slots of the last run, kept until its results are loaded (so the last proof can print).
    @State private var pendingSlots = 0

    private typealias Running = (image: Int, step: Int, total: Int, batch: Int, preview: Data?)

    var body: some View {
        let running: Running? = {
            if case .running(_, _, let r, let image, let step, let total, let preview, _) = model.running {
                return (image, step, total, r.batchCount, preview)
            }
            return nil
        }()
        let count = running.map { max($0.batch, 1) } ?? max(model.results.count, pendingSlots)
        let slots = min(max(count, 1), 4)
        let ratio = min(max(CGFloat(model.request.width) / CGFloat(max(model.request.height, 1)), 0.5), 2)
        let cols = slots == 1 ? 1 : 2
        VStack(spacing: 6) {
            ForEach(0 ..< (slots + cols - 1) / cols, id: \.self) { row in
                HStack(spacing: 6) {
                    ForEach(0 ..< cols, id: \.self) { col in
                        let i = row * cols + col
                        if i >= slots {
                            Color.clear.frame(maxWidth: .infinity)
                        } else {
                            cell(i, running: running, ratio: ratio)
                        }
                    }
                }
            }
        }
        .task(id: running?.preview) {
            guard let running, let data = running.preview else { return }
            guard let image = await decodeImage(data, maxSide: 768), !Task.isCancelled, let cg = image.cgImage, let grid = LumaGrid(image: cg) else { return }
            previewSerial += 1
            preview = (running.image, PlateImage(id: "\(previewSerial)", grid: grid))
        }
        .onChange(of: running?.batch) { _, batch in
            if let batch { pendingSlots = batch }
        }
        .onChange(of: model.running.isRunning) { _, isRunning in
            if isRunning { preview = nil }
        }
        .task(id: model.results.count) {
            // Results caught up, or the run ended without them (cancelled, failed).
            if model.results.count >= pendingSlots { pendingSlots = 0; return }
            guard !model.isRunning else { return }
            try? await Task.sleep(for: .seconds(4))
            if !Task.isCancelled && !model.isRunning { pendingSlots = 0 }
        }
    }

    private func ink(_ i: Int, running: Running?, result: ResultUi?) -> PlateInk? {
        if let result, let grid = LumaCache.grid(result.image) {
            return .final(PlateImage(id: result.galleryId, grid: grid, image: result.image))
        }
        guard let running else {
            return i < pendingSlots ? .hold : nil
        }
        if i == running.image {
            if running.preview != nil, let preview, preview.image == i { return .preview(preview.ink) }
            let loading = model.phase != nil || running.total == 0
            return .flow(progress: running.total > 0 ? Double(running.step) / Double(running.total) : 0, loading: loading)
        }
        return i < running.image ? .hold : .idle
    }

    private func cell(_ i: Int, running: Running?, ratio: CGFloat) -> some View {
        let result = model.results[imagineSafe: i]
        let plateInk = ink(i, running: running, result: result)
        return ZStack(alignment: .bottomLeading) {
            c.surfaceContainerLow
            if let plateInk {
                InkPlate(ink: plateInk, dot: c.primary, idleDot: c.outlineVariant, plate: c.primary, phase: Double(i) * 0.37)
            } else if model.preferInit, i == 0, let initImage = model.initImage {
                Image(uiImage: initImage).resizable().scaledToFill()
                if model.request.mode == .inpaint { MaskOverlay(mask: model.mask) }
            } else {
                InkPlate(ink: .idle, dot: c.primary, idleDot: c.outlineVariant, plate: c.primary, phase: Double(i) * 0.37)
            }
            if let running, i == running.image {
                let loading = model.phase != nil || running.total == 0
                Tag(loading ? "loading" : "step \(running.step)/\(running.total)", accent: c.primary, filled: true, busy: loading).padding(6)
            }
        }
        .frame(maxWidth: .infinity)
        .aspectRatio(ratio, contentMode: .fit)
        .clipped()
        .overlay { if i == selected && result != nil { Rectangle().strokeBorder(c.primary, lineWidth: Tokens.Stroke.selection) } }
        .contentShape(Rectangle())
        .onTapGesture { if result != nil { selected = i } }
        .accessibilityLabel(result != nil ? "Result \(i + 1)" : "Proof \(i + 1)")
    }
}

private struct StudioBar: View {
    let model: ImagineModel
    let onShowTune: () -> Void
    @Environment(\.scheme) private var c

    var body: some View {
        VStack(spacing: 8) {
            HStack {
                Group {
                    // The elapsed seconds tick only while a job runs (whole seconds: once a second).
                    if model.isRunning {
                        TimelineView(.periodic(from: .now, by: 1)) { context in
                            Text(status(now: context.date)).textStyle(.bodyMedium).foregroundStyle(c.onBackground).lineLimit(2)
                        }
                    } else {
                        Text(status(now: Date())).textStyle(.bodyMedium).foregroundStyle(c.onBackground).lineLimit(2)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if case .running(_, _, let r, _, _, _, _, _) = model.running {
                    Text("\(model.results.count)/\(r.batchCount)").textStyle(.readout).foregroundStyle(c.onBackground)
                } else {
                    TextAction("Tune", color: c.primary, style: .labelLarge, action: onShowTune)
                }
            }
            InkProgressBar(barState, height: 2)
            HStack(spacing: 8) {
                if case .running(let source, _, _, _, _, _, _, _) = model.running {
                    GhostButton("Cancel", accent: c.primary, enabled: source != .agent, action: model.cancel)
                } else if !model.results.isEmpty {
                    GhostButton("Variations", enabled: model.canGenerate, action: model.variations)
                    PrimaryButton("Run again", enabled: model.canGenerate, action: model.runAgain)
                } else {
                    PrimaryButton("Generate", enabled: model.canGenerate, action: model.generate)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(c.background)
    }

    /// The model loading (no steps yet) is indeterminate; sampling fills the bar.
    private var barState: InkBarState {
        guard case .running(_, _, _, _, _, let total, _, _) = model.running else { return .progress(0, running: false) }
        return model.phase != nil || total == 0 ? .indeterminate : .progress(Double(progress), running: true)
    }

    private var progress: CGFloat {
        guard case .running(_, _, let r, let image, let step, let total, _, _) = model.running, total > 0 else { return 0 }
        return CGFloat((Double(image) + Double(step) / Double(total)) / Double(max(r.batchCount, 1)))
    }

    private func status(now: Date) -> String {
        let r = model.request
        if case .running(let source, let backend, _, _, _, _, _, let started) = model.running {
            let seconds = Int(now.timeIntervalSince(started))
            if let phase = model.phase { return "\(phase) · \(seconds) s" }
            if source == .agent { return "Agent is generating · \(seconds) s" }
            return "Running \(model.backend?.kind == .onDevice ? "on device" : "on \(backend)") · \(seconds) s"
        }
        if !model.results.isEmpty { return "Done · \(model.results.count) image\(model.results.count == 1 ? "" : "s")" }
        if let blocker = model.generateBlocker, !blocker.isEmpty { return blocker }
        return "\(r.mode.label) · \(r.sampler.isEmpty ? "default sampler" : r.sampler) · cfg \(String(format: "%.1f", r.cfgScale))"
    }
}

private struct InitStrip: View {
    let model: ImagineModel
    let onEditMask: () -> Void
    @Environment(\.scheme) private var c
    @State private var picked: PhotosPickerItem?

    var body: some View {
        HStack(spacing: 10) {
            PhotosPicker(selection: $picked, matching: .images, photoLibrary: .shared()) {
                ZStack {
                    c.surfaceContainerLow
                    if let image = model.initImage {
                        Image(uiImage: image).resizable().scaledToFill()
                        if model.request.mode == .inpaint { MaskOverlay(mask: model.mask) }
                    } else {
                        AutobotIconView(icon: .image, color: c.onSurfaceVariant)
                    }
                }
                .frame(width: 72, height: 72)
                .clipped()
            }
            .accessibilityLabel("Pick an image")
            VStack(alignment: .leading, spacing: 4) {
                MicroLabel(model.request.mode == .inpaint ? "inpaint · \(model.mask.isEmpty ? "no mask" : counted(model.mask.strokes.count, "stroke"))" : "img2img")
                ValueSlider(label: "Denoise", value: Binding(get: { model.request.denoiseStrength }, set: { model.setDenoise($0) }), range: 0.05 ... 1)
            }
            if model.request.mode == .inpaint && model.initImage != nil {
                IconAction(.brush, label: "Edit mask", action: onEditMask)
            }
        }
        .onChange(of: picked) { _, item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self) { await model.setInitImage(data) } else { model.photoLoadFailed() }
                picked = nil
            }
        }
    }
}

/// Every parameter, out of the way: mode, negative, sampler, cfg, size, seed, batch, LoRA.
private struct TuneSheet: View {
    let model: ImagineModel
    let onOpenBackends: () -> Void
    @Environment(\.scheme) private var c
    @State private var base = 512
    @State private var showLoras = false

    var body: some View {
        let request = model.request
        let caps = model.capabilities
        let kind = model.backend?.kind
        ScrollView {
            InkStack(alignment: .leading, spacing: 14) {
                HStack {
                    SectionLabel("tune")
                    Tag("backends", action: onOpenBackends)
                }
                let modes = DiffusionMode.allCases.filter { caps?.modes.contains($0) ?? true }
                Segmented(options: modes, selected: request.mode, label: { $0.label }, onSelect: model.setMode)

                ConsoleTextField(text: Binding(get: { model.request.negativePrompt }, set: { model.setNegative($0) }), label: "Negative", kind: .mono, minLines: 2, maxLines: 5)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        MicroLabel("presets")
                        ForEach(ImagineModel.negativePresets, id: \.id) { preset in
                            Tag(preset.name) { model.addNegative(preset) }
                                .accessibilityLabel("Add the \(preset.name) negative preset")
                        }
                    }
                }

                let (samplers, value): ([String], String) = switch kind {
                case .onDevice: (LocalDiffusion.samplers, request.sampler)
                case .localSse: (LocalSseEngine.schedulers, LocalSseEngine.schedulerId(request.sampler, request.scheduler))
                default: (model.catalog.samplers.isEmpty ? defaultSamplers : model.catalog.samplers, request.sampler)
                }
                HStack(alignment: .top, spacing: 8) {
                    SelectField(label: "Sampler", value: value, options: samplers, onSelect: { model.setSampler($0, scheduler: kind == .sdApi || kind == .onDevice ? request.scheduler : nil) })
                    let schedulers: [String] = switch kind {
                    case .onDevice: LocalDiffusion.schedulers
                    case .sdApi: model.catalog.schedulers
                    default: []
                    }
                    if !schedulers.isEmpty {
                        SelectField(label: "Schedule", value: request.scheduler ?? "default", options: schedulers, onSelect: { model.setScheduler($0) })
                    }
                }
                if kind == .onDevice {
                    MicroLabel("core ml offers dpm++ 2m (karras) and pndm; other samplers fall back · inpaint = img2img + feathered mask", lines: 3)
                }
                ValueSlider(label: "Steps", value: Binding(get: { Double(model.request.steps) }, set: { model.setSteps(Int($0)) }),
                            range: 1 ... Double(caps?.maxSteps ?? 150), step: 1, format: { "\(Int($0))" })
                ValueSlider(label: "CFG", value: Binding(get: { model.request.cfgScale }, set: { model.setCfg($0) }), range: 1 ... 20, format: { String(format: "%.1f", $0) })

                if kind == .onDevice {
                    MicroLabel("size · \(request.width)×\(request.height) · fixed by the core ml model", lines: 2)
                } else {
                    MicroLabel("size · \(request.width)×\(request.height)")
                    Segmented(options: bases, selected: base, label: { "\($0)" }, onSelect: { base = $0 })
                    ImagineTagFlow(spacing: 6) {
                        ForEach(aspects, id: \.0) { label, ratio in
                            let (w, h) = aspectSize(base, ratio)
                            let active = w == request.width && h == request.height
                            Tag(label, accent: active ? c.primary : c.onSurfaceVariant, filled: active) { model.setSize(w, h) }
                        }
                    }
                }

                HStack(alignment: .bottom, spacing: 16) {
                    VStack(alignment: .leading, spacing: 4) {
                        MicroLabel("seed")
                        HStack {
                            Text(request.seed < 0 ? "random" : "\(request.seed)").textStyle(.readout).foregroundStyle(c.onSurface)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            IconAction(.dice, label: "Fixed random seed", action: model.randomizeSeed)
                            if request.seed >= 0 { TextAction("Random", action: model.setRandomSeed) }
                        }
                    }
                    StepperField(label: "Batch", value: request.batchCount, range: 1 ... ImagineModel.maxBatch, onChange: model.setBatch)
                }

                if request.mode == .inpaint && caps?.maskEncoding == .opaqueBlackWhite && kind == .sdApi {
                    ValueSlider(label: "Mask blur", value: Binding(get: { Double(model.request.maskBlur) }, set: { model.setMaskBlur(Int($0)) }),
                                range: 0 ... 64, step: 1, format: { "\(Int($0)) px" })
                    HStack {
                        Text("Only masked area").textStyle(.titleSmall).foregroundStyle(c.onSurface).frame(maxWidth: .infinity, alignment: .leading)
                        RisoToggle(isOn: Binding(get: { model.request.inpaintOnlyMasked }, set: { model.setOnlyMasked($0) }))
                    }
                }

                HStack {
                    MicroLabel("lora").frame(maxWidth: .infinity, alignment: .leading)
                    if caps?.runtimeLora == true { Tag("+ add", accent: c.primary) { showLoras = true } }
                }
                if caps?.runtimeLora == false { MicroLabel("this backend runs a fixed model; merge loras before conversion", lines: 2) }
                ForEach(request.loras, id: \.name) { lora in
                    HStack {
                        ValueSlider(label: lora.name, value: Binding(get: { lora.weight }, set: { model.setLoraWeight(lora.name, $0) }), range: -1 ... 2)
                        IconAction(symbol: "xmark", label: "Remove \(lora.name)", size: 14) { model.removeLora(lora.name) }
                    }
                }
                if request.cfgScale <= 1 { MicroLabel("cfg 1 skips the unconditional pass — right for turbo / lcm / distilled models", lines: 2) }
            }
            .padding(.horizontal, 16)
            .padding(.top, 20)
            .padding(.bottom, 32)
        }
        .background(c.surfaceContainerLow)
        .sheet(isPresented: $showLoras) {
            LoraSheet(available: model.catalog.loras, selected: Set(request.loras.map(\.name))) {
                model.addLora($0)
                showLoras = false
            }
            .paperTheme()
            .presentationDetents([.medium, .large])
        }
    }
}

private struct LoraSheet: View {
    let available: [String]
    let selected: Set<String>
    let onPick: (String) -> Void
    @State private var query = ""
    @Environment(\.scheme) private var c

    var body: some View {
        let filtered = available.filter { query.isEmpty || $0.localizedCaseInsensitiveContains(query) }
        InkStack(alignment: .leading, spacing: 8) {
            SectionLabel("lora library · \(available.count)")
            ConsoleTextField(text: $query, placeholder: "filter", kind: .mono)
            if filtered.isEmpty { MicroLabel(available.isEmpty ? "no loras — add them to your pc backend" : "no match") }
            ScrollView {
                VStack(spacing: 0) {
                    ForEach(filtered, id: \.self) { name in
                        Button { onPick(name) } label: {
                            HStack(spacing: 10) {
                                AutobotIconView(icon: .layers, size: 16, color: c.onSurfaceVariant)
                                Text(name).textStyle(.code).foregroundStyle(c.onSurface).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
                                if selected.contains(name) { MicroLabel("added", color: c.primary) }
                            }
                            .padding(.vertical, 10)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(PressStyle())
                        .disabled(selected.contains(name))
                    }
                }
            }
        }
        .padding(16)
        .background(c.surfaceContainerLow)
    }
}

