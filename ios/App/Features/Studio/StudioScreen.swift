import AutobotShared
import Observation
import SwiftUI

/**
 The Creative Studio (spec §1): a mode of its own. Its bar (Image · Audio · Video · Workflows ·
 Library) replaces the tab bar; the chrome is a thin top bar (leave, room, project) and the queue
 strip; the canvas is the hero and the controls fold away. Darkroom (ink) for image and video,
 paper for the library. Leaving returns to the app exactly where it was; queued jobs keep going.
 */
struct StudioScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @State private var session: StudioSession?

    var body: some View {
        let room = nav.studio ?? .image
        Group {
            if let session {
                StudioFrame(room: room, session: session)
            } else {
                Color.clear
            }
        }
        .modifier(StudioSurfaceTheme(surface: room.surface, amoled: app.settings.settings.amoled))
        .task {
            if session == nil { session = StudioSession(app: app) }
            app.studio.syncEngines(app: app)
        }
    }
}

/// The room's riso scheme: darkroom (ink), paper, or the app's own.
struct StudioSurfaceTheme: ViewModifier {
    let surface: StudioSurface
    let amoled: Bool

    func body(content: Content) -> some View {
        switch surface {
        case .darkroom: content.darkroomTheme(amoled: amoled)
        case .paper: content.paperTheme()
        case .app: content
        }
    }
}

/// State the rooms keep while the Studio is open (rebuilt on the next visit; jobs and assets persist).
@MainActor
@Observable
final class StudioSession {
    let image: ImageRoomModel
    let audio: AudioRoomModel
    let video: VideoRoomModel
    let player = StudioAudioPlayer()
    /// Controls folded away: the canvas fills the room.
    var collapsed = false

    init(app: AppContainer) {
        image = ImageRoomModel(app: app)
        audio = AudioRoomModel(app: app)
        video = VideoRoomModel(app: app)
    }
}

private struct StudioFrame: View {
    let room: StudioRoom
    let session: StudioSession
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c

    var body: some View {
        VStack(spacing: 0) {
            StudioTopBar(room: room, session: session)
            QueueStrip()
            Group {
                switch room {
                case .image: ImageRoom(model: session.image, session: session)
                case .audio: AudioRoom(model: session.audio, session: session)
                case .video: VideoRoom(model: session.video, session: session)
                case .workflows: WorkflowsRoom(session: session)
                case .library: LibraryRoom(session: session)
                }
            }
            .id(room)
            .inkArrive()
            .frame(maxHeight: .infinity)
            if let message = app.studio.noticeText {
                HStack {
                    Text(message).textStyle(.bodySmall).foregroundStyle(c.onPrimary).frame(maxWidth: .infinity, alignment: .leading)
                    IconAction(symbol: "xmark", label: "Dismiss", tint: c.onPrimary, size: 14) { app.studio.noticeText = nil }
                }
                .padding(.leading, 12)
                .background(c.primary)
                .task(id: message) {
                    try? await Task.sleep(for: .seconds(4))
                    if app.studio.noticeText == message { app.studio.noticeText = nil }
                }
            }
            AutobotNavBar(items: StudioRoom.allCases.map { NavItem(key: $0, label: $0.label) }, selected: room) { selected in
                if selected != room { nav.studio = selected }
            }
        }
        .background(c.background.ignoresSafeArea())
    }
}

/// `× STUDIO · IMAGE` and the project menu.
private struct StudioTopBar: View {
    let room: StudioRoom
    let session: StudioSession
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c
    @State private var naming: String?

