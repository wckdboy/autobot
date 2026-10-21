import AutobotShared
import PhotosUI
import SwiftUI

private let defaultSamplers = ["DPM++ 2M", "DPM++ SDE", "Euler a", "Euler", "LCM", "DDIM", "UniPC"]

private func sizeLabel(_ w: Int, _ h: Int) -> String { w == h ? "\(w)²" : "\(w)×\(h)" }

/// The image screen: the prompt, model chips, the run's settings at a glance and up to 2×2 results.
struct ImagineScreen: View {
    var handoffId: String? = nil
    var intent: ImageHandoff = .reuse
    var modelId: String? = nil
    var prompt: String? = nil
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @State private var model: ImagineModel?
    @State private var showTune = false
    @State private var showMask = false
    @State private var selected = -1
    @State private var viewer: ImageViewerTarget?

    var body: some View {
        let _ = InkProbe.hit("body.imagine")
        Group {
            if let model {
                content(model)
            } else {
                Color(.systemBackground)
            }
        }
        .background(Color(.systemBackground).ignoresSafeArea())
        .task {
            if model == nil {
                let m = ImagineModel(app: app)
                model = m
                m.onAppear()
                if let modelId { m.useModel(modelId) }
                if let prompt { m.setPrompt(prompt) }
                if let handoffId {
                    await m.handoff(handoffId, intent)
                    if intent == .inpaint, m.initImage != nil { showMask = true }
                }
            }
        }
    }

