import SwiftUI

/// One audit log line as the Privacy Center shows it (port of `PrivacyCenterViewModel.AuditRow`).
struct AuditRow: Identifiable, Equatable {
    let id: Int64
    let timeMillis: Int64
    let method: String
    let host: String
    let path: String
    let detail: String
    let blocked: Bool

    init(_ e: AuditEntry) {
        id = e.id
        timeMillis = e.timeMillis
        method = e.method
        host = e.host
        path = e.path
        var parts = [e.route]
        if e.count > 1 { parts.append("×\(e.count)") }
        if let tag = e.tag { parts.append(tag) }
        if let status = e.status { parts.append("HTTP \(status)") }
        if let sent = e.bytesSent { parts.append("↑\(sent)B") }
        if let received = e.bytesReceived { parts.append("↓\(received)B") }
        if let error = e.error { parts.append(error) }
        detail = parts.joined(separator: " · ")
        blocked = e.blocked
    }
}

private enum ModeKind: CaseIterable {
    case offline, direct, tor, socks5

    var label: String {
        switch self {
        case .offline: "Offline"
        case .direct: "Direct"
        case .tor: "Tor"
        case .socks5: "SOCKS5"
        }
    }

    var description: String {
        switch self {
        case .offline: "Nothing leaves the phone"
        case .direct: "Straight to providers"
        case .tor: "Through Orbot · 127.0.0.1:9050"
        case .socks5: "Through your proxy"
        }
    }

    init(_ mode: NetworkMode) {
        switch mode {
        case .offline: self = .offline
        case .direct: self = .direct
        case .tor: self = .tor
        case .socks5: self = .socks5
        }
    }
}

/// Privacy Center (port of `PrivacyCenterScreen.kt`): network mode, loopback, live audit log.
/// Panic wipe lives in Settings › Privacy & security (one home per thing).
struct PrivacyCenterScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c
    @State private var audit: [AuditRow] = []

    var body: some View {
        let settings = app.settings.settings
        VStack(spacing: 0) {
            AutobotTopBar(title: "Privacy Center", onBack: { nav.back() })
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    header("Network mode")
                    NetworkModeSelector(mode: settings.mode) { app.settings.setNetworkMode($0) }
                    SwitchRow(
                        title: "Loopback offline",
                        isOn: Binding(get: { settings.allowLoopbackWhenOffline }, set: { app.settings.setAllowLoopbackWhenOffline($0) }),
                        info: "Lets on-device servers (e.g. Ollama at 127.0.0.1) work in Offline mode.",
                    )
                    HStack(spacing: 0) {
                        header("Audit log · \(audit.count)")
                        InfoButton(title: "Audit log", text: "Every outbound request attempt, kept in memory only. Headers, queries and bodies are never recorded.")
                            .padding(.top, 8)
                        Spacer()
                        if !audit.isEmpty { TextAction("Clear", color: c.primary) { app.auditLog.clear() }.padding(.top, 8).padding(.trailing, 8) }
                    }
                    if audit.isEmpty {
                        Text("No requests").textStyle(.bodyMedium).foregroundStyle(c.onSurfaceVariant).padding(Tokens.Spacing.gutter)
                    }
                    ForEach(audit) { AuditRowView(row: $0) }
                }
                .padding(.bottom, 32)
            }
        }
        .background(c.background)
        .task {
            for await entries in app.auditLog.updates() {
                audit = entries.reversed().map(AuditRow.init)
            }
        }
    }

    private func header(_ text: String) -> some View {
        SectionLabel(text).padding(.horizontal, Tokens.Spacing.gutter).padding(.top, 16).padding(.bottom, 4)
    }
}

private struct NetworkModeSelector: View {
    let mode: NetworkMode
    let onChange: (NetworkMode) -> Void
    @Environment(\.scheme) private var c
    @State private var torHost = NetworkMode.defaultTorHost
    @State private var torPort = "\(NetworkMode.defaultTorPort)"
    @State private var socksHost = NetworkPolicy.defaultSocksHost
    @State private var socksPort = "\(NetworkPolicy.defaultSocksPort)"
    /// Set when nothing answers at the selected proxy (the mode still applies).
    @State private var proxyWarning: String?

