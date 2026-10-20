import SwiftUI
import UIKit
import WebKit

/// Liquid Glass surface: real `glassEffect` on iOS 26, ultra-thin material before it.
private struct HarnessGlass<Content: View>: View {
    var cornerRadius: CGFloat = 22
    @ViewBuilder var content: Content

    var body: some View {
        if #available(iOS 26.0, *) {
            content.glassEffect(.regular, in: .rect(cornerRadius: cornerRadius))
        } else {
            content.background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        }
    }
}

/**
 The Harness tab: the DeepSeek Harness web UI runs inside the iSH-ARM64 emulator
 on this device, and this screen supervises it — boot progress, the loopback
 web view, a shell, the log, and a route to the paired Invincible node for
 broader compute.
 */
struct HarnessScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c
    @State private var model = HarnessModel()
    @State private var showLogs = false
    @State private var showShell = false
    @State private var showNodes = false

    private var nodes: [Provider] {
        app.providers.providers.filter { $0.kind == .invincibleNode }
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            c.background.ignoresSafeArea()
            if model.isReady {
                HarnessWebView(url: model.baseURL)
                    .ignoresSafeArea(edges: .bottom)
                    .transition(.opacity)
            } else {
                placeholder
            }

            VStack(spacing: 10) {
                statusBar
                if !model.isReady { startupCard }
            }
            .padding(.horizontal, 14)
            .padding(.bottom, 12)
        }
        .task { model.start() }
        .sheet(isPresented: $showLogs) { HarnessLogSheet(model: model) }
        .sheet(isPresented: $showShell) { HarnessShellSheet(model: model) }
        .confirmationDialog("Compute", isPresented: $showNodes, titleVisibility: .hidden) {
            ForEach(nodes) { node in
                Button("Open agent on \(node.displayName)") {
                    nav.push(.nodeAgent(providerId: node.id))
                }
            }
            Button("Pair a new node") {
                nav.reset(.remote, [.providers])
                nav.pendingPairing = ""
            }
        }
    }

    /// Quiet glass bar: live state dot, server URL, and the three actions.
    private var statusBar: some View {
        HStack(spacing: 12) {
            Circle()
                .fill(stateColor)
                .frame(width: 9, height: 9)
                .accessibilityLabel(model.stateName)

            VStack(alignment: .leading, spacing: 1) {
                Text("DeepSeek Harness").font(.subheadline.weight(.semibold))
                    .foregroundStyle(c.onSurface)
                Text(model.isReady ? "127.0.0.1:\(model.port) · on device" : model.stateName)
                    .font(.caption2).monospacedDigit()
                    .foregroundStyle(c.onSurfaceVariant)
            }
            Spacer(minLength: 6)

            Button("Shell", systemImage: "chevron.left.forwardslash.chevron.right") { showShell = true }
                .disabled(!model.isReady)
            Button("Log", systemImage: "doc.text.magnifyingglass") { showLogs = true }
            Button("Reload", systemImage: "arrow.clockwise") { model.restart() }
                .disabled(model.state == .starting || model.state == .restarting)
            Menu {
                if !nodes.isEmpty {
                    ForEach(nodes) { node in
                        Button("Agent on \(node.displayName)", systemImage: "bolt.horizontal.circle") {
                            nav.push(.nodeAgent(providerId: node.id))
                        }
                    }
                }
                Button("Pair a node", systemImage: "point.3.connected.trianglepath.dotted") {
                    nav.reset(.remote, [.providers])
                    nav.pendingPairing = ""
                }
                Button(model.isReady ? "Stop harness" : "Start harness", systemImage: "power") {
                    model.isReady ? model.stop() : model.restart()
                }
            } label: {
                Image(systemName: "ellipsis.circle")
            }
        }
        .buttonStyle(.plain)
        .labelStyle(.iconOnly)
        .font(.system(size: 17, weight: .medium))
        .foregroundStyle(c.primary)
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background {
            HarnessGlass(cornerRadius: 24) { Color.clear }
        }
        .overlay {
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .stroke(c.outlineVariant.opacity(0.5), lineWidth: 0.5)
        }
        .accessibilityElement(children: .contain)
    }

    private var stateColor: Color {
        switch model.state {
        case .ready: c.inks.cyan
        case .failed: c.error
        case .starting, .restarting: c.inks.amber
        case .stopped, .idle: c.outline
        @unknown default: c.outline
        }
    }

    /// While the guest boots, media stays visible behind a compact progress card.
    private var startupCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                if model.bootProgress >= 0 {
                    ProgressView(value: model.bootProgress)
                        .progressViewStyle(.linear)
                        .tint(c.inks.signal)
                } else {
                    ProgressView().tint(c.primary)
                }
                Text(model.statusMessage)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(c.onSurface)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
                if model.failed {
                    Button("Retry") { model.restart() }
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(c.onPrimary)
                        .padding(.horizontal, 12).padding(.vertical, 6)
                        .background(c.primary, in: Capsule())
                }
            }
            if !model.logTail.isEmpty {
                Text(model.logTail.joined(separator: "\n"))
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(c.onSurfaceVariant)
                    .lineLimit(3)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity)
        .background { HarnessGlass(cornerRadius: 20) { Color.clear } }
        .overlay {
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .stroke(c.outlineVariant.opacity(0.5), lineWidth: 0.5)
        }
    }

    /// Quiet empty state behind the startup card: the harness mark on the scheme ground.
    private var placeholder: some View {
        VStack(spacing: 14) {
            Image(systemName: "point.3.connected.trianglepath.dotted")
                .font(.system(size: 44, weight: .ultraLight))
                .foregroundStyle(c.secondary)
            Text("A Linux agent workspace on this device")
                .font(.headline)
                .foregroundStyle(c.onBackground)
            Text("Alpine, Node and the DeepSeek Harness boot inside an emulator. Only model API calls leave the device.")
                .font(.subheadline)
                .foregroundStyle(c.onSurfaceVariant)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 340)
        }
        .padding(28)
    }
}