    var body: some View {
        let studio = app.studio
        HStack(spacing: 6) {
            IconAction(symbol: "xmark", label: "Leave the Studio") { nav.leaveStudio() }
            Text("Studio").textStyle(.titleLarge).foregroundStyle(c.onBackground).lineLimit(1).fixedSize().accessibilityAddTraits(.isHeader)
            Text("· \(room.label.sentenceCased)").textStyle(.titleLarge).foregroundStyle(c.onSurfaceVariant).lineLimit(1)
            Spacer(minLength: 4)
            Menu {
                ForEach(studio.library.projects, id: \.id) { p in
                    Button { studio.selectProject(p.id) } label: {
                        if p.id == studio.projectId { Label(p.name, systemImage: "checkmark") } else { Text(p.name) }
                    }
                }
                Divider()
                Button("New project…") { naming = "" }
                if let p = studio.project { Button("Rename “\(p.name)”…") { naming = p.name } }
            } label: {
                HStack(spacing: 4) {
                    // A long name truncates at 130 pt; a short one keeps the chip snug against the
                    // trailing edge (a max-width frame outside the border left a gap after it).
                    Text(studio.project?.name ?? "Project").textStyle(.micro).lineLimit(1).foregroundStyle(c.onSurface)
                        .frame(maxWidth: 130)
                    Image(systemName: "chevron.down").symbolSize(9, weight: .semibold).foregroundStyle(c.onSurfaceVariant)
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 6)
                .overlay(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall).strokeBorder(c.outline, lineWidth: Tokens.Stroke.border))
                .fixedSize()
            }
            .accessibilityLabel("Project: \(studio.project?.name ?? "none")")
        }
        .padding(.leading, 4)
        .padding(.trailing, Tokens.Spacing.topBarPaddingEnd)
        .frame(minHeight: Tokens.Spacing.topBarMinHeight)
        // Chrome, like the tab bar: it stops growing at AX1 so the title, the room and the
        // project chip share one line.
        .dynamicTypeSize(...DynamicTypeSize.accessibility1)
        .background(c.background)
        .alert(naming == "" ? "New project" : "Rename project", isPresented: Binding(get: { naming != nil }, set: { if !$0 { naming = nil } })) {
            TextField("Name", text: Binding(get: { naming ?? "" }, set: { naming = $0 }))
            Button("Save") {
                let name = (naming ?? "").trimmingCharacters(in: .whitespaces)
                if let p = studio.project, naming != "" && !name.isEmpty && p.name != name { studio.renameProject(p.id, name) } else if !name.isEmpty { studio.createProject(name) }
                naming = nil
            }
            Button("Cancel", role: .cancel) { naming = nil }
        }
    }
}

// MARK: - Queue strip

/**
 The queue (spec §1): one chip per job, oldest first. A running job's chip is a small ink plate
 that flows with its progress and develops into the backend's preview; finished jobs print their
 result. Cancel, retry and clear sit right there. On-device work pauses in the background (iOS).
 */
struct QueueStrip: View {
    @Environment(AppContainer.self) private var app
    @Environment(\.scheme) private var c

    var body: some View {
        let studio = app.studio
        let jobs = studio.jobs.suffix(24)
        if !jobs.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    let active = studio.activeJobs.count
                    MicroLabel(active > 0 ? "queue · \(active) waiting or running" : "queue · done").frame(maxWidth: .infinity, alignment: .leading)
                    if studio.jobs.contains(where: { $0.state.isFinished }) { TextAction("Clear", color: c.primary) { studio.clearFinished() } }
                }
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(Array(jobs), id: \.id) { job in
                            JobChip(job: job)
                        }
                    }
                }
                if studio.hasRunningOnDevice {
                    MicroLabel("on-device work pauses if you leave the app (ios)", lines: 1)
                }
            }
            .padding(.horizontal, Tokens.Spacing.gutter)
            .padding(.vertical, 6)
            .background(c.surfaceContainerLow)
            .hairlineEdge()
        }
    }
}

private struct JobChip: View {
    let job: StudioJob
    @Environment(AppContainer.self) private var app
    @Environment(\.scheme) private var c
    @State private var preview: PlateImage?
    @State private var result: UIImage?
    @State private var serial = 0

