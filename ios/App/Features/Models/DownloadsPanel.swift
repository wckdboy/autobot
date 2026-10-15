import SwiftUI

/**
 The downloads panel (Android's `DownloadsPanel`): the running download (speed, time left), the
 queue in the order it runs, paused downloads with their reason, what blocks the queue (kill
 switch, no network), the opt-in "notify when done" switch and, once after the app was swiped
 away, why downloads stopped.
 */
struct DownloadsPanel: View {
    @Environment(AppContainer.self) private var app
    @Environment(\.scheme) private var c
    @State private var reachability = NetworkReachability()
    @State private var notify = DownloadAlerts.enabled

    var body: some View {
        let state = DownloadQueue.state(
            app.library.models, progress: app.downloader.progress,
            networkOff: app.settings.settings.mode == .offline, connected: reachability.connected,
        )
        if !state.isEmpty || app.downloader.forceQuitNotice {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    MicroLabel("downloads", color: c.primary).frame(maxWidth: .infinity, alignment: .leading)
                    Tag(notify ? "notify on" : "notify off", accent: notify ? c.primary : c.onSurfaceVariant, filled: notify) {
                        Task { notify = await DownloadAlerts.setEnabled(!notify) }
                    }
                }
                if app.downloader.forceQuitNotice {
                    HStack(alignment: .top) {
                        MicroLabel("Swiping Autobot away stops downloads; leave it in the background and they continue. They resumed where they stopped.", color: c.primary, lines: 3)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        Tag("got it") { app.downloader.dismissForceQuitNotice() }
                    }
                }
                if let block = state.block { MicroLabel(block.label, color: AutobotColors.amber, lines: 2) }
                if let p = state.active {
                    HStack(alignment: .center) {
                        Text(p.title).textStyle(.titleMedium).foregroundStyle(c.onSurface).lineLimit(1)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        TextAction("Pause", color: c.primary) { app.downloader.pause(p.id) }
                    }
                    ProgressLine(fraction: p.fraction, running: true)
                    MicroLabel([
                        "\(formatBytes(p.downloaded)) / \(formatBytes(p.total))",
                        p.bytesPerSecond > 0 ? "\(formatBytes(p.bytesPerSecond))/s" : nil,
                        p.etaSeconds.map { "\(DownloadQueue.formatEta($0)) left" },
                    ].compactMap { $0 }.joined(separator: " · "))
                }
                ForEach(Array(state.queued.enumerated()), id: \.element.id) { i, m in row(m, status: "queued · \(i + 1)") }
                ForEach(state.paused) { m in
                    row(m, status: DownloadQueue.pausedReason(m)?.label ?? m.error ?? (m.status == .failed ? "failed" : "paused"))
                }
                if state.active != nil || !state.queued.isEmpty {
                    MicroLabel("keeps going in the background · swiping autobot away stops it until you open it again", lines: 2)
                }
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall).fill(c.surfaceContainerHigh))
            .padding(.bottom, 14)
        }
    }

    private func row(_ m: InstalledModel, status: String) -> some View {
        HStack(spacing: 6) {
            VStack(alignment: .leading, spacing: 2) {
                Text(m.title).textStyle(.bodyMedium).foregroundStyle(c.onSurface).lineLimit(1)
                MicroLabel([status, m.totalBytes > 0 ? "\(formatBytes(m.downloadedBytes)) / \(formatBytes(m.totalBytes))" : nil]
                    .compactMap { $0 }.joined(separator: " · "), lines: 2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            switch m.status {
            case .queued: Tag("pause") { app.downloader.pause(m.id) }
            case .paused: Tag("resume", accent: c.primary) { app.downloader.resume(m.id) }
            case .failed: Tag("retry", accent: c.error) { app.downloader.resume(m.id) }
            default: EmptyView()
            }
            Tag("cancel") { app.downloader.remove(m.id) }
        }
    }
}
