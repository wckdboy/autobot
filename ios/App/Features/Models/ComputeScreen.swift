import SwiftUI
import UIKit

/// MODELS → COMPUTE (port of `ComputeScreen.kt`): the phone's CPU, GPU and Neural Engine; per
/// model, force a backend or benchmark them; `auto` then uses the fastest measured one.
struct ComputeScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @State private var devices: ComputeDevices?
    @State private var running: String?
    /// The model being benchmarked, and how far (backends done / to run).
    @State private var benchModel: String?
    @State private var benchStep = (done: 0, total: 1)
    @State private var device: DeviceProfile?
    @State private var report: String?

    var body: some View {
        let models = app.library.models.filter { $0.isReady && ($0.isLocalChat || ($0.engine == .coreML && $0.kind == .image)) }
        VStack(spacing: 0) {
            AutobotTopBar(title: "Compute", onBack: { nav.back() }) {
                if DiagnosticsReportBuilder.available {
                    Button("Report") { report = DiagnosticsReportBuilder.render(app: app, devices: devices) }
                }
            }
            List {
                let info = LocalDeviceInfo.current()
                let names = [info.name ?? device?.machine, info.chip ?? device?.chip].compactMap { $0 }
                Section {
                    DeviceRow(label: "CPU", value: device.map { "\($0.cores) cores" }, present: true)
                    DeviceRow(label: "GPU", value: devices?.gpu ?? (devices == nil ? nil : "none"), present: devices?.gpu != nil)
                    DeviceRow(label: "NPU", value: devices?.npu ?? (devices == nil ? nil : "none"), present: devices?.npu != nil)
                    if let error = devices?.npuError { Text("npu: \(error)").font(.footnote).foregroundStyle(.red).lineLimit(2) }
                } header: {
                    Text("This phone" + (names.isEmpty ? "" : " · " + names.joined(separator: " · ")))
                } footer: {
                    if devices?.npu != nil {
                        Text("chat runs on the gpu (metal) or cpu — llama.cpp has no neural engine backend · images run on the neural engine")
                    }
                }
                let system = AppleIntelligence.unavailability
                Section {
                    LabeledContent(AppleIntelligence.title) {
                        Text(system.map { "unavailable · \($0.detail)" } ?? "available")
                            .foregroundStyle(system == nil ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                    }
                } header: {
                    Text("System model")
                } footer: {
                    Text("fallback · ~4k tokens, no tools")
                }
                if let running {
                    Section {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("benchmarking · \(running.hasSuffix("…") ? String(running.dropLast()) : running)").font(.footnote).foregroundStyle(.secondary).lineLimit(2)
                            ProgressView(value: Double(benchStep.done) / Double(max(benchStep.total, 1)))
                        }
                    }
                }
                if models.contains(where: { !SpeculativeDraftPicker.drafts(for: $0.id).isEmpty }) {
                    SpeculativePanel()
                }
                ForEach(models) { m in
                    ModelCompute(model: m, running: running != nil, benchmarking: benchModel == m.id) { benchmark(m) }
                }
                if models.isEmpty {
                    MarkUnavailableView("No runnable models yet", message: "get one in the Models tab")
                }
            }
            .listStyle(.insetGrouped)
        }
        .sheet(item: Binding(get: { report.map(ReportText.init) }, set: { report = $0?.text })) { r in
            ReportSheet(text: r.text) { report = nil }
        }
        .task {
            device = DeviceProfile.current()
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("-AutobotReport") {
                try? await Task.sleep(for: .milliseconds(800))
                report = DiagnosticsReportBuilder.render(app: app, devices: devices)
            }
            #endif
            devices = ComputeBench.quickDevices()
            devices = await ComputeBench(library: app.library, profile: app.compute, llm: app.llm).devices()
        }
    }

    private func benchmark(_ model: InstalledModel) {
        guard running == nil else { return }
        running = "starting"
        benchModel = model.id
        let bench = ComputeBench(library: app.library, profile: app.compute, llm: app.llm, speculativeMode: app.settings.settings.speculativeMode)
        let backends = devices?.llmBackends ?? [.cpu]
        // One step per backend measured (ComputeBench.run reports each as it starts).
        let total = model.engine == .llama ? [Backend.cpu, .gpu, .npu].filter(backends.contains).count : 2
        benchStep = (0, max(total, 1))
        Task {
            _ = await bench.run(model, backends: backends) { status in
                if running != "starting" { benchStep.done = min(benchStep.done + 1, benchStep.total) }
                running = status
            }
            benchStep.done = benchStep.total
            running = nil
            benchModel = nil
            app.modelsChanged()
        }
    }
}

/// One compute unit: its name, what it is (a spinner while it is read), the tint when present.
private struct DeviceRow: View {
    let label: String
    /// Nil while it is being read.
    let value: String?
    let present: Bool

    var body: some View {
        LabeledContent {
            if let value {
                Text(value).lineLimit(2)
            } else {
                ProgressView()
            }
        } label: {
            Label {
                Text(label)
            } icon: {
                Image(systemName: present ? "checkmark.circle.fill" : "circle.dashed")
                    .foregroundStyle(present ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
            }
        }
    }
}

private struct ModelCompute: View {
    let model: InstalledModel
    let running: Bool
    /// This model is the one being benchmarked.
    let benchmarking: Bool
    let onBenchmark: () -> Void
    @Environment(AppContainer.self) private var app

