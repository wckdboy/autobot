import AutobotShared
import Observation
import SwiftUI

/**
 The Creative Studio (spec §1): a mode of its own. Its rooms (Image · Audio · Video · Workflows ·
 Library) are the system's tab view, a tab bar on iPhone and a sidebar on iPad and the Mac; each
 room has the system navigation bar (leave, the project menu) and the queue strip under it. The
 canvas is the hero and the controls fold away. It follows the app's light or dark theme. Leaving
 returns to the app exactly where it was; queued jobs keep going.
 */
struct StudioScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @State private var session: StudioSession?

    var body: some View {
        Group {
            if let session {
                StudioFrame(session: session)
            } else {
                Color(.systemGroupedBackground).ignoresSafeArea()
            }
        }
        .task {
            if session == nil { session = StudioSession(app: app) }
            app.studio.syncEngines(app: app)
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

extension StudioRoom {
    /// The room's SF Symbol in the tab bar / sidebar.
    var symbol: String {
        switch self {
        case .image: "photo"
        case .audio: "waveform"
        case .video: "film"
        case .workflows: "point.3.connected.trianglepath.dotted"
        case .library: "square.grid.2x2"
        }
    }
}

private struct StudioFrame: View {
    let session: StudioSession
    @Environment(Navigator.self) private var nav

    var body: some View {
        let room = Binding(get: { nav.studio ?? .image }, set: { nav.studio = $0 })
        TabView(selection: room) {
            ForEach(StudioRoom.allCases, id: \.self) { r in
                SwiftUI.Tab(r.label.sentenceCased, systemImage: r.symbol, value: r) {
                    NavigationStack {
                        RoomContent(room: r, session: session)
                            .studioChrome(room: r)
                    }
                }
            }
        }
        .tabViewStyle(.sidebarAdaptable)
        .tint(Color.brandPurple)
    }
}

private struct RoomContent: View {
    let room: StudioRoom
    let session: StudioSession

    var body: some View {
        switch room {
        case .image: ImageRoom(model: session.image, session: session)
        case .audio: AudioRoom(model: session.audio, session: session)
        case .video: VideoRoom(model: session.video, session: session)
        case .workflows: WorkflowsRoom(session: session)
        case .library: LibraryRoom(session: session)
        }
    }
}

private extension View {
    func studioChrome(room: StudioRoom) -> some View { modifier(StudioChrome(room: room)) }
}

/// The Studio's bar: leave, "Studio" with the purple dot, the project menu; the queue and the notice under it.
private struct StudioChrome: ViewModifier {
    let room: StudioRoom
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @State private var naming: String?

    func body(content: Content) -> some View {
        let studio = app.studio
        content
            .navigationTitle("Studio · \(room.label.sentenceCased)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { nav.leaveStudio() } label: { Image(systemName: "xmark") }
                        .accessibilityLabel("Leave the Studio")
                }
                ToolbarItem(placement: .principal) { TitleWithDot("Studio") }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        ForEach(studio.library.projects, id: \.id) { p in
                            Button { studio.selectProject(p.id) } label: {
                                if p.id == studio.projectId { Label(p.name, systemImage: "checkmark") } else { Text(p.name) }
                            }
                        }
                        Divider()
                        Button("New project…", systemImage: "plus") { naming = "" }
                        if let p = studio.project { Button("Rename “\(p.name)”…", systemImage: "pencil") { naming = p.name } }
                    } label: {
                        Label(studio.project?.name ?? "Project", systemImage: "folder")
                            .labelStyle(.titleAndIcon)
                            .lineLimit(1)
                            .frame(maxWidth: 160)
                    }
                    .accessibilityLabel("Project: \(studio.project?.name ?? "none")")
                }
            }
            .safeAreaInset(edge: .top, spacing: 0) {
                VStack(spacing: 0) {
                    QueueStrip()
                    if let message = studio.noticeText { NoticeLine(message: message) }
                }
            }
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

/// A short notice from the Studio ("Frame saved to the project"), gone after 4 s or on dismiss.
private struct NoticeLine: View {
    let message: String
    @Environment(AppContainer.self) private var app

    var body: some View {
        HStack(spacing: 8) {
            Text(message).font(.footnote).frame(maxWidth: .infinity, alignment: .leading)
            Button { app.studio.noticeText = nil } label: { Image(systemName: "xmark") }
                .buttonStyle(.borderless)
                .foregroundStyle(.secondary)
                .accessibilityLabel("Dismiss")
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .glassSurface(in: Capsule())
        .padding(.horizontal, 12)
        .padding(.bottom, 6)
        .accessibilityAddTraits(.updatesFrequently)
        .task(id: message) {
            try? await Task.sleep(for: .seconds(4))
            if app.studio.noticeText == message { app.studio.noticeText = nil }
        }
    }
}

// MARK: - Queue strip

/**
 The queue (spec §1): one chip per job, oldest first. A running job's chip shows the backend's
 preview as it arrives and its progress; finished jobs show their result. Cancel, retry and clear
 sit right there. On-device work pauses in the background (iOS).
 */
struct QueueStrip: View {
    @Environment(AppContainer.self) private var app

    var body: some View {
        let studio = app.studio
        let jobs = studio.jobs.suffix(24)
        if !jobs.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    let active = studio.activeJobs.count
                    Text(active > 0 ? "Queue · \(active) waiting or running" : "Queue · done")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if studio.jobs.contains(where: { $0.state.isFinished }) {
                        Button("Clear") { studio.clearFinished() }
                            .buttonStyle(.borderless)
                            .font(.caption.weight(.medium))
                    }
                }
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(Array(jobs), id: \.id) { job in
                            JobChip(job: job)
                        }
                    }
                }
                if studio.hasRunningOnDevice {
                    Text("On-device work pauses if you leave the app (iOS)")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
            .background(.bar)
        }
    }
}

