import SwiftUI
import UIKit

/// MODELS → COMPUTE (port of `ComputeScreen.kt`): the phone's CPU, GPU and Neural Engine; per
/// model, force a backend or benchmark them; `auto` then uses the fastest measured one.
struct ComputeScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c
    @State private var devices: ComputeDevices?
    @State private var running: String?
    @State private var device: DeviceProfile?
    @State private var report: String?

    var body: some View {
        let models = app.library.models.filter { $0.isReady && ($0.engine == .llama || ($0.engine == .coreML && $0.kind == .image)) }
        VStack(spacing: 0) {
            AutobotTopBar(title: "Compute", onBack: { nav.back() }) {
                if DiagnosticsReportBuilder.available {
                    Tag("report") { report = DiagnosticsReportBuilder.render(app: app, devices: devices) }
                        .padding(.trailing, 4)
                }
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    VStack(alignment: .leading, spacing: 8) {
                        let info = LocalDeviceInfo.current()
                        SectionLabel("this phone" + ([info.name ?? device?.machine, info.chip ?? device?.chip].compactMap { $0 }.isEmpty ? "" : " · " + [info.name ?? device?.machine, info.chip ?? device?.chip].compactMap { $0 }.joined(separator: " · ")))
                        HStack(alignment: .top, spacing: 10) {
                            DeviceCard(label: "cpu", value: device.map { "\($0.cores) cores" } ?? "…", present: true)
                            DeviceCard(label: "gpu", value: devices?.gpu ?? "none", present: devices?.gpu != nil)
                            DeviceCard(label: "npu", value: devices?.npu ?? "none", present: devices?.npu != nil)
                        }
                        if let error = devices?.npuError { MicroLabel("npu: \(error)", color: c.error, lines: 2) }
                        if devices?.npu != nil {
                            MicroLabel("chat runs on the gpu (metal) or cpu — llama.cpp has no neural engine backend · images run on the neural engine", lines: 3)
                        }
                    }
                    if let running { MicroLabel("benchmarking · \(running)", color: c.primary, lines: 2) }
                    SectionLabel("models · auto picks the fastest measured backend", color: c.onSurfaceVariant)
                    ForEach(models) { m in
                        ModelCompute(model: m, running: running != nil) { benchmark(m) }
                    }
                    if models.isEmpty { MicroLabel("no runnable models yet — get one in the Models tab", lines: 2) }
                }
                .padding(16)
            }
        }
        .background(c.background)
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
        running = "starting…"
        let bench = ComputeBench(library: app.library, profile: app.compute, llm: app.llm)
        let backends = devices?.llmBackends ?? [.cpu]
        Task {
            _ = await bench.run(model, backends: backends) { running = $0 }
            running = nil
            app.modelsChanged()
        }
    }
}

private struct DeviceCard: View {
    let label: String
    let value: String
    let present: Bool
    @Environment(\.scheme) private var c

    var body: some View {
        Panel(title: label, accent: present ? c.primary : nil) {
            Text(value)
                .textStyle(.bodySmall)
                .foregroundStyle(present ? c.onSurface : c.onSurfaceVariant)
                .lineLimit(2)
                .frame(maxWidth: .infinity, minHeight: 34, alignment: .topLeading)
        }
        .frame(maxWidth: .infinity)
    }
}

private struct ModelCompute: View {
    let model: InstalledModel
    let running: Bool
    let onBenchmark: () -> Void
    @Environment(AppContainer.self) private var app
    @Environment(\.scheme) private var c

    var body: some View {
        let mine = app.compute.benchmarks.filter { $0.modelId == model.id }
        let results = Dictionary(grouping: mine.filter(\.ok), by: \.backend).compactMapValues { $0.max { $0.createdAt < $1.createdAt } }
        let fastest = ComputeProfile.fastest(mine)
        let lastError = mine.max { $0.createdAt < $1.createdAt }?.error
        let isText = model.engine == .llama
        let order: [Backend] = [.auto, .cpu, .gpu, .npu]
        Panel(title: model.title) {
            Tag(running ? "busy" : "benchmark", accent: c.primary, filled: !running, action: running ? nil : onBenchmark)
        } content: {
            Segmented(options: order, selected: model.backend, label: { b in
                if b == .auto, let fastest { "auto · \(fastest.label)" } else { b.label }
            }) { b in
                app.compute.setBackend(model.id, b)
                app.modelsChanged()
            }
            if !isText {
                MicroLabel("core ml · npu = neural engine, gpu = metal · auto uses the model's build (\(model.subtitle.contains("original") ? "gpu" : "neural engine"))", lines: 2)
            } else if model.backend == .npu {
                MicroLabel("llama.cpp has no neural engine backend — npu falls back to the gpu or cpu", color: AutobotColors.amber, lines: 2)
            }
            if !results.isEmpty {
                HStack(alignment: .top, spacing: 18) {
                    ForEach(order.filter { results[$0] != nil }, id: \.self) { backend in
                        let r = results[backend]!
                        let value = isText ? r.generationTps.map(fmt) ?? "–" : r.stepMs.map { fmt($0 / 1000) + "s" } ?? "–"
                        BigReadout(label: backend.label + (isText ? " · tok/s" : " · step"), value: value)
                    }
                }
                if results.values.contains(where: { $0.promptTps != nil }) {
                    MicroLabel(order.compactMap { b in results[b].map { "\(b.label) prefill \($0.promptTps.map(fmt) ?? "–") tok/s" } }.joined(separator: "  "), lines: 2)
                }
            } else {
                MicroLabel("not measured yet")
            }
            if let lastError { MicroLabel("last run: \(lastError)", color: c.error, lines: 3) }
        }
    }

    private func fmt(_ v: Double) -> String { String(format: v >= 100 ? "%.0f" : "%.1f", v) }
}

private struct ReportText: Identifiable {
    let text: String
    var id: String { text }
}

/// Shows the report before it is copied: the user sees exactly what they are about to share.
private struct ReportSheet: View {
    let text: String
    let onClose: () -> Void
    @Environment(\.scheme) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Diagnostics report").textStyle(.headlineSmall).foregroundStyle(c.onSurface)
            MicroLabel("no prompts, file paths or identifiers · nothing is sent", lines: 2)
            ScrollView {
                Text(text)
                    .textStyle(.codeBlock)
                    .foregroundStyle(c.onSurfaceVariant)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(10)
            }
            .background(RoundedRectangle(cornerRadius: AutobotShapes.small).fill(c.surfaceContainerLowest))
            .overlay(RoundedRectangle(cornerRadius: AutobotShapes.small).strokeBorder(c.outlineVariant, lineWidth: Tokens.Stroke.border))
            HStack(spacing: 8) {
                GhostButton("Close", action: onClose)
                PrimaryButton("Copy") {
                    UIPasteboard.general.string = text
                    onClose()
                }
            }
        }
        .padding(16)
        .background(c.background.ignoresSafeArea())
        .presentationDetents([.large])
    }
}
