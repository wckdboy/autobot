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

    var body: some View {
        StudioRoomLayout(collapsed: Binding(get: { session.collapsed }, set: { session.collapsed = $0 })) {
            stage
        } controls: {
            controls
        } runBar: {
            runBar
        }
    }

    // MARK: Stage: clips, waveform, playback, trim

    private var stage: some View {
        let player = session.player
        return List {
            if let job = model.runningJob {
                HStack(spacing: 12) {
                    ProgressView()
                    VStack(alignment: .leading, spacing: 6) {
                        Text(job.status ?? (job.state == .queued ? "Queued" : "Generating")).font(.headline)
                        if job.state == .running && job.steps > 0 {
                            ProgressView(value: Double(job.progress)).progressViewStyle(.linear)
                        }
                    }
                }
                .padding(.vertical, 4)
            }
            if let clip = model.selected {
                Section {
                    ClipStage(clip: clip, player: player, trim: $model.trim) { Task { await model.saveTrim() } }
                }
            }
            if !model.clips.isEmpty {
                Section("Clips · \(model.clips.count)") {
                    ForEach(model.clips.prefix(20), id: \.id) { clip in
                        let active = clip.id == model.selected?.id
                        let playing = player.playingId == clip.id
                        HStack(spacing: 10) {
                            Button {
                                if playing { player.stop() } else { Task { if let d = await app.studio.bytes(clip.id) { player.play(clip.id, data: d) } } }
                            } label: {
                                Image(systemName: playing ? "stop.fill" : "play.fill").frame(width: 28, height: 28)
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel(playing ? "Stop" : "Play")
                            AssetThumb(asset: clip).frame(width: 90, height: 32)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(clip.recipe.text("prompt") ?? clip.recipe.text("text") ?? "clip").font(.subheadline).lineLimit(1)
                                Text(clip.label ?? StudioText.meta(clip)).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            if active { Image(systemName: "checkmark").foregroundStyle(.tint).accessibilityHidden(true) }
                        }
                        .contentShape(Rectangle())
                        .onTapGesture { model.selectedId = clip.id; model.trim = 0 ... 1 }
                        .accessibilityAddTraits(active ? .isSelected : [])
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
    }

    // MARK: Controls

    @ViewBuilder private var controls: some View {
        Section {
            Picker("Kind", selection: $model.tab) {
                Text("Sound").tag(AudioRoomModel.Tab.sound)
                Text("Voiceover").tag(AudioRoomModel.Tab.voiceover)
            }
            .pickerStyle(.segmented)
            .labelsHidden()
        }
        switch model.tab {
        case .sound: soundControls
        case .voiceover: voiceControls
        }
    }

    @ViewBuilder private var soundControls: some View {
        if !model.workflows.isEmpty {
            Section {
                OptionPicker("Engine", selection: model.workflowId ?? "", options: [""] + model.workflows.map(\.id),
                             optionLabel: { id in id.isEmpty ? "This iPhone · Stable Audio" : model.workflows.first { $0.id == id }?.name ?? id }) { id in model.workflowId = id.isEmpty ? nil : id }
            }
        }
        if let w = model.workflow {
            let server = app.studio.comfyServer(w.backendId) ?? app.studio.comfyServers.first
            Section {
                RunsOnTag(label: server?.name ?? "your pc", route: server?.route ?? .pcLan)
                PromptBox(prompt: $model.prompt, negative: .constant(""), placeholder: "rain on a tin roof, distant thunder", showNegative: false)
            }
            Section(w.name) {
                WorkflowForm(workflow: w, values: $model.workflowValues) { $0.name == "prompt" || $0.type == .seed }
            }
        } else if !model.hasLocalAudio {
            Section { StabilityCredit() }
            Section {
                StudioEmpty(StudioRoomWords.shared.audioNoModel) {
                    nav.leaveStudio()
                    nav.switchTo(.models)
                }
            }
        } else {
            Section { StabilityCredit() }
            Section {
                RunsOnTag(label: "this iphone", route: .localNpu)
                PromptBox(prompt: $model.prompt, negative: .constant(""), placeholder: "rain on a tin roof, distant thunder", showNegative: false)
            }
            Section {
                LabeledSlider("Seconds", value: $model.seconds, in: 0.1 ... AudioStudioEngine.maxSeconds, step: 0.1, format: { String(format: "%.1f s", $0) })
                LabeledSlider("Steps", value: Binding(get: { Double(model.steps) }, set: { model.steps = Int($0) }), in: 1 ... Double(AudioStudioEngine.maxSteps), step: 1, format: { "\(Int($0))" })
                LabeledSlider("CFG", value: $model.cfg, in: 0 ... 7, step: 0.5, format: { String(format: "%.1f", $0) })
            } footer: {
                if model.cfg > 1.5 { Text("CFG 1 is what the small model was distilled for; higher doubles the work.") }
            }
        }
        if model.workflow != nil || model.hasLocalAudio {
            SeedControl(mode: $model.seedMode, seed: $model.seed)
            Section {
                Stepper("Clips · \(model.count)", value: $model.count, in: 1 ... AudioStudioEngine.maxCount)
            }
        }
    }

    @ViewBuilder private var voiceControls: some View {
        if !model.hasVoice {
            Section {
                StudioEmpty(StudioRoomWords.shared.voiceNoModel) {
                    nav.leaveStudio()
                    nav.switchTo(.models)
                }
            }
        } else {
            Section {
                RunsOnTag(label: "this iphone · kokoro", route: .localNpu)
                StackedField(label: "Text", text: $model.text, prompt: "The text to read", kind: .prose, lines: 3 ... 10)
            }
            Section {
                OptionPicker("Voice", selection: model.voice, options: model.voices) { model.voice = $0 }
                LabeledSlider("Speed", value: $model.speed, in: 0.5 ... 2, step: 0.05, format: { String(format: "%.2f×", $0) })
            }
        }
    }

    private var runBar: some View {
        let blocker = model.blocker
        let status = blocker ?? (model.tab == .sound
            ? String(format: "%.1f s · ", model.seconds) + "\(counted(model.steps, "step")) · \(counted(model.count, "clip"))"
            : "voice \(model.voice) · \(String(format: "%.2f", model.speed))×")
        return StudioRunBar(status: status) {
            Button { model.generate() } label: {
                Text(model.tab == .sound ? "Generate" : "Read aloud").frame(maxWidth: .infinity)
            }
            .glassProminentButton()
            .disabled(blocker != nil)
        }
    }
}

/// The Stability AI Community License credit, shown wherever Stable Audio runs.
struct StabilityCredit: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("Powered by Stability AI").font(.subheadline.weight(.semibold)).lineLimit(1).minimumScaleFactor(0.8)
            Text("Stable Audio Open Small · Stability AI Community License").font(.caption).foregroundStyle(.secondary)
        }
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
    @State private var peaks: [Float] = []
    @State private var trimming = false

    var body: some View {
        let playing = player.playingId == clip.id
        VStack(alignment: .leading, spacing: 8) {
            Text(clip.recipe.text("prompt") ?? clip.recipe.text("text") ?? "clip").font(.headline).lineLimit(2)
            Text(StudioText.meta(clip)).font(.caption).foregroundStyle(.secondary)
            PlayheadWaveform(id: clip.id, peaks: peaks, player: player, trim: trimming ? $trim : nil)
                .frame(height: 96)
                .padding(.vertical, 4)
            HStack(spacing: 8) {
                Button {
                    if playing { player.stop() } else { Task { if let d = await app.studio.bytes(clip.id) { player.play(clip.id, data: d) } } }
                } label: {
                    Image(systemName: playing ? "stop.fill" : "play.fill").frame(width: 28, height: 28)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(playing ? "Stop" : "Play")
                StudioChip(player.loop ? "Loop on" : "Loop off", active: player.loop) { player.setLoop(!player.loop) }
                StudioChip(trimming ? "Trim · drag the handles" : "Trim", active: trimming) { trimming.toggle() }
                Spacer()
                if trimming && trim != 0 ... 1 {
                    Button("Save trim") { onSaveTrim(); trimming = false }.buttonStyle(.borderless)
                }
            }
            if trimming {
                let s = Double(clip.durationMs) / 1000
                Text(String(format: "Keep %.1f – %.1f s of %.1f s", trim.lowerBound * s, trim.upperBound * s, s)).font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
        .task(id: clip.id) { peaks = await app.studio.waveform(clip) }
    }
}