private struct JobChip: View {
    let job: StudioJob
    @Environment(AppContainer.self) private var app
    @State private var preview: UIImage?
    @State private var result: UIImage?

    var body: some View {
        let studio = app.studio
        let state = job.state
        let previewData = studio.previews[job.id]
        HStack(spacing: 8) {
            ZStack {
                Color(.tertiarySystemFill)
                if let image = result ?? (state == .running ? preview : nil) {
                    Image(uiImage: image).resizable().scaledToFill()
                } else if state == .running {
                    ProgressView().controlSize(.small)
                }
                if job.recipe.output == .audio && state == .done { Image(systemName: "waveform").foregroundStyle(.tint) }
            }
            .frame(width: 40, height: 40)
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.caption.weight(.medium)).lineLimit(1)
                Text(subtitle)
                    .font(.caption2)
                    .foregroundStyle(state == .failed ? AnyShapeStyle(.red) : AnyShapeStyle(.secondary))
                    .lineLimit(1)
                progress(state)
            }
            .frame(width: 118, alignment: .leading)
            switch state {
            case .queued, .running:
                Button { studio.cancel(job.id) } label: { Image(systemName: "xmark") }
                    .buttonStyle(.borderless)
                    .foregroundStyle(.secondary)
                    .accessibilityLabel("Cancel")
            case .failed, .cancelled:
                Button { studio.retry(job.id) } label: { Image(systemName: "arrow.clockwise") }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("Retry")
            default: EmptyView()
            }
        }
        .padding(.leading, 4)
        .padding(.vertical, 4)
        .padding(.trailing, state == .done ? 8 : 6)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Color(.secondarySystemGroupedBackground)))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(title), \(state.label)\(job.error.map { ": \($0)" } ?? "")")
        .task(id: previewData) {
            guard let previewData, let image = await decodeImage(previewData, maxSide: 256) else { return }
            preview = image
        }
        .task(id: job.outputs.first) {
            guard let id = job.outputs.first, let asset = studio.library.asset(id), asset.type != .audio else { return }
            result = await studio.thumbnail(asset)
        }
    }

    @ViewBuilder private func progress(_ state: JobState) -> some View {
        switch state {
        case .running where job.steps > 0:
            ProgressView(value: Double(job.progress)).progressViewStyle(.linear)
        case .done:
            ProgressView(value: 1).progressViewStyle(.linear)
        case .failed:
            ProgressView(value: Double(job.progress)).progressViewStyle(.linear).tint(.red)
        case .cancelled:
            ProgressView(value: Double(job.progress)).progressViewStyle(.linear).tint(.secondary)
        default:
            ProgressView(value: 0).progressViewStyle(.linear)
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
}

// MARK: - Shared pieces

/**
 A generating room (image, audio, video): the canvas and the controls (a Form), the run bar
 floating over the bottom. On a phone the canvas sits over the controls; in a wide window they
 stand side by side. The toolbar folds the controls away (the canvas takes the room).
 */
struct StudioRoomLayout<Canvas: View, Controls: View, RunBar: View>: View {
    @Binding var collapsed: Bool
    @ViewBuilder var canvas: () -> Canvas
    @ViewBuilder var controls: () -> Controls
    @ViewBuilder var runBar: () -> RunBar
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        Group {
            if sizeClass == .regular {
                HStack(spacing: 0) {
                    canvas().frame(maxWidth: .infinity, maxHeight: .infinity)
                    if !collapsed {
                        form.frame(minWidth: 320, idealWidth: 400, maxWidth: 440)
                            .transition(.move(edge: .trailing).combined(with: .opacity))
                    }
                }
            } else {
                VStack(spacing: 0) {
                    canvas().frame(minHeight: 170, maxHeight: .infinity)
                    if !collapsed {
                        form.frame(maxHeight: 340)
                            // The controls get their height first; the canvas takes the rest.
                            .layoutPriority(1)
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                }
            }
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .safeAreaInset(edge: .bottom, spacing: 0) { runBar() }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { withAnimation(.smooth) { collapsed.toggle() } } label: {
                    Image(systemName: collapsed ? "slider.horizontal.3" : "rectangle.expand.vertical")
                }
                .accessibilityLabel(collapsed ? "Show controls" : "Hide controls, canvas only")
            }
        }
    }

    private var form: some View {
        Form { controls() }
            .scrollDismissesKeyboard(.interactively)
            .contentMargins(.top, 8, for: .scrollContent)
    }
}

