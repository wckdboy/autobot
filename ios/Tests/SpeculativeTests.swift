import AutobotShared
import Foundation
import Testing
@testable import Autobot

/// Speculative decoding on iOS: the setting round-trips through the shared schema, the draft pairs
/// and rules come from the shared module, and the Compute benchmark reads the engine's numbers.
struct SpeculativeTests {
    @Test func settingDefaultsToAutoAndRoundTrips() {
        #expect(Autobot.AppSettings(values: [:]).speculativeMode == "auto")
        for mode in ["auto", "on", "off"] {
            var s = Autobot.AppSettings(values: [:])
            s.speculativeMode = mode
            #expect(Autobot.AppSettings(values: s.values).speculativeMode == mode)
            #expect(s.values[SettingsSchema.shared.SPECULATIVE] == mode)
        }
        // Damaged values fail safe to auto.
        #expect(Autobot.AppSettings(values: [SettingsSchema.shared.SPECULATIVE: "turbo"]).speculativeMode == "auto")
    }

    @Test func pairsComeFromTheSharedCatalog() {
        #expect(SpeculativeDraftPicker.drafts(for: "catalog:qwen3-4b-2507-q4_0") == ["catalog:qwen3-0.6b-q4_0"])
        #expect(SpeculativeDraftPicker.drafts(for: "catalog:qwen3.5-4b-q4_0").isEmpty)
        // Every draft is a model this build can download.
        for id in SpeculativeDraftPicker.drafts(for: "catalog:qwen2.5-coder-7b-q4_k_m") + SpeculativeDraftPicker.drafts(for: "catalog:llama-3.2-3b-q4_0") {
            #expect(Catalog.shared.contains { $0.id == id }, "\(id)")
        }
    }

    @Test func memoryRuleFollowsTheSetting() {
        let rules = SpeculativeDrafts.shared
        let gb: Int64 = 1_000_000_000
        let big = DeviceCapabilities(platform: .ios, totalRamBytes: 16 * gb, freeStorageBytes: 50 * gb, cpuFeatures: [], socModel: nil,
                                     neuralEngine: true, gpuWorkingSetBytes: 0)
        let small = DeviceCapabilities(platform: .ios, totalRamBytes: 4 * gb, freeStorageBytes: 50 * gb, cpuFeatures: [], socModel: nil,
                                       neuralEngine: true, gpuWorkingSetBytes: 0)
        let target: Int64 = 2_376_000_000
        let draft: Int64 = 382_000_000
        #expect(rules.allows(mode: .auto_, device: big, targetBytes: target, draftBytes: draft, contextTokens: 8192))
        #expect(!rules.allows(mode: .off, device: big, targetBytes: target, draftBytes: draft, contextTokens: 8192))
        #expect(!rules.allows(mode: .on, device: small, targetBytes: target, draftBytes: draft, contextTokens: 8192))
        // Metal with the model, the CPU when the model is on the CPU.
        #expect(rules.draftBackend(platform: .ios, target: .gpu) == "gpu")
        #expect(rules.draftBackend(platform: .ios, target: .cpu) == "cpu")
    }

    @Test func benchmarkReadsSpeedAndAcceptance() {
        let json = #"{"content":"x","usage":{"completion_tokens":256,"generation_ms":4000,"speculative":{"status":"active","draft":"qwen3 0.6B Q4_0","drafted":200,"accepted":150}}}"#
        #expect(abs(ComputeBench.tps(json) - 64) < 1e-9)
        #expect(ComputeBench.tps(nil) == 0)
        #expect(ComputeBench.tps("not json") == 0)
        let stats = LocalLlmCodec.shared.speculativeStats(json: json)
        #expect(stats?.status == "active")
        #expect(abs((stats?.acceptance ?? 0) - 0.75) < 1e-9)
        let result = SpeculativeResult(modelId: "m", draftTitle: "Qwen3 0.6B", backend: .gpu, plainTps: 30, speculativeTps: 45, acceptance: 0.75)
        #expect(abs(result.speedup - 1.5) < 1e-9)
    }
}