/// Loopback web view for the harness UI.
private struct HarnessWebView: UIViewRepresentable {
    let url: URL?

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        let view = WKWebView(frame: .zero, configuration: config)
        view.isOpaque = true
        view.scrollView.contentInsetAdjustmentBehavior = .never
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {
        guard let url, url != view.url else { return }
        view.load(URLRequest(url: url))
    }
}

/// The guest log: a shareable plain-text report of the harness and its server.
private struct HarnessLogSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scheme) private var c
    let model: HarnessModel

    var body: some View {
        NavigationStack {
            ScrollView {
                Text(model.diagnosticReport)
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(c.onSurface)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
                    .textSelection(.enabled)
            }
            .background(c.surface.ignoresSafeArea())
            .navigationTitle("Harness log")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
                ToolbarItem(placement: .cancellationAction) {
                    ShareLink(item: model.diagnosticReport)
                }
            }
        }
    }
}

/// A small shell into the running guest (the emulator's /bin/sh), for `apk add`,
/// `npm i -g`, or poking at ~/.dsh. Commands only work once the guest has booted.
private struct HarnessShellSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scheme) private var c
    let model: HarnessModel
    @State private var command = ""
    @State private var output = ""
    @State private var running = false
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ScrollViewReader { proxy in
                    ScrollView {
                        Text(output.isEmpty ? "The Alpine shell of the guest. Commands run as root inside the emulator." : output)
                            .font(.system(size: 12, design: .monospaced))
                            .foregroundStyle(c.onSurface)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(14)
                            .textSelection(.enabled)
                            .id("output")
                    }
                    .onChange(of: output) { _, _ in
                        withAnimation(.easeOut(duration: 0.15)) { proxy.scrollTo("output", anchor: .bottom) }
                    }
                }
                .background(c.surface)

                HStack(spacing: 10) {
                    TextField("Command", text: $command)
                        .font(.system(size: 13, design: .monospaced))
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .focused($focused)
                        .onSubmit { run() }
                        .padding(.horizontal, 12).padding(.vertical, 9)
                        .background(c.surfaceContainerHighest, in: RoundedRectangle(cornerRadius: 12))
                    Button("Run", systemImage: "play") {
                        run()
                    }
                    .labelStyle(.iconOnly)
                    .disabled(running || command.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
                .padding(12)
                .background {
                    HarnessGlass(cornerRadius: 18) { Color.clear }
                }
            }
            .navigationTitle("Guest shell")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .onAppear { focused = true }
        }
        .presentationDetents([.medium, .large])
    }

    private func run() {
        let command = command.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !command.isEmpty else { return }
        running = true
        output += "$ \(command)\n"
        self.command = ""
        ISHShellExecutor.executeCommand(command, lineCallback: { line, isStdErr in
            MainActor.assumeIsolated {
                output += (isStdErr ? "! " : "") + line + "\n"
            }
        }, completion: { result in
            MainActor.assumeIsolated {
                output += "exit \(result.exitCode)\n"
                running = false
            }
        })
    }
}
