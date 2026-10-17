import AutobotShared
import Observation
import SwiftUI

/**
 Audio room state (spec §3): text to audio on this phone (Stable Audio Open Small) or a ComfyUI
 audio workflow, and the Kokoro voiceover. Clips play from memory with loop; trim saves a new clip.
 */
@MainActor
@Observable
final class AudioRoomModel {
    enum Tab: String, CaseIterable { case sound, voiceover }

    var tab: Tab = .sound
    var prompt = ""
    var seconds = 10.0
    var steps = 8
    var cfg = 1.0
    var count = 1
    var seedMode: SeedMode = .random
    var seed: Int64 = Int64.random(in: 0 ... 4_294_967_295)
    /// nil = on this phone; else a ComfyUI audio workflow.
    var workflowId: String?
    var workflowValues: [String: ParamValue] = [:]
    var text = ""
    var voice = LocalVoice.defaultVoice
    var speed = 1.0
    var selectedId: String?
    var trim: ClosedRange<Double> = 0 ... 1
    private(set) var jobIds: [String] = []

    @ObservationIgnored private let app: AppContainer

    init(app: AppContainer) { self.app = app }

    var hasLocalAudio: Bool { app.studio.has(EngineKinds.shared.AUDIO) }
    var hasVoice: Bool { app.studio.has(EngineKinds.shared.SPEECH) }
    var workflows: [StudioWorkflow] { app.studio.library.workflows.filter { $0.output == .audio } }
    var workflow: StudioWorkflow? { app.studio.library.workflow(workflowId) }

    var voices: [String] {
        guard let m = app.library.models.first(where: { $0.isReady && $0.kind == .voice && $0.engine == .coreML }) else { return [LocalVoice.defaultVoice] }
        let list = LocalVoice.voices(in: ModelLibrary.dir(of: m.id))
        return list.isEmpty ? [LocalVoice.defaultVoice] : list
    }

    var clips: [StudioAsset] { app.studio.library.assets.filter { $0.type == .audio && $0.projectId == app.studio.projectId } }
    var selected: StudioAsset? { selectedId.flatMap { app.studio.library.asset($0) } ?? clips.first }
    var runningJob: StudioJob? { jobIds.reversed().lazy.compactMap { self.app.studio.job($0) }.first { !$0.state.isFinished } }

    var blocker: String? {
        switch tab {
        case .sound:
            if let w = workflow {
                if app.studio.comfyServers.isEmpty { return "Add your ComfyUI PC in Workflows" }
                return w.param(name: "prompt") != nil && prompt.trimmingCharacters(in: .whitespaces).isEmpty ? "Describe the sound" : nil
            }
            if !hasLocalAudio { return "Get the audio model in Models" }
            return prompt.trimmingCharacters(in: .whitespaces).isEmpty ? "Describe the sound" : nil
        case .voiceover:
            if !hasVoice { return "Get the Kokoro voice in Models" }
            return text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "Type the text to read" : nil
        }
    }

    func generate() {
        guard blocker == nil else { return }
        let studio = app.studio
        switch tab {
        case .sound:
            if let w = workflow {
                var params = workflowValues.filter { w.param(name: $0.key) != nil }
                if w.param(name: "prompt") != nil { params["prompt"] = .text(prompt) }
                if let p = w.params.first(where: { $0.role == "seconds" }), params[p.name] == nil { params[p.name] = .double(seconds) }
                let server = w.backendId.flatMap { studio.comfyServer($0) } ?? studio.comfyServers.first
                let r = Recipe(engine: EngineKinds.shared.COMFYUI, backendId: server?.id, model: nil, params: params, inputs: [], workflowId: w.id,
                               workflowVersion: KotlinInt(value: w.version), seed: seedMode == .random ? nil : KotlinLong(value: seed), output: .audio)
                jobIds += studio.submitBatch(r, count: count, mode: seedMode).map(\.id)
            } else {
                let r = Recipe(engine: EngineKinds.shared.AUDIO, backendId: nil, model: nil,
                               params: ["prompt": .text(prompt), "seconds": .double(seconds), "steps": .int(steps), "cfg": .double(cfg), "count": .int(count)],
                               inputs: [], workflowId: nil, workflowVersion: nil, seed: KotlinLong(value: seedMode == .random ? Int64.random(in: 0 ... 4_294_967_295) : seed), output: .audio)
                // One job makes the batch (clips walk from the seed), so the model loads once.
                if let job = studio.submit(r, label: count > 1 ? "× \(count)" : nil) { jobIds.append(job.id) }
            }
            if seedMode == .walk { seed = (seed + Int64(count)) % 4_294_967_296 }
        case .voiceover:
            let r = Recipe(engine: EngineKinds.shared.SPEECH, backendId: nil, model: nil, params: ["text": .text(text), "voice": .text(voice), "speed": .double(speed)],
                           inputs: [], workflowId: nil, workflowVersion: nil, seed: nil, output: .audio)
            if let job = studio.submit(r, label: voice) { jobIds.append(job.id) }
        }
        selectedId = nil
    }

