import Combine
import SwiftUI
import UIKit

enum RunMode { case chat, agent, code }

/// Where a mode will run: a provider + model, labelled for the card.
struct RunTarget: Equatable {
    let providerId: String
    let model: String
    let label: String
    let route: Route
}

struct RecentRun: Identifiable, Equatable {
    let id: String
    let title: String
    let subtitle: String
    let route: Route
    let time: Int64
    var conversationId: String? = nil
    var galleryId: String? = nil
}

/// Run screen state, derived from the repositories (port of `RunViewModel.kt`).
@MainActor
struct RunState {
    var status = ""
    var live = 0
    var chat: RunTarget?
    var agent: RunTarget?
    var code: RunTarget?
    var imageLabel = "no backend"
    var imageRoute: Route?
    var lastImageId: String?
    var recent: [RecentRun] = []
    var hasAnything = true
    var studioLabel = "image · audio · video"

    init(app: AppContainer, device: DeviceProfile?) {
        let models = app.library.models
        let titles = Dictionary(models.map { ($0.id, $0.title) }, uniquingKeysWith: { a, _ in a })
        let providers = app.providers.providers
        let local = providers.first { $0.kind == .local }
        let remote = providers.filter { $0.kind != .local }
        let readyChat = models.filter(\.isLocalChat)
        let codeModel = readyChat.first { $0.kind == .code }

        func target(_ p: Provider, _ model: String? = nil) -> RunTarget {
            let m = model ?? p.defaultModel
            return RunTarget(providerId: p.id, model: m, label: titles[m] ?? (m.isEmpty ? p.displayName : m), route: routeOf(p, model: app.library.get(m)))
        }

        chat = local.map { target($0, readyChat.first { $0.kind == .chat }?.id ?? $0.defaultModel) } ?? remote.first.map { target($0) }
        agent = remote.first.map { target($0) } ?? chat
        code = (local != nil && codeModel != nil) ? target(local!, codeModel!.id) : (remote.first.map { target($0) } ?? chat)

        let backend = app.backends.startingBackend(offline: app.settings.settings.mode == .offline)
        let imageModel = models.first { $0.isReady && $0.kind == .image && $0.engine == .coreML }
        imageLabel = backend == nil ? "no backend" : backend!.kind == .onDevice ? (imageModel?.title ?? "This phone") : backend!.name
        imageRoute = backend.map { Route.imageBackend($0, model: imageModel) }

        let byId = Dictionary(providers.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        let hidden = app.chats.hidden
        let convs = app.conversations.conversations.filter { !$0.archived && !hidden.contains($0.id) }.prefix(12).map { c in
            RecentRun(
                id: "c-\(c.id)",
                title: c.title,
                subtitle: c.model.map { titles[$0] ?? $0 } ?? "—",
                route: c.providerId.flatMap { byId[$0] }.map { routeOf($0, model: c.model.flatMap { app.library.get($0) }) } ?? .cloudApi,
                time: c.updatedAt,
                conversationId: c.id,
            )
        }
        let images = app.gallery.items.prefix(12).map { g in
            RecentRun(
                id: "g-\(g.id)",
                title: g.prompt.isEmpty ? "image" : g.prompt,
                subtitle: "\(g.width)×\(g.height)",
                route: g.engine == "This phone" ? .localNpu : .pcLan,
                time: g.createdAt,
                galleryId: g.id,
            )
        }
        recent = Array((convs + images).sorted { $0.time > $1.time }.prefix(8))
        lastImageId = app.gallery.items.first?.id

        let running = app.agent.running
        let genRunning = app.generator.isBusy
        let localBusy = genRunning || running.contains { id in app.conversations.get(id)?.providerId == local?.id }
        // State only: busy or idle, and heat or low space when they matter.
        var parts = [device?.chip ?? "this phone", localBusy ? "busy" : "idle"]
        if let device, device.thermal != .nominal { parts.append(device.thermalLabel) }
        if let device, device.freeStorageBytes < 5_000_000_000 { parts.append("\(formatBytes(device.freeStorageBytes)) free") }
        status = parts.joined(separator: " · ")
        live = running.count + (genRunning ? 1 : 0) + app.studio.jobs.filter { $0.state == .running }.count
        hasAnything = !providers.isEmpty || !models.isEmpty
        let queued = app.studio.activeJobs.count
        if queued > 0 {
            studioLabel = "\(queued) queued"
        } else if let p = app.studio.project, p.name.lowercased() != "studio" {
            studioLabel = p.name.lowercased()
        }
    }
}

/// The Run screen's card grid: two columns that line up across both rows, equal gutters.
enum RunGrid {
    static let gutter: CGFloat = 8
    static let topHeight: CGFloat = 236
    static let bottomHeight: CGFloat = 108

    /// Column widths for [width]: left (Image, Code) : right (Chat/Agent, Studio) = 1.1 : 1,
    /// rounded to whole points so both rows share the same edges.
    static func columns(width: CGFloat) -> (left: CGFloat, right: CGFloat) {
        let inner = max(width - gutter, 0)
        let left = (inner * 1.1 / 2.1).rounded()
        return (left, inner - left)
    }
}

struct RunScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c
    @State private var device: DeviceProfile?
    @State private var lastImage: UIImage?

