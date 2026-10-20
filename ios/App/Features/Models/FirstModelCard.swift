import AutobotShared
import SwiftUI

/// What the first-run card shows (the shared `FirstModel` rules), worked out from the library.
struct FirstModelState: Equatable {
    let plan: ModelPlan?
    let step: FirstModelStep
    let downloaded: Int64
    let error: String?

    /// [models] the library's rows, [progress] the running download, [hasChats] any chat exists.
    static func compute(device: DeviceCapabilities, models: [InstalledModel], progress: DownloadProgress?, hasChats: Bool) -> FirstModelState {
        let id = FirstModel.shared.pickId(device: device, plans: AutobotShared.Catalog.shared.chatModels)
        let plan = id.flatMap { id in Catalog.chat.first { $0.id == id } }
        let row = plan.flatMap { p in models.first { $0.id == p.id } }
        let localChat = models.contains { $0.isLocalChat && ($0.kind == .chat || $0.kind == .code) }
        let step = FirstModel.shared.step(picked: plan != nil, status: row?.status.rawValue, localChat: localChat, hasChats: hasChats)
        let live = progress.flatMap { $0.id == plan?.id ? $0.downloaded : nil }
        return FirstModelState(plan: plan, step: step, downloaded: live ?? row?.downloadedBytes ?? 0, error: row?.error)
    }
}

/**
 The first-run card at the top of Models ("Start here"): one chat model that fits this phone, its
 size, that it runs offline, and the one next action — Download, Pause / Resume, Retry, then
 Start a chat. The same card as Android's `FirstModelCard`.
 */
struct FirstModelCard: View {
    let state: FirstModelState
    let ramBytes: Int64
    /// Offline mode is on: the download needs the network once (the kill switch blocks it).
    var offline = false
    /// Sets the network mode: true = Direct (to download), false = back to Offline.
    var onNetwork: (Bool) -> Void = { _ in }
    let onDownload: () -> Void
    let onPause: () -> Void
    let onResume: () -> Void
    let onStart: () -> Void
    @Environment(\.scheme) private var c
    private let words = FirstModel.shared

    var body: some View {
        if state.step != .hidden {
            VStack(alignment: .leading, spacing: 8) {
                SectionLabel(words.TITLE)
                if let plan = state.plan {
                    Text(plan.title).textStyle(.titleMedium).foregroundStyle(c.onSurface)
                    MicroLabel(words.sizeLine(sizeBytes: plan.totalBytes, ramBytes: ramBytes))
                    Text(sentence(plan))
                        .textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                    action(plan)
                } else {
                    Text(words.NONE_FITS).textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall).fill(c.surfaceContainer))
            .overlay(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall).strokeBorder(c.outlineVariant, lineWidth: 1))
            .padding(.bottom, 14)
        }
    }

    private var blocked: Bool { offline && (state.step == .offer || state.step == .failed) }

    private func sentence(_ plan: ModelPlan) -> String {
        if state.step == .ready { return words.readyLine(title: plan.title) }
        return blocked ? words.OFFLINE_FIRST : words.OFFLINE
    }

    @ViewBuilder private func action(_ plan: ModelPlan) -> some View {
        let total = plan.totalBytes
        switch state.step {
        case .offer:
            if offline {
                PrimaryButton(words.ALLOW_AND_DOWNLOAD) { onNetwork(true); onDownload() }
            } else {
                PrimaryButton(words.DOWNLOAD, action: onDownload)
            }
        case .queued, .downloading:
            progress(total)
            TextAction(words.PAUSE, color: c.onSurfaceVariant, action: onPause)
        case .paused:
            progress(total)
            PrimaryButton(words.RESUME, action: onResume)
        case .failed:
            if let error = state.error {
                Text(error).textStyle(.bodySmall).foregroundStyle(c.error).lineLimit(2)
            }
            if offline {
                PrimaryButton(words.ALLOW_AND_DOWNLOAD) { onNetwork(true); onResume() }
            } else {
                PrimaryButton(words.RETRY, action: onResume)
            }
        case .ready:
            PrimaryButton(words.START_CHAT, action: onStart)
            if !offline {
                TextAction(words.BACK_OFFLINE, color: c.onSurfaceVariant) { onNetwork(false) }
            }

        default:
            EmptyView()
        }
    }

    @ViewBuilder private func progress(_ total: Int64) -> some View {
        ProgressLine(fraction: total > 0 ? min(max(Double(state.downloaded) / Double(total), 0), 1) : 0, running: state.step == .downloading)
        MicroLabel(words.progressLine(step: state.step, downloaded: state.downloaded, total: total))
    }
}