    var body: some View {
        let current = ModeKind(mode)
        VStack(alignment: .leading, spacing: 0) {
            ForEach(ModeKind.allCases, id: \.self) { kind in
                Button { if let m = build(kind) { onChange(m) } } label: {
                    HStack(spacing: 12) {
                        RadioMark(selected: kind == current)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(kind.label).textStyle(.bodyLarge).foregroundStyle(c.onSurface)
                            // Only the chosen mode says what it does (state, not a manual).
                            if kind == current {
                                Text(kind.description).textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).multilineTextAlignment(.leading)
                            }
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, Tokens.Spacing.gutter)
                    .padding(.vertical, 8)
                    .frame(minHeight: 52)
                    .contentShape(Rectangle())
                }
                .buttonStyle(PressStyle())
                .accessibilityAddTraits(kind == current ? .isSelected : [])
                if kind == current && (kind == .tor || kind == .socks5) {
                    let isTor = kind == .tor
                    HStack(alignment: .bottom, spacing: 8) {
                        ConsoleTextField(text: isTor ? $torHost : $socksHost, label: "Host", kind: .url)
                        ConsoleTextField(text: isTor ? $torPort : $socksPort, label: "Port", kind: .number).frame(width: 96)
                        let candidate = build(kind)
                        GhostButton("Apply", accent: c.primary, enabled: candidate != nil && candidate != mode) {
                            if let candidate { onChange(candidate) }
                        }
                        .frame(width: 88)
                    }
                    .padding(.leading, 52)
                    .padding(.trailing, Tokens.Spacing.gutter)
                    .padding(.bottom, 8)
                    if let proxyWarning {
                        MicroLabel(proxyWarning, color: c.error, lines: 2)
                            .padding(.leading, 52)
                            .padding(.trailing, Tokens.Spacing.gutter)
                            .padding(.bottom, 8)
                    }
                }
            }
        }
        .onAppear(perform: syncFields)
        .onChange(of: mode) { _, _ in syncFields() }
        .task(id: mode) { await probe(mode) }
    }

    /// Tor / SOCKS5: checks something listens at the proxy so a stopped Orbot is named, not a generic connect error.
    private func probe(_ mode: NetworkMode) async {
        proxyWarning = nil
        let endpoint: (String, Int)? = switch mode {
        case .tor(let h, let p), .socks5(let h, let p): (h, p)
        default: nil
        }
        guard case let (host, port)? = endpoint else { return }
        let reachable = await ProxyProbe.reachable(host: host, port: port)
        guard !reachable, !Task.isCancelled else { return }
        proxyWarning = "No SOCKS proxy at \(host):\(port) — start Orbot or check the port"
    }

    private func syncFields() {
        if case .tor(let h, let p) = mode { torHost = h; torPort = "\(p)" }
        if case .socks5(let h, let p) = mode { socksHost = h; socksPort = "\(p)" }
    }

    private func build(_ kind: ModeKind) -> NetworkMode? {
        func endpoint(_ host: String, _ port: String) -> (String, Int)? {
            let h = host.trimmingCharacters(in: .whitespaces)
            guard !h.isEmpty, let p = Int(port.filter(\.isNumber)), (1 ... 65535).contains(p) else { return nil }
            return (h, p)
        }
        switch kind {
        case .offline: return .offline
        case .direct: return .direct
        case .tor: return endpoint(torHost, torPort).map { .tor(host: $0.0, port: $0.1) }
        case .socks5: return endpoint(socksHost, socksPort).map { .socks5(host: $0.0, port: $0.1) }
        }
    }
}

/// Material-style radio indicator in riso colours.
struct RadioMark: View {
    let selected: Bool
    @Environment(\.scheme) private var c

    var body: some View {
        ZStack {
            Circle().strokeBorder(selected ? c.primary : c.onSurfaceVariant, lineWidth: 2).frame(width: 20, height: 20)
            if selected { Circle().fill(c.primary).frame(width: 10, height: 10) }
        }
        .accessibilityHidden(true)
    }
}

private struct AuditRowView: View {
    let row: AuditRow
    @Environment(\.scheme) private var c
    private static let time: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }()

    var body: some View {
        let accent = row.blocked ? c.error : AutobotColors.green
        HStack(alignment: .top, spacing: 10) {
            Text(row.blocked ? "BLOCKED" : row.method)
                .textStyle(.labelSmall, weight: 700)
                .foregroundStyle(accent)
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall).fill(accent.opacity(0.15)))
                .padding(.top, 2)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.host + row.path).textStyle(.codeBlock).foregroundStyle(c.onSurface).lineLimit(2)
                Text(Self.time.string(from: Date(timeIntervalSince1970: TimeInterval(row.timeMillis) / 1000)) + " · " + row.detail)
                    .textStyle(.labelSmall)
                    .foregroundStyle(c.onSurfaceVariant)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, Tokens.Spacing.gutter)
        .padding(.vertical, 6)
    }
}