    private func content(_ m: ImagineModel) -> some View {
        let request = m.request
        return VStack(spacing: 0) {
            AutobotTopBar(title: "Image", onBack: { nav.back() }) {
                if let route = m.route { RouteBadge(route: route) }
                Button { nav.push(.gallery()) } label: { Image(systemName: "photo.on.rectangle") }
                    .accessibilityLabel("Gallery")
                Button("Studio") { nav.enterStudio(.image) }
            }
            ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if let error = m.error { NoticeBanner(message: error, onDismiss: m.dismissError) }
                else if let notice = m.notice { NoticeBanner(message: notice, systemImage: "info.circle", onDismiss: m.dismissError) }
                if m.backends.isEmpty {
                    // One short sentence, one action; a PC is the secondary path.
                    VStack(spacing: 10) {
                        Text("No image model yet.")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        Button { nav.switchTo(.models) } label: {
                            Text("Get a model").frame(maxWidth: .infinity)
                        }
                        .glassProminentButton()
                        .controlSize(.large)
                        Button("Use a PC instead") { nav.push(.imageBackends) }
                            .buttonStyle(.borderless)
                    }
                    .frame(maxWidth: .infinity)
                }
                PromptField(text: Binding(get: { m.request.prompt }, set: { m.setPrompt($0) }), tokens: m.tokenEstimate, wildcards: m.wildcards)
                // The row keeps its place while hidden (no prompt yet, a job running), so typing the
                // first letter or starting a job does not push the results down and back up.
                let styles = !m.isRunning && !m.request.prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                styleRow(m)
                    .opacity(styles ? 1 : 0)
                    .allowsHitTesting(styles)
                    .accessibilityHidden(!styles)
                    .animation(.easeOut(duration: 0.2), value: styles)
                if !m.chips.isEmpty { chipRow(m) }
                if m.catalogStatus == .error, let error = m.catalogError {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Label("\(m.backend?.name ?? "Backend") unreachable · \(error)", systemImage: "wifi.exclamationmark")
                            .font(.footnote)
                            .foregroundStyle(.red)
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        Button("Retry") { m.refreshCatalog() }.buttonStyle(.borderless)
                    }
                }
                HStack(spacing: 0) {
                    SettingReadout(label: "Steps", value: "\(request.steps)") { showTune = true }
                    Divider().frame(height: 32)
                    SettingReadout(label: "Seed", value: request.seed < 0 ? "rnd" : String(String(request.seed).suffix(6))) { showTune = true }
                    Divider().frame(height: 32)
                    SettingReadout(label: "Size", value: sizeLabel(m.outputSize.width, m.outputSize.height)) { showTune = true }
                    Divider().frame(height: 32)
                    SettingReadout(label: "Batch", value: "\(request.batchCount)") { showTune = true }
                }
                .padding(.vertical, 8)
                .background(.fill.quaternary, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                if request.mode != .txt2img { InitStrip(model: m) { showMask = true } }
                ProofGrid(model: m, selected: $selected) { id in
                    viewer = ImageViewerTarget(ids: m.results.map(\.galleryId), start: id)
                }
                if !m.isRunning, let result = m.results[imagineSafe: max(selected, 0)] {
                    ImagineTagFlow(spacing: 6) {
                        Text("seed \(result.seed) · \(result.width)×\(result.height) · \(String(format: "%.1f", Double(result.durationMs) / 1000)) s")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                            .padding(.vertical, 5)
                        chip("Same seed", systemImage: "arrow.counterclockwise") { m.setSeed(result.seed) }
                        chip("Img2img", systemImage: "photo.on.rectangle.angled") { Task { await m.useAsInit(result.galleryId) } }
                        chip("View", systemImage: "arrow.up.left.and.arrow.down.right") { viewer = ImageViewerTarget(ids: m.results.map(\.galleryId), start: result.galleryId) }
                            .accessibilityLabel("View full screen")
                    }
                    if UnsavedImages.isUnsaved(result.galleryId) {
                        HStack(spacing: 8) {
                            if app.gallery.keptIncognito[result.galleryId] != nil {
                                Label(PanicCopy.shared.SAVED.sentenceCased, systemImage: "checkmark.circle")
                                    .font(.caption).foregroundStyle(.secondary)
                            } else {
                                Label(PanicCopy.shared.NOT_SAVED.sentenceCased, systemImage: "eye.slash")
                                    .font(.caption).foregroundStyle(.secondary)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                chip(PanicCopy.shared.SAVE.sentenceCased, systemImage: "tray.and.arrow.down", active: true) { m.saveResult(result.galleryId) }
                            }
                        }
                    }
                }
            }
            .padding(.horizontal)
            .padding(.top, 8)
            .padding(.bottom, 16)
            .readableWidth()
        }
            .scrollDismissesKeyboard(.interactively)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                GenerateBar(model: m) { showTune = true }
            }
        }
        // One sync when a job starts or ends or a result arrives.
        .task(id: [m.isRunning ? 1 : 0, app.generator.lastResults.count]) { await m.syncResults() }
        .sheet(isPresented: $showTune) {
            TuneSheet(model: m, onDone: { showTune = false }) {
                showTune = false
                nav.push(.imageBackends)
            }
            .presentationDetents([.large])
        }
        .imageViewer($viewer) { id, intent in
            Task {
                await m.handoff(id, intent)
                if intent == .inpaint, m.initImage != nil { showMask = true }
            }
        }
        .fullScreenCover(isPresented: $showMask) {
            if let image = m.initImage {
                MaskEditor(image: image, model: m) { showMask = false }
            }
        }
    }

    private func chip(_ title: String, systemImage: String? = nil, active: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Group {
                if let systemImage { Label(title, systemImage: systemImage) } else { Text(title) }
            }
            .chipLook(active: active)
        }
        .buttonStyle(.plain)
    }

    /// Built-in styles: one tap wraps the prompt (and adds the style's negatives).
    private func styleRow(_ m: ImagineModel) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                Text("Style").font(.caption).foregroundStyle(.secondary)
                ForEach(ImagineModel.styles, id: \.id) { style in
                    chip(style.name) { m.applyStyle(style) }
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
                    self.chip(chip.label, active: active) { m.selectChip(chip) }
                        .accessibilityAddTraits(active ? .isSelected : [])
                }
                self.chip("Model", systemImage: "plus") { nav.switchTo(.models) }
                    .accessibilityLabel("Get a model")
            }
        }
    }
}

private extension Array {
    subscript(imagineSafe index: Int) -> Element? { indices.contains(index) ? self[index] : nil }
}

/// One setting at a glance (`Steps` over `20`); a tap opens Tune.
private struct SettingReadout: View {
    let label: String
    let value: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 2) {
                Text(label).font(.caption).foregroundStyle(.secondary)
                Text(value).font(.title3.monospacedDigit().weight(.semibold)).foregroundStyle(.primary).lineLimit(1).minimumScaleFactor(0.7)
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityHint("Opens Tune")
    }
}

/// The prompt: a large multi-line field.
private struct PromptField: View {
    @Binding var text: String
    let tokens: Int
    var wildcards: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            TextField("Prompt", text: $text, prompt: Text("Describe an image…"), axis: .vertical)
                .font(.title3)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .lineLimit(3 ... 12)
                .padding(12)
                .background(.fill.quaternary, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            if let wildcards {
                Text(wildcards.sentenceCased)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .accessibilityLabel(wildcards.replacingOccurrences(of: "·", with: ","))
            } else if tokens > 60 {
                // Only worth saying near the limit.
                Text("~\(tokens) / 75 tokens")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(tokens > 75 ? AnyShapeStyle(.red) : AnyShapeStyle(.secondary))
            }
        }
    }
}

