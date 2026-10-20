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
    private let words = FirstModel.shared

    var body: some View {
        if state.step != .hidden {
            Section(words.TITLE) {
                VStack(alignment: .leading, spacing: 10) {
                    if let plan = state.plan {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(plan.title).font(.headline)
                            Text(words.sizeLine(sizeBytes: plan.totalBytes, ramBytes: ramBytes)).font(.subheadline).foregroundStyle(.secondary)
                        }
                        Text(sentence(plan))
                            .font(.footnote).foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                        action(plan)
                    } else {
                        Text(words.NONE_FITS).font(.footnote).foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(.vertical, 4)
            }
        }
    }

    private var blocked: Bool { offline && (state.step == .offer || state.step == .failed) }

    private func sentence(_ plan: ModelPlan) -> String {
        if state.step == .ready { return words.readyLine(title: plan.title) }
        return blocked ? words.OFFLINE_FIRST : words.OFFLINE
    }

    /// The card's one next action: a prominent Liquid Glass button across the row.
    private func primary(_ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { Text(label).frame(maxWidth: .infinity) }
            .primaryActionStyle()
            .controlSize(.large)
    }

    private func quiet(_ label: String, action: @escaping () -> Void) -> some View {
        Button(label, action: action).buttonStyle(.borderless).foregroundStyle(.secondary).frame(maxWidth: .infinity)
    }

    @ViewBuilder private func action(_ plan: ModelPlan) -> some View {
        let total = plan.totalBytes
        switch state.step {
        case .offer:
            if offline {
                primary(words.ALLOW_AND_DOWNLOAD) { onNetwork(true); onDownload() }
            } else {
                primary(words.DOWNLOAD, action: onDownload)
            }
        case .queued, .downloading:
            progress(total)
            quiet(words.PAUSE, action: onPause)
        case .paused:
            progress(total)
            primary(words.RESUME, action: onResume)
        case .failed:
            if let error = state.error {
                Text(error).font(.footnote).foregroundStyle(.red).lineLimit(2)
            }
            if offline {
                primary(words.ALLOW_AND_DOWNLOAD) { onNetwork(true); onResume() }
            } else {
                primary(words.RETRY, action: onResume)
            }
        case .ready:
            primary(words.START_CHAT, action: onStart)
            if !offline {
                quiet(words.BACK_OFFLINE) { onNetwork(false) }
            }

        default:
            EmptyView()
        }
    }

    @ViewBuilder private func progress(_ total: Int64) -> some View {
        ProgressView(value: total > 0 ? min(max(Double(state.downloaded) / Double(total), 0), 1) : 0)
        Text(words.progressLine(step: state.step, downloaded: state.downloaded, total: total))
            .font(.caption).foregroundStyle(.secondary).monospacedDigit()
    }
}