    var body: some View {
        let _ = InkProbe.hit("body.run")
        let state = RunState(app: app, device: device)
        VStack(spacing: 0) {
            AutobotTopBar {
                if state.live > 0 { CountBadge("\(state.live) LIVE") }
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    SectionLabel(state.status)
                    Spacer().frame(height: 14)
                    cards(state)
                    Spacer().frame(height: 22)
                    if !state.hasAnything {
                        PrimaryButton("Get a model") { nav.switchTo(.models) }
                        Spacer().frame(height: 24)
                    }
                    HStack(alignment: .center) {
                        MicroLabel("recent").frame(maxWidth: .infinity, alignment: .leading)
                        TextAction("All", color: c.onSurfaceVariant) { nav.push(.chatList) }
                            .accessibilityLabel("All sessions")
                    }
                    ForEach(state.recent) { run in
                        RunRow(title: run.title, subtitle: run.subtitle, action: {
                            if let id = run.conversationId { nav.push(.chat(conversationId: id)) }
                            if let id = run.galleryId { nav.push(.gallery(openId: id)) }
                        }) { RouteTag(route: run.route) }
                    }
                    if state.recent.isEmpty { MicroLabel("nothing yet").padding(.vertical, 12) }
                    Spacer().frame(height: 16)
                }
                .padding(.horizontal, Tokens.Spacing.gutter)
                .padding(.vertical, 8)
            }
        }
        .background(c.background)
        // Hardware facts once; thermal state and free space when they can have changed (no polling).
        .task { device = await Task.detached(priority: .userInitiated) { DeviceProfile.current() }.value }
        .onReceive(NotificationCenter.default.publisher(for: ProcessInfo.thermalStateDidChangeNotification).receive(on: RunLoop.main)) { _ in
            device = device?.refreshed()
        }
        .onChange(of: app.library.models.count) { _, _ in device = device?.refreshed() }
        .onChange(of: app.gallery.items.count) { _, _ in device = device?.refreshed() }
        .task(id: state.lastImageId) {
            guard let id = state.lastImageId, let bytes = await app.gallery.thumbnail(id) else {
                lastImage = nil
                return
            }
            lastImage = await decodeImage(bytes)
        }
    }

    private func cards(_ state: RunState) -> some View {
        GeometryReader { geo in
            // Two columns shared by both rows (Image : Chat/Agent = 1.1 : 1, as on Android), one
            // gutter everywhere: Code sits under Image and Studio under Chat/Agent.
            let columns = RunGrid.columns(width: geo.size.width)
            VStack(spacing: RunGrid.gutter) {
                HStack(spacing: RunGrid.gutter) {
                    // The last image, as it is, above the words; nothing when there is none.
                    if let lastImage {
                        ModeCard(index: "01", title: "Image", subtitle: state.imageLabel, route: state.imageRoute, action: { nav.push(.imagine()) }) {
                            Image(uiImage: lastImage).resizable().scaledToFill()
                        }
                        .frame(width: columns.left)
                    } else {
                        ModeCard(index: "01", title: "Image", subtitle: state.imageLabel, route: state.imageRoute) { nav.push(.imagine()) }
                            .frame(width: columns.left)
                    }
                    VStack(spacing: RunGrid.gutter) {
                        ModeCard(index: "02", title: "Chat", subtitle: state.chat?.label ?? "add a model", route: state.chat?.route, living: true) { start(.chat, state) }
                        ModeCard(index: "03", title: "Agent", subtitle: state.agent?.label ?? "add a model", route: state.agent?.route, living: true, seed: 2.1) { start(.agent, state) }
                    }
                    .frame(width: columns.right)
                }
                .frame(height: RunGrid.topHeight)
                HStack(spacing: RunGrid.gutter) {
                    ModeCard(index: "04", title: "Code", subtitle: state.code?.label ?? "add a code model", route: state.code?.route, living: true, seed: 4.2) { start(.code, state) }
                        .frame(width: columns.left)
                    ModeCard(index: "05", title: "Studio", subtitle: state.studioLabel, route: nil, living: true, seed: 5.3) { nav.enterStudio(.image) }
                        .frame(width: columns.right)
                        .accessibilityHint("Image, audio and video generation, workflows and the library")
                }
                .frame(height: RunGrid.bottomHeight)
            }
        }
        .frame(height: RunGrid.topHeight + RunGrid.gutter + RunGrid.bottomHeight)
    }

    /// Starts a session for [mode] on its target; tools on for agent and code.
    private func start(_ mode: RunMode, _ state: RunState) {
        let target: RunTarget? = switch mode {
        case .chat: state.chat
        case .agent: state.agent
        case .code: state.code
        }
        guard let target else {
            nav.switchTo(.models)
            return
        }
        let conversation = app.conversations.create(incognito: app.settings.settings.incognitoByDefault, providerId: target.providerId, model: target.model)
        app.agent.setToolsEnabled(conversation.id, mode != .chat)
        nav.push(.chat(conversationId: conversation.id))
    }
}
