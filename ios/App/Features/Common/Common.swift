import Observation
import SwiftUI
import UIKit

/// One formatter for every relative time (creating one per row per render was most of a row's cost).
@MainActor private let relativeFormatter: RelativeDateTimeFormatter = {
    let f = RelativeDateTimeFormatter()
    f.unitsStyle = .full
    return f
}()

/// "2 min ago" style relative time (Android's `DateUtils.getRelativeTimeSpanString`), relative to [now].
@MainActor
func relativeTime(_ millis: Int64, now: Date = Date()) -> String {
    let date = Date(timeIntervalSince1970: TimeInterval(millis) / 1000)
    if abs(date.timeIntervalSince(now)) < 60 { return "just now" }
    return relativeFormatter.localizedString(for: date, relativeTo: now)
}

/**
 The current minute, observed: a view showing relative times reads [now] and re-renders once a
 minute ("just now" turns into "1 minute ago"), not only when something else changes.
 */
@MainActor
@Observable
final class MinuteTicker {
    static let shared = MinuteTicker()

    private(set) var now = Date()
    @ObservationIgnored private var task: Task<Void, Never>?

    private init() {
        task = Task { [weak self] in
            while !Task.isCancelled {
                let seconds = 60 - Date().timeIntervalSince1970.truncatingRemainder(dividingBy: 60)
                try? await Task.sleep(for: .seconds(seconds + 0.05))
                self?.now = Date()
            }
        }
    }
}

/// `12k`, `1.2M` token counts.
func compactCount(_ n: Int) -> String {
    if n >= 1_000_000 { return String(format: "%.1fM", Double(n) / 1e6) }
    if n >= 1_000 { return "\(n / 1000)k" }
    return "\(n)"
}

/// "1 session", "2 sessions" ([plural] for irregular words).
func counted(_ n: Int, _ singular: String, _ plural: String? = nil) -> String {
    "\(n) \(n == 1 ? singular : plural ?? singular + "s")"
}

/// The route of a configured provider (where its requests go). The on-device provider is
/// llama.cpp on Metal ([localChat]). A server on loopback is not this app's engine: iOS cannot keep
/// a model server running beside it, so 127.0.0.1 is a forwarded PC (or the Simulator's Mac) and
/// tags as one, like LAN hosts. Everything else is a cloud API.
func routeOf(_ p: Provider, model: InstalledModel? = nil) -> Route {
    if p.kind == .local { return Route.localChat(model) }
    if Loopback.isLoopbackURL(p.baseUrl) || isPrivateHost(p.baseUrl) { return .pcLan }
    return .cloudApi
}

/**
 Route tags name the engine that actually runs a model on this phone: Core ML image models on
 the Neural Engine (or the GPU), llama.cpp chat on Metal. "CPU" only when the user forced the
 CPU for that model (Models › Compute).
 */
extension Route {
    /// An on-device Core ML image model: the user's backend if they chose one, else where `.auto`
    /// runs it (the GPU for "original"-attention builds, the Neural Engine otherwise).
    static func onDeviceImage(_ model: InstalledModel?) -> Route {
        switch model?.backend ?? .auto {
        case .cpu: return .localCpu
        case .gpu: return .localGpu
        case .npu: return .localNpu
        case .auto: return model.map(prefersGpu) == true ? .localGpu : .localNpu
        }
    }

    /// An on-device chat model (llama.cpp): Metal unless the user forced the CPU. llama.cpp has
    /// no Neural Engine backend, so "npu" also runs on the GPU.
    static func localChat(_ model: InstalledModel?) -> Route {
        model?.backend == .cpu ? .localCpu : .localGpu
    }

    /// An image backend: on-device (with its selected model), else a PC (LAN or loopback, see [routeOf]).
    static func imageBackend(_ backend: DiffusionBackend, model: InstalledModel?) -> Route {
        if backend.kind == .onDevice { return onDeviceImage(model) }
        return .pcLan
    }

    /// Core ML packages compiled with "original" attention run on the GPU under `.auto`
    /// (`CoreMLDiffusionModel.preferredBackend`); split-einsum ones on the Neural Engine.
    private static func prefersGpu(_ model: InstalledModel) -> Bool {
        let marker = "/original/"
        return model.id.contains(":original") || model.manifest.files.contains { $0.url.contains(marker) }
    }
}

func isPrivateHost(_ url: String) -> Bool {
    let afterScheme = url.range(of: "://").map { String(url[$0.upperBound...]) } ?? url
    let host = String(afterScheme.split(separator: "/").first ?? "").split(separator: ":").first.map(String.init) ?? ""
    return host.hasPrefix("192.168.") || host.hasPrefix("10.") || host.hasSuffix(".local") || host.hasSuffix(".lan") ||
        host.range(of: #"^172\.(1[6-9]|2\d|3[01])\."#, options: .regularExpression) != nil || host.hasSuffix(".ts.net")
}

extension NetworkMode {
    var privacyStatus: PrivacyStatus {
        switch self {
        case .offline: .offline
        case .direct: .direct
        case .tor: .tor
        case .socks5: .proxy
        }
    }
}

/// Decodes image bytes (PNG/JPEG/HEIC) off the main actor, optionally downscaled.
func decodeImage(_ data: Data, maxSide: CGFloat? = nil) async -> UIImage? {
    await Task.detached(priority: .userInitiated) {
        guard let image = UIImage(data: data) else { return nil }
        guard let maxSide, max(image.size.width, image.size.height) > maxSide else { return image.preparingForDisplay() ?? image }
        let scale = maxSide / max(image.size.width, image.size.height)
        return image.preparingThumbnail(of: CGSize(width: image.size.width * scale, height: image.size.height * scale)) ?? image
    }.value
}

/// Placeholder for a screen that is not ported yet.
struct PlaceholderScreen: View {
    let title: String
    var note = "coming to ios"
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c

    var body: some View {
        VStack(spacing: 0) {
            AutobotTopBar(title: title, onBack: nav.path.isEmpty ? nil : { nav.back() })
            VStack(alignment: .leading, spacing: 10) {
                SectionLabel(title)
                MicroLabel(note)
                Spacer()
            }
            .padding(16)
        }
        .background(c.background)
    }
}

/// Riso confirmation dialog (Material AlertDialog look): title, text, actions.
struct RisoAlert: ViewModifier {
    @Binding var isPresented: Bool
    let title: String
    let message: String
    let confirm: String
    var destructive = true
    let onConfirm: () -> Void

    func body(content: Content) -> some View {
        content.alert(title, isPresented: $isPresented) {
            Button(confirm, role: destructive ? .destructive : nil, action: onConfirm)
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(message)
        }
    }
}

extension View {
    func risoAlert(_ isPresented: Binding<Bool>, title: String, message: String, confirm: String, destructive: Bool = true, onConfirm: @escaping () -> Void) -> some View {
        modifier(RisoAlert(isPresented: isPresented, title: title, message: message, confirm: confirm, destructive: destructive, onConfirm: onConfirm))
    }
}