/// Up to 2×2 results: the latent preview while sampling, a spinner while waiting, then each image.
private struct ProofGrid: View {
    let model: ImagineModel
    @Binding var selected: Int
    /// Opens a result full screen.
    var onOpen: (String) -> Void = { _ in }
    /// The latest latent preview, for the image being sampled.
    @State private var preview: (image: Int, ui: UIImage)?
    /// Slots of the last run, kept until its results are loaded.
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
        // The shape of what comes out (results keep their own; a fixed-size model's cut-out otherwise).
        let shape = model.results.first.map { ($0.width, $0.height) } ?? model.outputSize
        let ratio = min(max(CGFloat(shape.0) / CGFloat(max(shape.1, 1)), 0.5), 2)
        let cols = slots == 1 ? 1 : 2
        Grid(horizontalSpacing: 8, verticalSpacing: 8) {
            ForEach(0 ..< (slots + cols - 1) / cols, id: \.self) { row in
                GridRow {
                    ForEach(0 ..< cols, id: \.self) { col in
                        let i = row * cols + col
                        if i >= slots {
                            Color.clear.gridCellUnsizedAxes([.horizontal, .vertical])
                        } else {
                            cell(i, running: running, ratio: ratio)
                        }
                    }
                }
            }
        }
        .animation(.easeOut(duration: 0.25), value: model.results.map(\.galleryId))
        .task(id: running?.preview) {
            guard let running, let data = running.preview else { return }
            guard let image = await decodeImage(data, maxSide: 768), !Task.isCancelled else { return }
            preview = (running.image, image)
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

    private func cell(_ i: Int, running: Running?, ratio: CGFloat) -> some View {
        let result = model.results[imagineSafe: i]
        let shape = RoundedRectangle(cornerRadius: 14, style: .continuous)
        let current = running.map { i == $0.image } ?? false
        let waiting = result == nil && (running.map { i <= $0.image } ?? (i < pendingSlots))
        return ZStack {
            shape.fill(.fill.tertiary)
            if let result {
                Image(uiImage: result.image).resizable().scaledToFill()
            } else if running == nil, pendingSlots == 0, model.preferInit, i == 0, let initImage = model.initImage {
                Image(uiImage: initImage).resizable().scaledToFill()
                if model.request.mode == .inpaint { MaskOverlay(mask: model.mask) }
            } else if current, let preview, preview.image == i {
                Image(uiImage: preview.ui).resizable().scaledToFill()
            } else if waiting {
                ProgressView()
            } else if running == nil, model.results.isEmpty, i == 0 {
                VStack(spacing: 10) {
                    AutobotMark(size: 18, color: BrandColors.purple.opacity(0.6))
                    Text("Describe an image, then generate.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .padding()
                .accessibilityElement(children: .combine)
            }
            if let running, current {
                let loading = model.phase != nil || running.total == 0
                VStack {
                    Spacer()
                    HStack {
                        CapsuleTag(loading ? "loading" : "step \(running.step)/\(running.total)")
                            .background(.regularMaterial, in: Capsule())
                        Spacer()
                    }
                }
                .padding(8)
            }
        }
        .frame(maxWidth: .infinity)
        .aspectRatio(ratio, contentMode: .fit)
        .clipShape(shape)
        .overlay { if i == selected && result != nil { shape.strokeBorder(.tint, lineWidth: 3) } }
        .contentShape(shape)
        .onTapGesture {
            guard let result else { return }
            selected = i
            onOpen(result.galleryId)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(result != nil ? "Result \(i + 1)" : "Proof \(i + 1)")
        .accessibilityAddTraits(result != nil ? .isButton : [])
        .accessibilityHint(result != nil ? "Opens the image full screen" : "")
    }
}

/// The run's status, its progress and the main actions, on glass at the bottom.
private struct GenerateBar: View {
    let model: ImagineModel
    let onShowTune: () -> Void

    var body: some View {
        VStack(spacing: 10) {
            HStack {
                Group {
                    // The elapsed seconds tick only while a job runs (whole seconds: once a second).
                    if model.isRunning {
                        TimelineView(.periodic(from: .now, by: 1)) { context in
                            Text(status(now: context.date)).lineLimit(2)
                        }
                    } else {
                        Text(status(now: Date())).lineLimit(2)
                    }
                }
                .font(.subheadline)
                .frame(maxWidth: .infinity, alignment: .leading)
                if case .running(_, _, let r, _, _, _, _, _) = model.running {
                    Text("\(model.results.count)/\(r.batchCount)").font(.subheadline.monospacedDigit()).foregroundStyle(.secondary)
                } else {
                    Button("Tune", action: onShowTune).buttonStyle(.borderless)
                }
            }
            if model.isRunning || model.warming != nil {
                if let fraction = progress {
                    ProgressView(value: fraction)
                } else {
                    ProgressView().progressViewStyle(.linear)
                }
            }
            HStack(spacing: 10) {
                if case .running(let source, _, _, _, _, _, _, _) = model.running {
                    Button(role: .cancel, action: model.cancel) { Text("Cancel").frame(maxWidth: .infinity) }
                        .glassButton()
                        .disabled(source == .agent)
                } else if !model.results.isEmpty {
                    Button(action: model.variations) { Text("Variations").frame(maxWidth: .infinity) }
                        .glassButton()
                        .disabled(!model.canGenerate)
                    Button(action: model.runAgain) { Text("Run again").frame(maxWidth: .infinity) }
                        .glassProminentButton()
                        .disabled(!model.canGenerate)
                } else {
                    Button(action: model.generate) { Text("Generate").frame(maxWidth: .infinity) }
                        .glassProminentButton()
                        .disabled(!model.canGenerate)
                }
            }
            .controlSize(.large)
        }
        .padding(16)
        .glassSurface(in: RoundedRectangle(cornerRadius: 28, style: .continuous))
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
        .readableWidth()
    }

    /// The model loading (no steps yet) is indeterminate (nil); sampling fills the bar.
    private var progress: Double? {
        guard case .running(_, _, let r, let image, let step, let total, _, _) = model.running else { return nil }
        guard model.phase == nil, total > 0 else { return nil }
        return min(1, (Double(image) + Double(step) / Double(total)) / Double(max(r.batchCount, 1)))
    }

    private func status(now: Date) -> String {
        let r = model.request
        if case .running(let source, let backend, _, _, _, _, _, let started) = model.running {
            let seconds = Int(now.timeIntervalSince(started))
            if let phase = model.phase { return "\(phase) · \(seconds) s" }
            if source == .agent { return "Agent is generating · \(seconds) s" }
            return "Running \(model.backend?.kind == .onDevice ? "on device" : "on \(backend)") · \(seconds) s"
        }
        if let warming = model.warming { return warming }
        if !model.results.isEmpty { return "Done · \(model.results.count) image\(model.results.count == 1 ? "" : "s")" }
        if let blocker = model.generateBlocker, !blocker.isEmpty { return blocker }
        return "\(r.mode.label) · \(r.sampler.isEmpty ? "default sampler" : r.sampler) · CFG \(String(format: "%.1f", r.cfgScale))"
    }
}

private struct InitStrip: View {
    let model: ImagineModel
    let onEditMask: () -> Void
    @State private var picked: PhotosPickerItem?

    var body: some View {
        HStack(spacing: 12) {
            PhotosPicker(selection: $picked, matching: .images, photoLibrary: .shared()) {
                ZStack {
                    RoundedRectangle(cornerRadius: 12, style: .continuous).fill(.fill.tertiary)
                    if let image = model.initImage {
                        Image(uiImage: image).resizable().scaledToFill()
                        if model.request.mode == .inpaint { MaskOverlay(mask: model.mask) }
                    } else {
                        Image(systemName: "photo.badge.plus").font(.title2).foregroundStyle(.secondary)
                    }
                }
                .frame(width: 72, height: 72)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            .accessibilityLabel("Pick an image")
            VStack(alignment: .leading, spacing: 4) {
                Text(model.request.mode == .inpaint ? "Inpaint · \(model.mask.isEmpty ? "no mask" : counted(model.mask.strokes.count, "stroke"))" : "Img2img")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                LabeledSlider("Strength", value: Binding(get: { model.request.denoiseStrength }, set: { model.setDenoise($0) }), in: 0.05 ... 1)
            }
            if model.request.mode == .inpaint && model.initImage != nil {
                Button(action: onEditMask) { Image(systemName: "paintbrush.pointed") }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("Edit mask")
            }
        }
        .padding(12)
        .background(.fill.quaternary, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
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
    let onDone: () -> Void
    let onOpenBackends: () -> Void
    /// The resolution row's choice (nil: the request's own long side).
    @State private var base: Int?
    @State private var showLoras = false

    var body: some View {
        let request = model.request
        let caps = model.capabilities
        let kind = model.backend?.kind
        NavigationStack {
            Form {
                Section {
                    Toggle(isOn: Binding(get: { model.incognito }, set: { model.incognito = $0 })) {
                        Text(PanicCopy.shared.INCOGNITO_IMAGES_TITLE)
                        Text(PanicCopy.shared.INCOGNITO_IMAGES_INFO)
                    }
                    let modes = DiffusionMode.allCases.filter { caps?.modes.contains($0) ?? true }
                    Picker("Mode", selection: Binding(get: { model.request.mode }, set: { model.setMode($0) })) {
                        ForEach(modes, id: \.self) { Text($0.label.sentenceCased).tag($0) }
                    }
                    .pickerStyle(.segmented)
                }

                Section("Negative") {
                    TextField("Negative", text: Binding(get: { model.request.negativePrompt }, set: { model.setNegative($0) }), axis: .vertical)
                        .font(.body.monospaced())
                        .lineLimit(2 ... 5)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            Text("Presets").font(.caption).foregroundStyle(.secondary)
                            ForEach(ImagineModel.negativePresets, id: \.id) { preset in
                                Button { model.addNegative(preset) } label: { Label(preset.name, systemImage: "plus").chipLook(active: false) }
                                    .buttonStyle(.plain)
                                    .accessibilityLabel("Add the \(preset.name) negative preset")
                            }
                        }
                    }
                }

                Section {
                    let (samplers, value): ([String], String) = switch kind {
                    case .onDevice: (LocalDiffusion.samplers, request.sampler)
                    case .localSse: (LocalSseEngine.schedulers, LocalSseEngine.schedulerId(request.sampler, request.scheduler))
                    default: (model.catalog.samplers.isEmpty ? defaultSamplers : model.catalog.samplers, request.sampler)
                    }
                    MenuRow("Sampler", value: value, options: samplers) { model.setSampler($0, scheduler: kind == .sdApi || kind == .onDevice ? request.scheduler : nil) }
                    let schedulers: [String] = switch kind {
                    case .onDevice: LocalDiffusion.schedulers
                    case .sdApi: model.catalog.schedulers
                    default: []
                    }
                    if !schedulers.isEmpty {
                        MenuRow("Schedule", value: request.scheduler ?? "default", options: schedulers) { model.setScheduler($0) }
                    }
                    LabeledSlider("Steps", value: Binding(get: { Double(model.request.steps) }, set: { model.setSteps(Int($0)) }),
                                  in: 1 ... Double(caps?.maxSteps ?? 150), step: 1, format: { "\(Int($0))" })
                    LabeledSlider("CFG", value: Binding(get: { model.request.cfgScale }, set: { model.setCfg($0) }), in: 1 ... 20, format: { String(format: "%.1f", $0) })
                } header: {
                    Text("Sampling")
                } footer: {
                    VStack(alignment: .leading, spacing: 4) {
                        if kind == .onDevice {
                            Text("Core ML offers DPM++ 2M (Karras) and PNDM; other samplers fall back. Inpaint is img2img with a feathered mask.")
                        }
                        if request.cfgScale <= 1 { Text("CFG 1 skips the unconditional pass: right for turbo, LCM and distilled models.") }
                    }
                }

                SizePicker(model: model, base: $base)

                Section {
                    LabeledContent("Seed") {
                        HStack(spacing: 12) {
                            Text(request.seed < 0 ? "random" : "\(request.seed)").monospacedDigit()
                            Button(action: model.randomizeSeed) { Image(systemName: "dice") }
                                .buttonStyle(.borderless)
                                .accessibilityLabel("Fixed random seed")
                            if request.seed >= 0 { Button("Random", action: model.setRandomSeed).buttonStyle(.borderless) }
                        }
                    }
                    Stepper(value: Binding(get: { model.request.batchCount }, set: { model.setBatch($0) }), in: 1 ... ImagineModel.maxBatch) {
                        LabeledContent("Batch", value: "\(request.batchCount)")
                    }
                }

                if request.mode == .inpaint && caps?.maskEncoding == .opaqueBlackWhite && kind == .sdApi {
                    Section("Inpaint") {
                        LabeledSlider("Mask blur", value: Binding(get: { Double(model.request.maskBlur) }, set: { model.setMaskBlur(Int($0)) }),
                                      in: 0 ... 64, step: 1, format: { "\(Int($0)) px" })
                        Toggle("Only masked area", isOn: Binding(get: { model.request.inpaintOnlyMasked }, set: { model.setOnlyMasked($0) }))
                    }
                }

                Section {
                    ForEach(request.loras, id: \.name) { lora in
                        HStack {
                            LabeledSlider(lora.name, value: Binding(get: { lora.weight }, set: { model.setLoraWeight(lora.name, $0) }), in: -1 ... 2)
                            Button { model.removeLora(lora.name) } label: { Image(systemName: "xmark.circle.fill") }
                                .buttonStyle(.borderless)
                                .foregroundStyle(.secondary)
                                .accessibilityLabel("Remove \(lora.name)")
                        }
                    }
                    if caps?.runtimeLora == true {
                        Button { showLoras = true } label: { Label("Add LoRA", systemImage: "plus") }
                    }
                } header: {
                    Text("LoRA")
                } footer: {
                    if caps?.runtimeLora == false { Text("This backend runs a fixed model; merge LoRAs before conversion.") }
                }
            }
            .navigationTitle("Tune")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Backends", action: onOpenBackends) }
                ToolbarItem(placement: .confirmationAction) { Button("Done", action: onDone) }
            }
        }
        .sheet(isPresented: $showLoras) {
            LoraSheet(available: model.catalog.loras, selected: Set(request.loras.map(\.name))) {
                model.addLora($0)
                showLoras = false
            }
            .presentationDetents([.medium, .large])
        }
    }
}

private struct LoraSheet: View {
    let available: [String]
    let selected: Set<String>
    let onPick: (String) -> Void
    @State private var query = ""

    var body: some View {
        let filtered = available.filter { query.isEmpty || $0.localizedCaseInsensitiveContains(query) }
        NavigationStack {
            List(filtered, id: \.self) { name in
                Button { onPick(name) } label: {
                    HStack {
                        Label(name, systemImage: "square.stack.3d.up").font(.body.monospaced()).lineLimit(1)
                        Spacer()
                        if selected.contains(name) { Text("Added").font(.caption).foregroundStyle(.secondary) }
                    }
                }
                .disabled(selected.contains(name))
            }
            .overlay {
                if filtered.isEmpty {
                    if available.isEmpty {
                        ContentUnavailableView("No LoRAs", systemImage: "square.stack.3d.up", description: Text("Add them to your PC backend."))
                    } else {
                        ContentUnavailableView.search(text: query)
                    }
                }
            }
            .searchable(text: $query, prompt: "Filter")
            .navigationTitle("LoRA library · \(available.count)")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

/**
 Aspect × resolution. The resolution is a pixel budget (16:9 · 1024 = about 1024² px = 1368×768);
 each aspect shows the exact size it gives. A Core ML model is compiled for one canvas: the resolution row gives way to
 that size, and each aspect shows what is cut from it, so nothing comes out square by surprise.
 */
private struct SizePicker: View {
    let model: ImagineModel
    @Binding var base: Int?

    var body: some View {
        let request = model.request
        let native = model.nativeSize
        let resolution = base ?? model.resolution
        let multiple = model.capabilities?.sizeMultiple ?? 8
        let out = model.outputSize
        Section {
            if native == nil {
                Picker("Resolution", selection: Binding(get: { resolution }, set: { r in
                    base = r
                    model.setResolution(r)
                })) {
                    ForEach(ImageSizing.resolutions, id: \.self) { Text("\($0)").tag($0) }
                }
                .pickerStyle(.segmented)
            }
            ImagineTagFlow(spacing: 6) {
                ForEach(AspectRatio.all) { aspect in
                    let s = ImagineModel.size(aspect: aspect, resolution: resolution, native: native, multiple: multiple)
                    let active = s.width == request.width && s.height == request.height
                    Button { model.setAspect(aspect, resolution: resolution) } label: {
                        Text("\(aspect.label) · \(s.width)×\(s.height)").monospacedDigit().chipLook(active: active)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(aspect.spoken), \(s.width) by \(s.height)")
                    .accessibilityAddTraits(active ? .isSelected : [])
                }
            }
            .padding(.vertical, 4)
        } header: {
            Text("Size")
        } footer: {
            if let native {
                Text("\(out.width)×\(out.height) · \(ImageSizing.compiledNote(native))")
            } else {
                Text("\(request.width)×\(request.height) · pixel budget")
            }
        }
    }
}
