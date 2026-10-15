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
            return RunTarget(providerId: p.id, model: m, label: titles[m] ?? (m.isEmpty ? p.displayName : m), route: routeOf(p))
        }

        chat = local.map { target($0, readyChat.first { $0.kind == .chat }?.id ?? $0.defaultModel) } ?? remote.first.map { target($0) }
        agent = remote.first.map { target($0) } ?? chat
        code = (local != nil && codeModel != nil) ? target(local!, codeModel!.id) : (remote.first.map { target($0) } ?? chat)

        let backend = app.backends.defaultBackend
        let imageModel = models.first { $0.isReady && $0.kind == .image && $0.engine == .coreML }
        imageLabel = backend == nil ? "no backend" : backend!.kind == .onDevice ? (imageModel?.title ?? "This phone") : backend!.name
        imageRoute = backend.map { $0.kind == .onDevice ? .localNpu : $0.isLoopback ? .localCpu : .pcLan }

        let byId = Dictionary(providers.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        let hidden = app.chats.hidden
        let convs = app.conversations.conversations.filter { !$0.archived && !hidden.contains($0.id) }.prefix(12).map { c in
            RecentRun(
                id: "c-\(c.id)",
                title: c.title,
                subtitle: "session · \(c.model.map { titles[$0] ?? $0 } ?? "—")",
                route: c.providerId.flatMap { byId[$0] }.map(routeOf) ?? .cloudApi,
                time: c.updatedAt,
                conversationId: c.id,
            )
        }
        let images = app.gallery.items.prefix(12).map { g in
            RecentRun(
                id: "g-\(g.id)",
                title: g.prompt.isEmpty ? "image" : g.prompt,
                subtitle: "image · \(g.width)×\(g.height) · \(g.durationMs / 1000) s",
                route: g.engine == "This phone" ? .localNpu : .pcLan,
                time: g.createdAt,
                galleryId: g.id,
            )
        }
        recent = Array((convs + images).sorted { $0.time > $1.time }.prefix(8))
        lastImageId = app.gallery.items.first?.id

        let running = app.agent.running
        let genRunning = app.generator.state.isRunning
        let localBusy = genRunning || running.contains { id in app.conversations.get(id)?.providerId == local?.id }
        var parts = ["this phone"]
        if let chip = device?.chip { parts.append(chip) }
        parts.append(localBusy ? "busy" : "idle")
        if let device { parts.append(device.thermalLabel) }
        if let device { parts.append("\(formatBytes(device.freeStorageBytes)) free") }
        status = parts.joined(separator: " · ")
        live = running.count + (genRunning ? 1 : 0) + app.studio.jobs.filter { $0.state == .running }.count
        hasAnything = !providers.isEmpty || !models.isEmpty
        let queued = app.studio.activeJobs.count
        if queued > 0 { studioLabel = "\(queued) in the queue" } else if let p = app.studio.project { studioLabel = p.name.lowercased() }
    }
}

struct RunScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c
    @State private var device: DeviceProfile?
    @State private var lastImage: UIImage?

    var body: some View {
        let state = RunState(app: app, device: device)
        VStack(spacing: 0) {
            AutobotTopBar {
                if state.live > 0 { CountBadge("\(state.live) LIVE") }
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    SectionLabel(state.status)
                    Spacer().frame(height: 10)
                    Text("Run\nanything.").textStyle(.displayMedium).foregroundStyle(c.onBackground)
                    Spacer().frame(height: 18)
                    cards(state)
                    Spacer().frame(height: 22)
                    if !state.hasAnything {
                        MicroLabel("no models yet — download one to run on this phone, or add a provider under remote", lines: 3)
                        Spacer().frame(height: 10)
                        PrimaryButton("Get a model") { nav.switchTo(.models) }
                        Spacer().frame(height: 22)
                    }
                    HStack(alignment: .center) {
                        MicroLabel("recent runs").frame(maxWidth: .infinity, alignment: .leading)
                        TextAction("All →", color: c.primary) { nav.push(.chatList) }
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
            .background(alignment: .top) {
                // Living paper behind the hero, fading out above the cards.
                InkGround()
                    .frame(height: 240)
                    .mask(LinearGradient(colors: [.black, .clear], startPoint: .center, endPoint: .bottom))
            }
        }
        .background(c.background)
        .task {
            while !Task.isCancelled {
                device = DeviceProfile.current()
                try? await Task.sleep(for: .seconds(5))
            }
        }
        .task(id: state.lastImageId) {
            guard let id = state.lastImageId, let bytes = await app.gallery.thumbnail(id) else {
                lastImage = nil
                return
            }
            lastImage = await decodeImage(bytes)
        }
    }

    private func cards(_ state: RunState) -> some View {
        VStack(spacing: 8) {
            GeometryReader { geo in
                // Image : (Chat / Agent) = 1.1 : 1, as on Android.
                let width = geo.size.width - 8
                HStack(spacing: 8) {
                    ModeCard(index: "01", title: "Image", subtitle: state.imageLabel, route: state.imageRoute, action: { nav.push(.imagine()) }) {
                        if let lastImage {
                            HalftoneImage(image: lastImage, cell: 7)
                        } else {
                            HalftoneField(cell: 8)
                        }
                    }
                    .frame(width: width * 1.1 / 2.1)
                    VStack(spacing: 8) {
                        ModeCard(index: "02", title: "Chat", subtitle: state.chat?.label ?? "add a model", route: state.chat?.route) { start(.chat, state) }
                        ModeCard(index: "03", title: "Agent", subtitle: state.agent?.label ?? "add a model", route: state.agent?.route) { start(.agent, state) }
                    }
                    .frame(width: width / 2.1)
                }
            }
            .frame(height: 268)
            HStack(spacing: 8) {
                ModeCard(index: "04", title: "Code", subtitle: state.code?.label ?? "add a code model", route: state.code?.route) { start(.code, state) }
                ModeCard(index: "05", title: "Studio", subtitle: state.studioLabel, route: nil) { nav.enterStudio(.image) }
                    .accessibilityHint("Image, audio and video generation, workflows and the library")
            }
            .frame(height: 100)
        }
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