    func reuse(_ recipe: Recipe) {
        if recipe.engine == EngineKinds.shared.SPEECH {
            tab = .voiceover
            text = recipe.text("text") ?? text
            voice = recipe.text("voice") ?? voice
            speed = recipe.double("speed") ?? speed
        } else {
            tab = .sound
            prompt = recipe.text("prompt") ?? prompt
            seconds = recipe.double("seconds") ?? seconds
            steps = recipe.int("steps") ?? steps
            cfg = recipe.double("cfg") ?? cfg
            workflowId = recipe.engine == EngineKinds.shared.COMFYUI ? recipe.workflowId : nil
            if workflowId != nil { workflowValues = recipe.params }
            if let s = recipe.seedValue { seed = s; seedMode = .locked }
        }
    }

    /// Saves the trimmed part of the selected clip as a new clip (the original stays).
    func saveTrim() async {
        guard let clip = selected, trim != 0 ... 1 else { return }
        let files = app.studio.files, id = clip.id, ext = StudioCodec.shared.extension(mimeType: clip.mimeType)
        let seconds = Double(clip.durationMs) / 1000
        let range = trim
        let result = await Task.detached { () -> (data: Data, durationMs: Int64)? in
            guard let url = files.openPlain(id, ext: ext) else { return nil }
            defer { try? FileManager.default.removeItem(at: url) }
            return StudioAudio.trim(file: url, start: range.lowerBound * seconds, end: range.upperBound * seconds)
        }.value
        guard let result else {
            app.studio.notice("Could not trim that clip")
            return
        }
        if let a = try? await app.studio.addAsset(result.data, type: .audio, mime: "audio/wav", recipe: clip.recipe, durationMs: result.durationMs, parent: clip.id,
                                                  label: String(format: "trim %.1f–%.1f s", range.lowerBound * seconds, range.upperBound * seconds)) {
            selectedId = a.id
            trim = 0 ... 1
        }
    }
}

struct AudioRoom: View {
    @Bindable var model: AudioRoomModel
    let session: StudioSession
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c

    var body: some View {
        VStack(spacing: 0) {
            stage.frame(minHeight: 170, maxHeight: .infinity)
            StudioPanel(title: model.tab == .sound ? "sound" : "voiceover", collapsed: Binding(get: { session.collapsed }, set: { session.collapsed = $0 })) { controls }
                .frame(maxHeight: session.collapsed ? nil : 340)
                    // The controls get their height first; the canvas takes the rest.
                    .layoutPriority(1)
            runBar
        }
    }

    // MARK: Stage: clips, waveform, playback, trim

