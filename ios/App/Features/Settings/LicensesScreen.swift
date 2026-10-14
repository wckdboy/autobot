import SwiftUI

/// One notice in the Licenses screen; [file] is a text resource in `Resources/Licenses`.
private struct Notice: Identifiable {
    let id: String
    let title: String
    let detail: String
    let license: String
    let file: String
}

private let notices: [Notice] = [
    Notice(id: "autobot", title: "Autobot", detail: "Copyright 2026 wckdboy", license: "Apache-2.0", file: "Apache-2.0"),
    Notice(id: "harness", title: "DeepSeek Harness", detail: "Architecture of the agent runtime (plugins, session log, loop, tools, permissions, compaction) · Copyright (c) 2026 DeepSeek", license: "MIT", file: "DeepSeek-Harness-MIT"),
    Notice(id: "llama", title: "llama.cpp (incl. ggml)", detail: "On-device chat on Metal and the CPU · Copyright (c) 2023-2026 The ggml authors", license: "MIT", file: "llama.cpp-MIT"),
    Notice(id: "whisper", title: "whisper.cpp", detail: "On-device dictation · Copyright (c) 2023-2026 The ggml authors", license: "MIT", file: "whisper.cpp-MIT"),
    Notice(id: "mlsd", title: "ml-stable-diffusion", detail: "Core ML Stable Diffusion on the Neural Engine · Copyright (c) 2024 Apple Inc.", license: "MIT", file: "ml-stable-diffusion-MIT"),
    Notice(id: "mlsd-ack", title: "ml-stable-diffusion acknowledgements", detail: "Third-party notices of the Core ML pipeline", license: "various", file: "ml-stable-diffusion-ACKNOWLEDGEMENTS"),
    Notice(id: "kotlin", title: "Kotlin, kotlinx.coroutines, kotlinx.serialization, Okio", detail: "Shared agent runtime (Kotlin Multiplatform) · JetBrains s.r.o., Square, Inc. and contributors", license: "Apache-2.0", file: "Apache-2.0"),
    Notice(id: "antonio", title: "Antonio", detail: "Display typeface · Vernon Adams", license: "OFL-1.1", file: "Antonio-OFL"),
    Notice(id: "jbmono", title: "JetBrains Mono", detail: "Label and code typeface · JetBrains", license: "OFL-1.1", file: "JetBrainsMono-OFL"),
]

/// Autobot's licence and the notices of the software built into the iOS app. Models are not
/// bundled; each catalog entry shows its own licence before download.
struct LicensesScreen: View {
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c
    @State private var open: String?

    var body: some View {
        VStack(spacing: 0) {
            AutobotTopBar(title: "Licenses", onBack: { nav.back() })
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    SectionLabel("open source · tap to read").padding(.vertical, 8)
                    ForEach(notices) { notice in
                        VStack(alignment: .leading, spacing: 0) {
                            Button { withAnimation(.easeInOut(duration: 0.2)) { open = open == notice.id ? nil : notice.id } } label: {
                                HStack(alignment: .top, spacing: 12) {
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(notice.title).textStyle(.titleMedium).foregroundStyle(c.onSurface).multilineTextAlignment(.leading)
                                            .fixedSize(horizontal: false, vertical: true)
                                        Text(notice.detail).textStyle(.micro).foregroundStyle(c.onSurfaceVariant).multilineTextAlignment(.leading)
                                            .fixedSize(horizontal: false, vertical: true)
                                    }
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    Tag(notice.license, accent: open == notice.id ? c.primary : nil, filled: open == notice.id)
                                }
                                .padding(.vertical, 12)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(PressStyle())
                            if open == notice.id {
                                Text(Self.text(notice.file))
                                    .textStyle(.codeBlock)
                                    .foregroundStyle(c.onSurfaceVariant)
                                    .textSelection(.enabled)
                                    .fixedSize(horizontal: false, vertical: true)
                                    .padding(10)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .background(c.surfaceContainerLow)
                                    .padding(.bottom, 10)
                            }
                            Hairline()
                        }
                    }
                    Spacer().frame(height: 14)
                    MicroLabel("models are not bundled · downloaded models are licensed by their authors (shown before download)", lines: 3)
                    Spacer().frame(height: 24)
                }
                .padding(.horizontal, Tokens.Spacing.gutter)
            }
        }
        .background(c.background)
    }

    static func text(_ file: String) -> String {
        Bundle.main.url(forResource: file, withExtension: "txt").flatMap { try? String(contentsOf: $0, encoding: .utf8) } ?? "Licence text missing from this build."
    }
}
