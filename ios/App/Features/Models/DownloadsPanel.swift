import SwiftUI

/**
 The downloads panel (Android's `DownloadsPanel`): the running download (speed, time left), the
 queue in the order it runs, paused downloads with their reason, what blocks the queue (kill
 switch, no network), the opt-in "notify when done" switch and, once after the app was swiped
 away, why downloads stopped.
 */
struct DownloadsPanel: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @State private var reachability = NetworkReachability()
    @State private var notify = DownloadAlerts.enabled

    var body: some View {
        let state = DownloadQueue.state(
            app.library.models, progress: app.downloader.progress,
            networkOff: app.settings.settings.mode == .offline, connected: reachability.connected,
        )
        if !state.isEmpty || app.downloader.forceQuitNotice {
            Section {
                if app.downloader.forceQuitNotice {
                    HStack(alignment: .firstTextBaseline) {
                        Text("Resumed · swiping Autobot away stops downloads").font(.footnote).foregroundStyle(.tint)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        Button("Got it") { app.downloader.dismissForceQuitNotice() }.buttonStyle(.borderless)
                    }
                }
                if let block = state.blocks.first {
                    HStack(alignment: .firstTextBaseline) {
                        Label(block.label, systemImage: "exclamationmark.triangle").font(.footnote).foregroundStyle(.orange)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        if block == .killSwitch { Button("Privacy") { nav.push(.privacyCenter) }.buttonStyle(.borderless) }
                    }
                }
                if let p = state.active {
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text(p.title).font(.headline).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
                            Button { app.downloader.pause(p.id) } label: { Text("Pause").minimumHitTarget().padding(.vertical, -10) }.buttonStyle(.borderless)
                        }
                        ProgressView(value: p.fraction)
                        Text([
                            "\(formatBytes(p.downloaded)) / \(formatBytes(p.total))",
                            p.bytesPerSecond > 0 ? "\(formatBytes(p.bytesPerSecond))/s" : nil,
                            p.etaSeconds.map { "\(DownloadQueue.formatEta($0)) left" },
                        ].compactMap { $0 }.joined(separator: " · "))
                            .font(.caption).foregroundStyle(.secondary).monospacedDigit()
                    }
                }
                ForEach(Array(state.queued.enumerated()), id: \.element.id) { i, m in row(m, status: "queued · \(i + 1)") }
                ForEach(state.paused) { m in
                    row(m, status: DownloadQueue.pausedReason(m)?.label ?? m.error ?? (m.status == .failed ? "failed" : "paused"))
                }
            } header: {
                HStack {
                    InfoTitle("Downloads", info: "Downloads keep going in the background. Swiping Autobot away stops them until you open it again; they resume where they stopped.")
                    Spacer(minLength: 0)
                    Button {
                        Task { notify = await DownloadAlerts.setEnabled(!notify) }
                    } label: {
                        Image(systemName: notify ? "bell.fill" : "bell.slash").minimumHitTarget().padding(.vertical, -12)
                    }
                    .buttonStyle(.borderless)
                    .tint(notify ? Color.accentColor : .secondary)
                    .accessibilityLabel(notify ? "Notifications on" : "Notifications off")
                }
            }
        }
    }

    private func row(_ m: InstalledModel, status: String) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(m.title).lineLimit(1)
                Text([status, m.totalBytes > 0 ? "\(formatBytes(m.downloadedBytes)) / \(formatBytes(m.totalBytes))" : nil]
                    .compactMap { $0 }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(.secondary).lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Group {
                switch m.status {
                case .queued: Button { app.downloader.pause(m.id) } label: { Text("Pause").minimumHitTarget() }
                case .paused: Button { app.downloader.resume(m.id) } label: { Text("Resume").minimumHitTarget() }
                case .failed: Button { app.downloader.resume(m.id) } label: { Text("Retry").minimumHitTarget() }.tint(.red)
                default: EmptyView()
                }
            }
            .buttonStyle(.borderless)
            Button { app.downloader.remove(m.id) } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary).minimumHitTarget() }
                .buttonStyle(.borderless)
                .accessibilityLabel("Cancel")
        }
        .swipeActions(edge: .trailing) {
            Button("Cancel", role: .destructive) { app.downloader.remove(m.id) }
            switch m.status {
            case .queued: Button("Pause") { app.downloader.pause(m.id) }
            case .paused: Button("Resume") { app.downloader.resume(m.id) }.tint(.accentColor)
            case .failed: Button("Retry") { app.downloader.resume(m.id) }.tint(.orange)
            default: EmptyView()
            }
        }
        .contextMenu {
            switch m.status {
            case .queued: Button("Pause", systemImage: "pause") { app.downloader.pause(m.id) }
            case .paused: Button("Resume", systemImage: "play") { app.downloader.resume(m.id) }
            case .failed: Button("Retry", systemImage: "arrow.clockwise") { app.downloader.resume(m.id) }
            default: EmptyView()
            }
            Button("Cancel", systemImage: "xmark", role: .destructive) { app.downloader.remove(m.id) }
        }
    }
}
