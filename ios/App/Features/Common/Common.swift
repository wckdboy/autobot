import SwiftUI
import UIKit

/// "2 min ago" style relative time (Android's `DateUtils.getRelativeTimeSpanString`).
func relativeTime(_ millis: Int64) -> String {
    let date = Date(timeIntervalSince1970: TimeInterval(millis) / 1000)
    if abs(date.timeIntervalSinceNow) < 60 { return "just now" }
    let f = RelativeDateTimeFormatter()
    f.unitsStyle = .full
    return f.localizedString(for: date, relativeTo: Date())
}

/// `12k`, `1.2M` token counts.
func compactCount(_ n: Int) -> String {
    if n >= 1_000_000 { return String(format: "%.1fM", Double(n) / 1e6) }
    if n >= 1_000 { return "\(n / 1000)k" }
    return "\(n)"
}

/// The route of a configured provider (where its requests go).
func routeOf(_ p: Provider) -> Route {
    if p.kind == .local { return .localGpu }
    if Loopback.isLoopbackURL(p.baseUrl) { return .localCpu }
    if isPrivateHost(p.baseUrl) { return .pcLan }
    return .cloudApi
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
