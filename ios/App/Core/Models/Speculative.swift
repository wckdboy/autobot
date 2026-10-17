import AutobotShared
import Foundation

/**
 Speculative decoding on iOS, with the shared rules (`shared/.../core/models/Speculative.kt`): which
 installed model drafts for a chat model, whether the pair fits this phone for the setting, and
 where the draft runs (Metal with the model, the CPU when the model is on the CPU).
 */
enum SpeculativeDraftPicker {
    /// Draft model ids for [modelId], best first (empty: none in the catalog).
    static func drafts(for modelId: String) -> [String] { SpeculativeDrafts.shared.draftsFor(targetId: modelId) }

    /// The draft for [model] the setting [mode] (`SpeculativeMode` id) and this device allow, or nil.
    @MainActor
    static func pick(for model: InstalledModel, mode: String, library: ModelLibrary, device: DeviceProfile, context: Int, backend: Backend) -> HostSnapshots.Draft? {
        let speculative = SpeculativeMode.companion.of(value: mode)
        guard speculative != .off else { return nil }
        let rules = SpeculativeDrafts.shared
        for id in rules.draftsFor(targetId: model.id) {
            guard let draft = library.models.first(where: { $0.id == id && $0.isLocalChat }),
                  let path = library.paths(draft)[.model], FileManager.default.fileExists(atPath: path.path),
                  rules.allows(mode: speculative, device: device.capabilities, targetBytes: model.totalBytes, draftBytes: draft.totalBytes,
                               contextTokens: Int32(context))
            else { continue }
            let target = AutobotShared.Backend.companion.of(id: backend.rawValue)
            return .init(modelId: draft.id, title: draft.title, path: path.path, backend: rules.draftBackend(platform: .ios, target: target), mode: speculative.id)
        }
        return nil
    }
}

/// A Compute benchmark of speculative decoding: the same greedy reply with and without the draft (this session only).
struct SpeculativeResult: Equatable, Sendable {
    var modelId: String
    var draftTitle: String
    var backend: Backend
    var plainTps: Double
    var speculativeTps: Double
    /// Share of drafted tokens the model kept, 0...1.
    var acceptance: Double
    var error: String? = nil

    var speedup: Double { plainTps > 0 ? speculativeTps / plainTps : 0 }
}