    private var stage: some View {
        let player = session.player
        return ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                if let job = model.runningJob {
                    HStack(spacing: 10) {
                        InkLoader(.block)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(job.status ?? (job.state == .queued ? "Queued" : "Generating")).textStyle(.titleSmall).foregroundStyle(c.onSurface)
                            InkProgressBar(job.state == .queued ? .progress(0, running: false) : job.steps == 0 ? .indeterminate : .progress(Double(job.progress), running: true))
                        }
                    }
                    .padding(.top, 8)
                }
                if let clip = model.selected {
                    ClipStage(clip: clip, player: player, trim: $model.trim) { Task { await model.saveTrim() } }
                } else if model.runningJob == nil {
                    Color.clear.frame(height: 16)
                }
                if !model.clips.isEmpty {
                    SectionLabel("clips · \(model.clips.count)")
                    ForEach(model.clips.prefix(20), id: \.id) { clip in
                        let active = clip.id == model.selected?.id
                        HStack(spacing: 10) {
                            IconAction(symbol: player.playingId == clip.id ? "stop.fill" : "play.fill", label: player.playingId == clip.id ? "Stop" : "Play", size: 16) {
                                if player.playingId == clip.id { player.stop() } else { Task { if let d = await app.studio.bytes(clip.id) { player.play(clip.id, data: d) } } }
                            }
                            AssetThumb(asset: clip).frame(width: 90, height: 32)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(clip.recipe.text("prompt") ?? clip.recipe.text("text") ?? "clip").textStyle(.labelMedium).foregroundStyle(c.onSurface).lineLimit(1)
                                MicroLabel(clip.label ?? StudioText.meta(clip))
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .padding(.vertical, 2)
                        .background(active ? c.primary.opacity(0.12) : .clear)
                        .contentShape(Rectangle())
                        .onTapGesture { model.selectedId = clip.id; model.trim = 0 ... 1 }
                        .accessibilityAddTraits(active ? .isSelected : [])
                        Hairline()
                    }
                }
            }
            .padding(.horizontal, Tokens.Spacing.gutter)
        }
    }

    // MARK: Controls

    @ViewBuilder private var controls: some View {
        Segmented(options: AudioRoomModel.Tab.allCases, selected: model.tab, label: { $0.rawValue }) { model.tab = $0 }
        switch model.tab {
        case .sound: soundControls
        case .voiceover: voiceControls
        }
    }

    @ViewBuilder private var soundControls: some View {
        if !model.workflows.isEmpty {
            SelectField(label: "Engine", value: model.workflow?.name ?? "this iphone · stable audio", options: [""] + model.workflows.map(\.id),
                        optionLabel: { id in id.isEmpty ? "this iphone · stable audio" : model.workflows.first { $0.id == id }?.name ?? id }) { id in model.workflowId = id.isEmpty ? nil : id }
        }
        if let w = model.workflow {
            let server = app.studio.comfyServer(w.backendId) ?? app.studio.comfyServers.first
            RunsOnTag(label: server?.name ?? "your pc", route: server?.route ?? .pcLan)
            PromptBox(prompt: $model.prompt, negative: .constant(""), placeholder: "rain on a tin roof, distant thunder", showNegative: false)
            WorkflowForm(workflow: w, values: $model.workflowValues) { $0.name == "prompt" || $0.type == .seed }
        } else if !model.hasLocalAudio {
            StabilityCredit()
            StudioEmpty(title: "no audio model yet",
                        text: "Stable Audio Open Small makes up to 11.9 s of 44.1 kHz stereo sound on this iPhone. Get it in Models › audio (about 600 MB).",
                        action: "Get the audio model in Models") {
                nav.leaveStudio()
                nav.switchTo(.models)
            }
        } else {
            StabilityCredit()
            RunsOnTag(label: "this iphone", route: .localNpu)
            PromptBox(prompt: $model.prompt, negative: .constant(""), placeholder: "rain on a tin roof, distant thunder", showNegative: false)
            ValueSlider(label: "Seconds", value: $model.seconds, range: 0.1 ... AudioStudioEngine.maxSeconds, step: 0.1, format: { String(format: "%.1f s", $0) })
            ValueSlider(label: "Steps", value: Binding(get: { Double(model.steps) }, set: { model.steps = Int($0) }), range: 1 ... Double(AudioStudioEngine.maxSteps), step: 1, format: { "\(Int($0))" })
            ValueSlider(label: "CFG", value: $model.cfg, range: 0 ... 7, step: 0.5, format: { String(format: "%.1f", $0) })
            if model.cfg > 1.5 { MicroLabel("cfg 1 is what the small model was distilled for; higher doubles the work", lines: 2) }
        }
        if model.workflow != nil || model.hasLocalAudio {
            SeedControl(mode: $model.seedMode, seed: $model.seed)
            StepperField(label: "Clips", value: model.count, range: 1 ... AudioStudioEngine.maxCount) { model.count = $0 }
        }
    }

    @ViewBuilder private var voiceControls: some View {
        if !model.hasVoice {
            StudioEmpty(title: "no voice yet", text: "Kokoro reads text aloud on this iPhone. Get it in Models › voice.", action: "Get the voice in Models") {
                nav.leaveStudio()
                nav.switchTo(.models)
            }
        } else {
            RunsOnTag(label: "this iphone · kokoro", route: .localNpu)
            ConsoleTextField(text: $model.text, placeholder: "The text to read", label: "Text", kind: .prose, minLines: 3, maxLines: 10)
            SelectField(label: "Voice", value: model.voice, options: model.voices) { model.voice = $0 }
            ValueSlider(label: "Speed", value: $model.speed, range: 0.5 ... 2, step: 0.05, format: { String(format: "%.2f×", $0) })
        }
    }

    private var runBar: some View {
        let blocker = model.blocker
        return VStack(spacing: 6) {
            Text(blocker ?? (model.tab == .sound ? String(format: "%.1f s · ", model.seconds) + "\(counted(model.steps, "step")) · \(counted(model.count, "clip"))" : "voice \(model.voice) · \(String(format: "%.2f", model.speed))×"))
                .textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
            PrimaryButton(model.tab == .sound ? "Generate" : "Read aloud", enabled: blocker == nil) { model.generate() }
        }
        .padding(.horizontal, Tokens.Spacing.gutter)
        .padding(.vertical, 8)
        .background(c.background)
        .hairlineEdge(top: true, bottom: false)
    }
}

