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
        // Android's words (PrivacyCenterScreen.kt).
        case .offline: "Kill switch on. Nothing leaves the device."
        case .direct: "Connect to providers directly."
        case .tor: "Route through Orbot's SOCKS port (remote DNS)."
        case .socks5: "Route through a custom SOCKS5 proxy."
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
    @State private var audit: [AuditRow] = []

    var body: some View {
        let settings = app.settings.settings
        VStack(spacing: 0) {
            AutobotTopBar(title: "Privacy Center", onBack: { nav.back() })
            List {
                Section("Network mode") {
                    NetworkModeSelector(mode: settings.mode) { app.settings.setNetworkMode($0) }
                    InfoToggle(
                        title: "Loopback when offline",
                        info: "Lets on-device servers (e.g. Ollama at 127.0.0.1) work in Offline mode.",
                        isOn: Binding(get: { settings.allowLoopbackWhenOffline }, set: { app.settings.setAllowLoopbackWhenOffline($0) }),
                    )
                }
                Section {
                    if audit.isEmpty {
                        Text("No requests yet.").foregroundStyle(.secondary)
                    }
                    ForEach(audit) { AuditRowView(row: $0) }
                } header: {
                    HStack {
                        InfoTitle("Audit log · \(audit.count)", info: "Every outbound request attempt, kept in memory only. Headers, queries and bodies are never recorded.")
                        Spacer()
                        if !audit.isEmpty {
                            Button("Clear") { app.auditLog.clear() }
                                .font(.subheadline)
                                .textCase(nil)
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
        }
        .task {
            for await entries in app.auditLog.updates() {
                audit = entries.reversed().map(AuditRow.init)
            }
        }
    }
}

private struct NetworkModeSelector: View {
    let mode: NetworkMode
    let onChange: (NetworkMode) -> Void
    @State private var torHost = NetworkMode.defaultTorHost
    @State private var torPort = "\(NetworkMode.defaultTorPort)"
    @State private var socksHost = NetworkPolicy.defaultSocksHost
    @State private var socksPort = "\(NetworkPolicy.defaultSocksPort)"
    /// Set when nothing answers at the selected proxy (the mode still applies).
    @State private var proxyWarning: String?

    var body: some View {
        let current = ModeKind(mode)
        ForEach(ModeKind.allCases, id: \.self) { kind in
            Button { if let m = build(kind) { onChange(m) } } label: {
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(kind.label).foregroundStyle(.primary)
                        // One line each, as on Android: what a mode does decides the choice.
                        Text(kind.description).font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.leading)
                    }
                    Spacer(minLength: 0)
                    if kind == current {
                        Image(systemName: "checkmark").font(.body.weight(.semibold)).foregroundStyle(.tint)
                            .accessibilityHidden(true)
                    }
                }
                .contentShape(Rectangle())
            }
            .foregroundStyle(.primary)
            .accessibilityAddTraits(kind == current ? .isSelected : [])
            if kind == current && (kind == .tor || kind == .socks5) {
                let isTor = kind == .tor
                FormField("Host", text: isTor ? $torHost : $socksHost, prompt: "Host", kind: .url)
                FormField("Port", text: isTor ? $torPort : $socksPort, prompt: "Port", kind: .number)
                let candidate = build(kind)
                Button("Apply") { if let candidate { onChange(candidate) } }
                    .disabled(candidate == nil || candidate == mode)
                if let proxyWarning {
                    Text(proxyWarning).font(.footnote).foregroundStyle(.red).lineLimit(2)
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

private struct AuditRowView: View {
    let row: AuditRow
    private static let time: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }()

    var body: some View {
        // The method as a quiet tag; a blocked request says so with the glyph, in red (words
        // first, then a glyph), as on Android.
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            TintTag(row.blocked ? "✗ blocked" : row.method, color: row.blocked ? .red : .secondary)
            VStack(alignment: .leading, spacing: 2) {
                // Host and path are code: monospaced.
                Text(row.host + row.path).font(.callout.monospaced()).lineLimit(2)
                Text(Self.time.string(from: Date(timeIntervalSince1970: TimeInterval(row.timeMillis) / 1000)) + " · " + row.detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