    var body: some View {
        let mine = app.compute.benchmarks.filter { $0.modelId == model.id }
        let results = Dictionary(grouping: mine.filter(\.ok), by: \.backend).compactMapValues { $0.max { $0.createdAt < $1.createdAt } }
        let fastest = ComputeProfile.fastest(mine)
        let lastError = mine.max { $0.createdAt < $1.createdAt }?.error
        let isText = model.engine == .llama
        let order: [Backend] = [.auto, .cpu, .gpu, .npu]
        Section {
            Picker("Backend", selection: Binding(get: { model.backend }, set: { b in
                app.compute.setBackend(model.id, b)
                app.modelsChanged()
            })) {
                ForEach(order, id: \.self) { b in
                    Text(b == .auto ? (fastest.map { "auto · \($0.label)" } ?? b.label) : b.label).tag(b)
                }
            }
            if !results.isEmpty {
                ForEach(order.filter { results[$0] != nil }, id: \.self) { backend in
                    let r = results[backend]!
                    let value = isText ? r.generationTps.map(fmt) ?? "–" : r.stepMs.map { fmt($0 / 1000) + "s" } ?? "–"
                    LabeledContent(backend.label + (isText ? " · tok/s" : " · step")) {
                        Text(value).font(.title3.weight(.semibold)).monospacedDigit().foregroundStyle(.primary)
                    }
                }
                if results.values.contains(where: { $0.promptTps != nil }) {
                    Text(order.compactMap { b in results[b].map { "\(b.label) prefill \($0.promptTps.map(fmt) ?? "–") tok/s" } }.joined(separator: "  "))
                        .font(.caption).foregroundStyle(.secondary).lineLimit(2)
                }
            } else {
                Text("not measured yet").foregroundStyle(.secondary)
            }
            if isText, let line = speculativeLine {
                Text(line.text).font(.footnote).foregroundStyle(line.error ? AnyShapeStyle(.red) : AnyShapeStyle(.secondary)).lineLimit(2)
            }
            if let lastError { Text("last run: \(lastError)").font(.footnote).foregroundStyle(.red).lineLimit(3) }
        } header: {
            HStack {
                Text(model.title)
                Spacer()
                Button {
                    onBenchmark()
                } label: {
                    if benchmarking { ProgressView() } else { Text("Benchmark") }
                }
                .font(.subheadline)
                .textCase(nil)
                .disabled(running)
            }
        } footer: {
            if !isText {
                Text("core ml · npu = neural engine, gpu = metal · auto uses the model's build (\(model.subtitle.contains("original") ? "gpu" : "neural engine"))")
            } else if model.backend == .npu {
                Text("llama.cpp has no neural engine backend — npu falls back to the gpu or cpu").foregroundStyle(.orange)
            }
        }
    }
    /// What speculative decoding does for this model: needs a draft, measure it, or the measured speedup.
    private var speculativeLine: (text: String, error: Bool)? {
        let drafts = SpeculativeDraftPicker.drafts(for: model.id)
        guard let first = drafts.first else { return nil }
        let title = { (id: String) in app.library.models.first { $0.id == id }?.title ?? Catalog.shared.first { $0.id == id }?.title ?? id }
        if app.settings.settings.speculativeMode == "off" { return ("speculative: off", false) }
        guard drafts.contains(where: { id in app.library.models.contains { $0.id == id && $0.isLocalChat } }) else {
            return ("speculative: install \(title(first)) to speed this model up", false)
        }
        guard let s = app.compute.speculative[model.id] else { return ("speculative: benchmark to measure (draft \(title(first)))", false) }
        if let error = s.error { return ("speculative: \(error)", true) }
        return ("speculative · \(fmt(s.speedup))x · \(fmt(s.speculativeTps)) tok/s · \(Int(s.acceptance * 100))% of drafts kept · \(s.draftTitle)", false)
    }

    private func fmt(_ v: Double) -> String { String(format: v >= 100 ? "%.0f" : "%.1f", v) }
}

/// Speculative decoding: auto / on / off (shown when an installed model has a draft in the catalog).
private struct SpeculativePanel: View {
    @Environment(AppContainer.self) private var app

    private static let modes = ["auto", "on", "off"]

    var body: some View {
        let mode = app.settings.settings.speculativeMode
        Section {
            Picker("Speculative decoding", selection: Binding(get: { mode }, set: { app.settings.setSpeculativeMode($0) })) {
                ForEach(Self.modes, id: \.self) { Text($0).tag($0) }
            }
            .pickerStyle(.segmented)
        } header: {
            Text("Speculative decoding")
        } footer: {
            Text(Self.detail(mode))
        }
    }

    static func detail(_ mode: String) -> String {
        switch mode {
        case "on": "always draft when the pair fits at all, even when it does not pay"
        case "off": "no draft model is loaded"
        default: "a small model of the same family drafts, the chat model checks · same replies, faster · when the pair fits"
        }
    }
}

private struct ReportText: Identifiable {
    let text: String
    var id: String { text }
}

/// Shows the report before it is copied: the user sees exactly what they are about to share.
private struct ReportSheet: View {
    let text: String
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text("no prompts, file paths or identifiers · nothing is sent").font(.footnote).foregroundStyle(.secondary)
                    // The report is code-like text: monospaced.
                    Text(text)
                        .font(.caption.monospaced())
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(12)
                        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 12))
                }
                .padding(16)
            }
            .navigationTitle("Diagnostics report")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close", action: onClose) }
            }
            .safeAreaInset(edge: .bottom) {
                Button {
                    UIPasteboard.general.string = text
                    onClose()
                } label: {
                    Text("Copy").frame(maxWidth: .infinity)
                }
                .primaryActionStyle()
                .controlSize(.large)
                .padding(16)
            }
        }
        .presentationDetents([.large])
    }
}