    var body: some View {
        let studio = app.studio
        let state = job.state
        let previewData = studio.previews[job.id]
        HStack(spacing: 8) {
            ZStack {
                c.surfaceContainer
                InkPlate(ink: ink(state), dot: c.primary, idleDot: c.outlineVariant, plate: c.primary, cell: 4)
                if job.recipe.output == .audio && state == .done { Image(systemName: "waveform").foregroundStyle(c.primary) }
            }
            .frame(width: 40, height: 40)
            .clipped()
            VStack(alignment: .leading, spacing: 3) {
                Text(title).textStyle(.labelSmall).foregroundStyle(c.onSurface).lineLimit(1)
                Text(subtitle).textStyle(.micro).foregroundStyle(state == .failed ? c.error : c.onSurfaceVariant).lineLimit(1)
                InkProgressBar(bar(state), height: 2)
            }
            .frame(width: 118)
            switch state {
            case .queued, .running: IconAction(symbol: "xmark", label: "Cancel", size: 13) { studio.cancel(job.id) }
            case .failed, .cancelled: IconAction(symbol: "arrow.clockwise", label: "Retry", size: 13) { studio.retry(job.id) }
            default: EmptyView()
            }
        }
        .padding(.leading, 4)
        .padding(.vertical, 4)
        .padding(.trailing, state == .done ? 8 : 0)
        .background(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall).fill(c.surfaceContainerHigh))
        .stamp(state, when: { $0 == .done })
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(title), \(state.label)\(job.error.map { ": \($0)" } ?? "")")
        .task(id: previewData) {
            guard let previewData, let image = await decodeImage(previewData, maxSide: 256), let cg = image.cgImage, let grid = LumaGrid(image: cg) else { return }
            serial += 1
            preview = PlateImage(id: "\(job.id)-\(serial)", grid: grid)
        }
        .task(id: job.outputs.first) {
            guard let id = job.outputs.first, let asset = studio.library.asset(id), asset.type != .audio else { return }
            result = await studio.thumbnail(asset)
        }
    }

    private var title: String {
        let r = job.recipe
        let base = r.text("prompt") ?? r.text("text") ?? app.studio.library.workflow(r.workflowId)?.name ?? r.engine
        return job.label.map { "\(base.prefix(24)) · \($0)" } ?? base
    }

    private var subtitle: String {
        if let error = job.error, job.state == .failed { return error }
        if job.state == .running {
            if let status = job.status, job.steps == 0 { return status }
            if job.steps > 0 { return job.steps == 1000 ? "\(Int(job.progress * 100))%" : "step \(job.step)/\(job.steps)" }
            return "starting"
        }
        let where_ = job.recipe.backendId.flatMap { id in app.studio.comfyServer(id)?.name ?? app.backends.backends.first { $0.id == id }?.name } ?? "this iphone"
        return "\(job.state.label) · \(where_)"
    }

    private func bar(_ state: JobState) -> InkBarState {
        switch state {
        case .running: job.steps == 0 ? .indeterminate : .progress(Double(job.progress), running: true)
        case .done: .progress(1, running: false)
        case .failed: .error(Double(job.progress))
        case .cancelled: .paused(Double(job.progress))
        default: .progress(0, running: false)
        }
    }

    private func ink(_ state: JobState) -> PlateInk {
        if let result, let grid = LumaCache.grid(result) { return .final(PlateImage(id: job.id, grid: grid, image: result)) }
        switch state {
        case .running:
            if let preview { return .preview(preview) }
            return .flow(progress: Double(job.progress), loading: job.steps == 0)
        case .done: return .hold
        default: return .idle
        }
    }
}

// MARK: - Shared pieces

/// A room's controls: a header that folds them away (the canvas takes the room) and the content.
struct StudioPanel<Content: View>: View {
    let title: String
    @Binding var collapsed: Bool
    @ViewBuilder var content: () -> Content
    @Environment(\.scheme) private var c

