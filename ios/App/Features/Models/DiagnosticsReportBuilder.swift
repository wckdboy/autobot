import Foundation
import UIKit
#if canImport(AutobotShared)
import AutobotShared
#endif

/**
 The Compute screen's "report": the shared `DiagnosticsReport` (same fields and text as Android),
 filled from what this phone and its engines reported. No prompts, outputs, file paths or
 identifiers; the user sees the text before copying it, and nothing is sent.
 */
@MainActor
enum DiagnosticsReportBuilder {
    static var available: Bool {
        #if canImport(AutobotShared)
        true
        #else
        false
        #endif
    }

    /// The report text, or nil when the shared runtime is not linked.
    static func render(app: AppContainer, devices: ComputeDevices?) -> String? {
        #if canImport(AutobotShared)
        let info = LocalDeviceInfo.current()
        let profile = DeviceProfile.current()
        let bundle = Bundle.main.infoDictionary
        let version = "\(bundle?["CFBundleShortVersionString"] as? String ?? "?") (\(bundle?["CFBundleVersion"] as? String ?? "0"))"
        let device = DeviceInfo(
            label: info.name ?? info.machine,
            soc: info.chip,
            accelerator: info.neuralEngine ? "Neural Engine" : nil,
            ramBytes: profile.totalRamBytes,
            freeStorageBytes: profile.freeStorageBytes,
            cores: Int32(profile.cores),
            cpuFeatures: [],
        )
        let engineDevices = (devices?.engineDevices ?? []).map {
            EngineDevice(name: $0.name, description: $0.description, kind: $0.kind, memoryTotalBytes: $0.memoryTotalBytes)
        }
        var lines: [BenchmarkLine] = []
        for model in app.library.models where model.isReady && (model.engine == .llama || model.engine == .coreML) {
            let mine = app.compute.benchmarks.filter { $0.modelId == model.id }
            let newest = Dictionary(grouping: mine.filter(\.ok), by: \.backend).compactMapValues { $0.max { $0.createdAt < $1.createdAt } }
            for (backend, b) in newest.sorted(by: { $0.key.rawValue < $1.key.rawValue }) {
                lines.append(BenchmarkLine(
                    model: model.title, backend: backend.label,
                    promptTps: b.promptTps.map { KotlinDouble(value: $0) },
                    generationTps: b.generationTps.map { KotlinDouble(value: $0) },
                    stepMs: b.stepMs.map { KotlinDouble(value: $0) },
                    loadMs: b.loadMs.map { KotlinLong(value: $0) },
                    error: nil,
                ))
            }
            if let error = mine.max(by: { $0.createdAt < $1.createdAt })?.error {
                lines.append(BenchmarkLine(model: model.title, backend: "last run", promptTps: nil, generationTps: nil, stepMs: nil, loadMs: nil, error: error))
            }
        }
        let report = DiagnosticsReport(
            app: "Autobot \(version)",
            os: "iOS \(UIDevice.current.systemVersion)",
            device: device,
            engineDevices: engineDevices,
            imageAccelerator: AcceleratorStatus(name: "Core ML ANE", available: info.neuralEngine, detail: devices?.npuError),
            benchmarks: lines,
            errors: [devices?.npuError].compactMap { $0 } + EngineErrors.recent,
            networkMode: app.settings.settings.mode.privacyStatus.label,
            systemModel: AcceleratorStatus(name: AppleIntelligence.title, available: AppleIntelligence.unavailability == nil, detail: AppleIntelligence.unavailability?.detail),
            imageAcceleratorDetails: [],
        )
        return report.render()
        #else
        return nil
        #endif
    }
}
