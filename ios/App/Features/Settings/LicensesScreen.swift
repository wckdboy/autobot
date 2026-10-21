import SwiftUI

/// One notice in the Licenses screen; [file] is a text resource in `Resources/Licenses`.
private struct LicenseNotice: Identifiable {
    let id: String
    let title: String
    let detail: String
    let license: String
    let file: String
}

private let notices: [LicenseNotice] = [
    LicenseNotice(id: "autobot", title: "Autobot", detail: "Copyright 2026 wckdboy", license: "Apache-2.0", file: "Apache-2.0"),
    LicenseNotice(id: "harness", title: "DeepSeek Harness", detail: "Architecture of the agent runtime (plugins, session log, loop, tools, permissions, compaction) · Copyright (c) 2026 DeepSeek", license: "MIT", file: "DeepSeek-Harness-MIT"),
    LicenseNotice(id: "llama", title: "llama.cpp (incl. ggml)", detail: "On-device chat on Metal and the CPU · Copyright (c) 2023-2026 The ggml authors", license: "MIT", file: "llama.cpp-MIT"),
    LicenseNotice(id: "whisper", title: "whisper.cpp", detail: "On-device dictation · Copyright (c) 2023-2026 The ggml authors", license: "MIT", file: "whisper.cpp-MIT"),
    LicenseNotice(id: "mlsd", title: "ml-stable-diffusion", detail: "Core ML Stable Diffusion on the Neural Engine · Copyright (c) 2024 Apple Inc.", license: "MIT", file: "ml-stable-diffusion-MIT"),
    LicenseNotice(id: "mlsd-ack", title: "ml-stable-diffusion acknowledgements", detail: "Third-party notices of the Core ML pipeline", license: "various", file: "ml-stable-diffusion-ACKNOWLEDGEMENTS"),
    LicenseNotice(id: "stable-audio", title: "Stable Audio Open Small", detail: "Powered by Stability AI · on-device text to audio (downloaded, not bundled) · This Stability AI Model is licensed under the Stability AI Community License, Copyright © Stability AI Ltd. All Rights Reserved", license: "Stability AI Community", file: "StableAudio-Stability-AI-Community"),
    LicenseNotice(id: "kotlin", title: "Kotlin, kotlinx.coroutines, kotlinx.serialization, Okio", detail: "Shared agent runtime (Kotlin Multiplatform) · JetBrains s.r.o., Square, Inc. and contributors", license: "Apache-2.0", file: "Apache-2.0"),
    LicenseNotice(id: "jbmono", title: "JetBrains Mono", detail: "Label and code typeface · JetBrains", license: "OFL-1.1", file: "JetBrainsMono-OFL"),
]

/// Autobot's licence and the notices of the software built into the iOS app. Models are not
/// bundled; each catalog entry shows its own licence before download.
struct LicensesScreen: View {
    @Environment(Navigator.self) private var nav
    @State private var open: String?

    var body: some View {
        VStack(spacing: 0) {
            AutobotTopBar(title: "Licenses", onBack: { nav.back() })
            List {
                Section {
                    ForEach(notices) { notice in
                        DisclosureGroup(isExpanded: Binding(get: { open == notice.id }, set: { open = $0 ? notice.id : nil })) {
                            // Licence text is plain text set as code: monospaced.
                            Text(Self.text(notice.file))
                                .font(.caption.monospaced())
                                .foregroundStyle(.secondary)
                                .textSelection(.enabled)
                                .fixedSize(horizontal: false, vertical: true)
                        } label: {
                            HStack(alignment: .top, spacing: 12) {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(notice.title).font(.headline)
                                    Text(notice.detail).font(.caption).foregroundStyle(.secondary)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                CapsuleTag(notice.license, color: open == notice.id ? nil : .secondary)
                            }
                        }
                    }
                } header: {
                    Text("Open source · tap to read")
                } footer: {
                    Text("models are not bundled · downloaded models are licensed by their authors (shown before download)")
                }
            }
            .listStyle(.insetGrouped)
        }
    }

    static func text(_ file: String) -> String {
        Bundle.main.url(forResource: file, withExtension: "txt").flatMap { try? String(contentsOf: $0, encoding: .utf8) } ?? "Licence text missing from this build."
    }
}