/// The Stability AI Community License credit, shown wherever Stable Audio runs.
struct StabilityCredit: View {
    @Environment(\.scheme) private var c

    var body: some View {
        HStack(spacing: 8) {
            Rectangle().fill(c.primary).frame(width: 8, height: 8)
            VStack(alignment: .leading, spacing: 2) {
                Text("POWERED BY STABILITY AI").textStyle(.labelLarge).foregroundStyle(c.onSurface).lineLimit(1).minimumScaleFactor(0.8)
                MicroLabel("stable audio open small · stability ai community license", lines: 2)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .overlay(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall).strokeBorder(c.outline, lineWidth: Tokens.Stroke.border))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Powered by Stability AI. Stable Audio Open Small, Stability AI Community License.")
    }
}

/// The selected clip: waveform with the playhead and trim handles, play / loop, save the trim.
private struct ClipStage: View {
    let clip: StudioAsset
    let player: StudioAudioPlayer
    @Binding var trim: ClosedRange<Double>
    let onSaveTrim: () -> Void
    @Environment(AppContainer.self) private var app
    @Environment(\.scheme) private var c
    @State private var peaks: [Float] = []
    @State private var trimming = false

    var body: some View {
        let playing = player.playingId == clip.id
        VStack(alignment: .leading, spacing: 8) {
            Text(clip.recipe.text("prompt") ?? clip.recipe.text("text") ?? "clip").textStyle(.titleMedium).foregroundStyle(c.onSurface).lineLimit(2)
            MicroLabel(StudioText.meta(clip))
            PlayheadWaveform(id: clip.id, peaks: peaks, player: player, trim: trimming ? $trim : nil)
                .frame(height: 96)
                .padding(.vertical, 4)
            HStack(spacing: 8) {
                IconAction(symbol: playing ? "stop.fill" : "play.fill", label: playing ? "Stop" : "Play") {
                    if playing { player.stop() } else { Task { if let d = await app.studio.bytes(clip.id) { player.play(clip.id, data: d) } } }
                }
                Tag(player.loop ? "loop on" : "loop off", accent: player.loop ? c.primary : c.onSurfaceVariant, filled: player.loop) { player.setLoop(!player.loop) }
                Tag(trimming ? "trim · drag the handles" : "trim", accent: c.primary) { trimming.toggle() }
                Spacer()
                if trimming && trim != 0 ... 1 {
                    TextAction("Save trim") { onSaveTrim(); trimming = false }
                }
            }
            if trimming {
                let s = Double(clip.durationMs) / 1000
                MicroLabel(String(format: "keep %.1f – %.1f s of %.1f s", trim.lowerBound * s, trim.upperBound * s, s))
            }
        }
        .padding(.top, 8)
        .task(id: clip.id) { peaks = await app.studio.waveform(clip) }
    }
}
