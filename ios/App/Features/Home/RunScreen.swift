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

/**
 Home (port of `RunScreen.kt`): the device's state, the paired node's digest, a tile per mode
 (Image, Chat, Agent, Code, Studio) and the recent runs, as a system list. The tiles reflow from
 two columns on a phone to as many as fit on a wide window.
 */
struct RunScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @State private var device: DeviceProfile?
    @State private var lastImage: UIImage?
    @Environment(\.dynamicTypeSize) private var dynamicType
    @State private var digest = NodeDigestModel()

    var body: some View {
        let _ = RenderProbe.hit("body.run")
        let state = RunState(app: app, device: device)
        List {
            Section {
                // What the paired node did meanwhile; nothing when there is no node or nothing new.
                if let card = digest.card {
                    NodeDigestCard(
                        state: card,
                        onNeedsYou: {
                            digest.markSeen()
                            // Approvals hold a turn: the agent, in their conversation when they share one. Else the board's questions.
                            if card.digest.opensBoard {
                                nav.push(.nodeAgent(providerId: card.providerId, open: .board))
                            } else {
                                nav.push(.nodeAgent(providerId: card.providerId, session: card.digest.approvalSession))
                            }
                        },
                        onDetails: {
                            digest.markSeen()
                            nav.push(.nodeAgent(providerId: card.providerId, open: .activity))
                        },
                        onDismiss: { digest.markSeen() },
                    )
                    .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 12, trailing: 0))
                    .listRowBackground(Color.clear)
                }
                tiles(state)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                if !state.hasAnything {
                    Button("Get a model") { nav.switchTo(.models) }
                        .glassProminentButton()
                        .controlSize(.large)
                        .frame(maxWidth: .infinity)
                        .listRowInsets(EdgeInsets(top: 12, leading: 0, bottom: 0, trailing: 0))
                        .listRowBackground(Color.clear)
                }
            } header: {
                Text(state.status).textCase(nil)
            }
            // Always there: "All" is the way to every chat, archived ones too, even with nothing
            // recent. No empty paragraph under it (as on Android).
            Section {
                ForEach(state.recent) { run in
                    Button {
                        if let id = run.conversationId { nav.push(.chat(conversationId: id)) }
                        if let id = run.galleryId { nav.push(.gallery(openId: id)) }
                    } label: {
                        RecentRunRow(run: run)
                    }
                    .foregroundStyle(.primary)
                }
            } header: {
                HStack {
                    Text("Recent runs").textCase(nil)
                    Spacer()
                    Button { nav.push(.chatList) } label: { Text("All").headerActionTarget() }
                        .font(.subheadline)
                        .textCase(nil)
                        .accessibilityLabel("All chats")
                }
            }
        }
        .listStyle(.insetGrouped)
        .toolbar {
            ToolbarItem(placement: .principal) { Wordmark() }
            if state.live > 0 {
                ToolbarItem(placement: .topBarTrailing) { CapsuleTag("\(state.live) live") }
            }
        }
        .navigationTitle("Autobot")
        .navigationBarTitleDisplayMode(.inline)
        // Hardware facts once; thermal state and free space when they can have changed (no polling).
        .task { device = await Task.detached(priority: .userInitiated) { DeviceProfile.current() }.value }
        // Each time Run shows: what the node did since the owner last looked.
        // (again when a node is paired or unpaired).
        .task(id: NodeDigestModel.pairedNode(app)?.id) { await digest.refresh(node: NodeDigestModel.pairedNode(app), fetch: NodeDigestModel.fetch(app)) }
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

    /// The mode tiles: two columns on a phone, more as the window widens, one at accessibility sizes.
    private func tiles(_ state: RunState) -> some View {
        let columns = dynamicType.isAccessibilitySize
            ? [GridItem(.flexible())]
            : [GridItem(.adaptive(minimum: 160), spacing: 12)]
        return LazyVGrid(columns: columns, spacing: 12) {
            ModeTile(title: "Image", subtitle: state.imageLabel, symbol: "photo", route: state.imageRoute, image: lastImage) { nav.push(.imagine()) }
            ModeTile(title: "Chat", subtitle: state.chat?.label ?? "add a model", symbol: "bubble.left.and.bubble.right", route: state.chat?.route) { start(.chat, state) }
            ModeTile(title: "Agent", subtitle: state.agent?.label ?? "add a model", symbol: "wand.and.sparkles", route: state.agent?.route) { start(.agent, state) }
            ModeTile(title: "Code", subtitle: state.code?.label ?? "add a code model", symbol: "chevron.left.forwardslash.chevron.right", route: state.code?.route) { start(.code, state) }
            ModeTile(title: "Studio", subtitle: state.studioLabel, symbol: "square.stack.3d.up", route: nil) { nav.enterStudio(.image) }
                .accessibilityHint("Image, audio and video generation, workflows and the library")
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

/// A mode on the Run screen: its symbol (or the last image), where it runs, its name and model.
private struct ModeTile: View {
    let title: String
    let subtitle: String
    let symbol: String
    let route: Route?
    var image: UIImage? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .top) {
                    if let image {
                        Image(uiImage: image).resizable().scaledToFill()
                            .frame(width: 36, height: 36)
                            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                            .accessibilityHidden(true)
                    } else {
                        Image(systemName: symbol)
                            .font(.title3)
                            .foregroundStyle(.tint)
                            .frame(height: 36)
                            .accessibilityHidden(true)
                    }
                    Spacer(minLength: 8)
                    if let route { RouteBadge(route: route) }
                }
                Spacer(minLength: 0)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.headline).foregroundStyle(.primary).lineLimit(1)
                    if subtitle.lowercased() != title.lowercased() {
                        Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1).truncationMode(.tail)
                    }
                }
            }
            .padding(14)
            .frame(maxWidth: .infinity, minHeight: 116, alignment: .topLeading)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
    }
}

/// A recent chat or image: title, model or size, and where it ran.
private struct RecentRunRow: View {
    let run: RecentRun

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(run.title).font(.body).lineLimit(1)
                if !run.subtitle.isEmpty {
                    Text(run.subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            RouteBadge(route: run.route)
        }
        .contentShape(Rectangle())
    }
}