/// The bar under a generating room: what will run (or what is missing) and the run button.
struct StudioRunBar<Actions: View>: View {
    let status: String
    @ViewBuilder var actions: () -> Actions

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(status)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
            HStack(spacing: 8) { actions() }
                .controlSize(.large)
        }
        .padding(12)
        .glassSurface()
        .readableWidth(640)
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
    }
}

/// "runs on <backend>" — where a generation will run.
struct RunsOnTag: View {
    let label: String
    let route: Route

    var body: some View {
        HStack(spacing: 6) {
            RouteBadge(route: route)
            Text(label).font(.caption).foregroundStyle(.secondary).lineLimit(1)
        }
    }
}

/// An empty room: the pixel a, what is missing and the way to get it.
struct StudioEmpty: View {
    let title: String
    let text: String
    var action: String? = nil
    var onAction: (() -> Void)? = nil

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
        MarkUnavailable(title.sentenceCased, description: text) {
            if let action, let onAction {
                Button(action, action: onAction).glassProminentButton()
            }
        }
    }
}

/// An asset's picture: image thumb, video poster, or audio waveform.
struct AssetThumb: View {
    let asset: StudioAsset
    var size: CGFloat? = nil
    @Environment(AppContainer.self) private var app
    @State private var image: UIImage?
    @State private var peaks: [Float] = []

    var body: some View {
        let _ = InkProbe.hit("body.assetThumb")
        // A recycled cell shows its cached thumbnail in the first frame (no blank flash, no reload).
        let shown = image ?? (asset.type == .audio ? nil : StudioThumbs.get(asset.id))
        ZStack {
            Color(.tertiarySystemFill)
            if asset.type == .audio {
                WaveformView(peaks: peaks).padding(8)
            } else if let shown {
                Image(uiImage: shown).resizable().scaledToFill()
            }
            if asset.type == .video {
                Image(systemName: "play.fill").font(.caption).foregroundStyle(.white).padding(6)
                    .background(Circle().fill(.black.opacity(0.5)))
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
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

/// A selectable capsule chip (backends, sizes, filters): tinted while [active], a button.
struct StudioChip: View {
    let text: String
    var active = false
    let action: () -> Void

    init(_ text: String, active: Bool = false, action: @escaping () -> Void) {
        self.text = text
        self.active = active
        self.action = action
    }

    var body: some View {
        Button(action: action) { Text(text).chipLook(active: active) }
            .buttonStyle(.plain)
            .accessibilityAddTraits(active ? .isSelected : [])
    }
}