    var body: some View {
        VStack(spacing: 0) {
            Button { withAnimation(.inkSlide) { collapsed.toggle() } } label: {
                HStack {
                    SectionLabel(title)
                    Image(systemName: collapsed ? "chevron.up" : "chevron.down").symbolSize(11, weight: .semibold).foregroundStyle(c.onSurfaceVariant)
                }
                .padding(.horizontal, Tokens.Spacing.gutter)
                .frame(minHeight: 36)
                .contentShape(Rectangle())
            }
            .buttonStyle(PressStyle())
            .accessibilityLabel(collapsed ? "Show controls" : "Hide controls, canvas only")
            .hairlineEdge(top: true, bottom: false)
            if !collapsed {
                ScrollView {
                    InkStack(alignment: .leading, spacing: 12) { content() }
                        .padding(.horizontal, Tokens.Spacing.gutter)
                        .padding(.bottom, 12)
                }
                .scrollDismissesKeyboard(.interactively)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .background(c.surfaceContainerLow)
    }
}

/// "runs on <backend>" — where a generation will run.
struct RunsOnTag: View {
    let label: String
    let route: Route
    @Environment(\.scheme) private var c

    var body: some View {
        HStack(spacing: 6) {
            RouteTag(route: route)
            MicroLabel(label)
        }
    }
}

/// An empty room: what is missing and the way to get it.
struct StudioEmpty: View {
    let title: String
    let text: String
    var action: String? = nil
    var onAction: (() -> Void)? = nil
    @Environment(\.scheme) private var c

    init(title: String, text: String, action: String? = nil, onAction: (() -> Void)? = nil) {
        self.title = title
        self.text = text
        self.action = action
        self.onAction = onAction
    }

    /// The shared words of an empty room (`StudioRoomWords`, the same on Android).
    init(_ words: StudioRoomWords.Empty, onAction: (() -> Void)? = nil) {
        self.init(title: words.title, text: words.text, action: onAction == nil ? nil : words.action, onAction: onAction)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            // The pixel a over the title, as Android's empty rooms.
            AutobotMark(size: 20)
            SectionLabel(title)
            Text(text).textStyle(.bodyMedium).foregroundStyle(c.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
            // Full width as on Android: a 260 pt cap cut longer labels ("Get the audio model in M…").
            if let action, let onAction { PrimaryButton(action, action: onAction) }
            // The living field gets its own band below the text (never behind it).
            Color.clear.frame(height: 40)
                .padding(.top, 6)
        }
        .padding(Tokens.Spacing.gutter)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 8)
        .padding(.top, 8)
    }
}

/// An asset's picture: image thumb, video poster, or audio waveform.
struct AssetThumb: View {
    let asset: StudioAsset
    var size: CGFloat? = nil
    @Environment(AppContainer.self) private var app
    @Environment(\.scheme) private var c
    @State private var image: UIImage?
    @State private var peaks: [Float] = []

    var body: some View {
        let _ = InkProbe.hit("body.assetThumb")
        // A recycled cell shows its cached thumbnail in the first frame (no blank flash, no reload).
        let shown = image ?? (asset.type == .audio ? nil : StudioThumbs.get(asset.id))
        ZStack {
            c.surfaceContainer
            if asset.type == .audio {
                WaveformView(peaks: peaks).padding(8)
            } else if let shown {
                Image(uiImage: shown).resizable().scaledToFill()
            }
            if asset.type == .video {
                Image(systemName: "play.fill").symbolSize(14).foregroundStyle(.white).padding(6)
                    .background(Circle().fill(.black.opacity(0.5)))
            }
        }
        .frame(width: size, height: size)
        .clipped()
        .accessibilityLabel(StudioText.describe(asset))
        .task(id: asset.id) {
            if asset.type == .audio {
                peaks = await app.studio.waveform(asset)
            } else if StudioThumbs.get(asset.id) == nil {
                InkProbe.hit("studio.thumbLoad")
                image = await app.studio.thumbnail(asset)
            }
        }
    }
}

enum StudioText {
    static func describe(_ a: StudioAsset) -> String {
        let what = a.type == .image ? "Image" : a.type == .audio ? "Audio clip" : "Video"
        let prompt = a.recipe.text("prompt") ?? a.recipe.text("text") ?? ""
        return prompt.isEmpty ? what : "\(what): \(prompt.prefix(80))"
    }

    static func meta(_ a: StudioAsset) -> String {
        var parts: [String] = []
        if a.width > 0 { parts.append("\(a.width)×\(a.height)") }
        if a.durationMs > 0 { parts.append(String(format: "%.1f s", Double(a.durationMs) / 1000)) }
        if let s = a.recipe.seedValue { parts.append("seed \(s)") }
        parts.append(a.recipe.engine)
        return parts.joined(separator: " · ")
    }
}
