import SwiftUI
import UIKit
import WebKit

/**
 HARNESS tab: the DeepSeek Harness web UI, served by `dsh` inside the iSH-ARM64 emulator on this
 device. The system navigation bar carries the title and the actions; the page fills the rest
 between it and the tab bar. While the guest boots, a plain startup view says what is happening;
 in Offline mode the server stays stopped and the screen says why.
 */
struct HarnessScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scenePhase) private var scenePhase
    @State private var model = HarnessModel()
    @State private var showLogs = false
    @State private var showShell = false
    @State private var confirmRestart = false

    private var nodes: [Provider] { app.providers.providers.filter { $0.kind == .invincibleNode } }
    private var networkAllowed: Bool { app.settings.settings.mode.privacyStatus != .offline }

    var body: some View {
        VStack(spacing: 0) {
            AutobotTopBar(title: "Harness") {
                Button("Guest shell", systemImage: "terminal") { showShell = true }
                    .disabled(!model.isReady)
                menu
            }
            content
        }
        .background(Color(.systemBackground).ignoresSafeArea())
        .task { model.attach(app: app, networkAllowed: networkAllowed) }
        .onChange(of: networkAllowed) { _, allowed in model.setNetworkAllowed(allowed) }
        .onChange(of: scenePhase) { _, phase in if phase == .active { model.verifyAlive() } }
        .onChange(of: app.providers.providers) { _, _ in model.applyEnvironment() }
        .sheet(isPresented: $showLogs) { HarnessLogSheet(model: model) }
        .sheet(isPresented: $showShell) { HarnessShellSheet() }
        .confirmationDialog("Restart the harness?", isPresented: $confirmRestart, titleVisibility: .visible) {
            Button("Restart", role: .destructive) { model.restart() }
        } message: {
            Text("Running agent turns are interrupted; sessions are kept on disk.")
        }
    }

    @ViewBuilder private var content: some View {
        if !networkAllowed {
            MarkUnavailable("Network is off",
                            description: "The harness calls DeepSeek's API from its Linux guest, outside Autobot's kill switch, so it stays stopped in Offline mode. Allow a route out to start it.") {
                Button("Privacy Center") { nav.push(.privacyCenter) }
                    .glassProminentButton()
            }
        } else if model.isReady {
            VStack(spacing: 0) {
                HarnessWebView(model: model)
                    .overlay(alignment: .top) {
                        if !model.pageLoaded { loadingBar }
                    }
                statusRow
            }
        } else {
            startup
        }
    }

    // MARK: Pieces

    /// The actions: reload and restart, stop and start, the log, and the paired node for more compute.
    private var menu: some View {
        Menu {
            Section {
                Button("Reload page", systemImage: "arrow.clockwise") { model.reload() }
                    .disabled(!model.isReady)
                Button("Restart harness", systemImage: "arrow.counterclockwise") { confirmRestart = true }
                    .disabled(model.isBusy || !networkAllowed)
                if model.isReady || model.isBusy {
                    Button("Stop harness", systemImage: "stop.circle") { model.stop() }
                } else {
                    Button("Start harness", systemImage: "play.circle") { model.start() }
                        .disabled(!networkAllowed)
                }
            }
            Section {
                Button("Harness log", systemImage: "doc.text") { showLogs = true }
            }
            Section("More compute") {
                ForEach(nodes) { node in
                    Button("Agent on \(node.displayName)", systemImage: "server.rack") {
                        nav.push(.nodeAgent(providerId: node.id))
                    }
                }
                Button("Pair a node", systemImage: "point.3.connected.trianglepath.dotted") {
                    nav.reset(.remote, [.providers])
                    nav.pendingPairing = ""
                }
            }
        } label: {
            Label("More", systemImage: "ellipsis.circle")
        }
    }

    /// A thin notice over the page while it (re)loads.
    private var loadingBar: some View {
        HStack(spacing: 8) {
            ProgressView().controlSize(.small)
            Text(model.pageMessage ?? "Loading the interface…")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity)
        .background(.bar)
        .transition(.move(edge: .top).combined(with: .opacity))
    }

    /// Under the page: where it serves, whose key, and the device capability it just used.
    private var statusRow: some View {
        HStack(spacing: 8) {
            Image(systemName: "circle.fill")
                .font(.system(size: 7))
                .foregroundStyle(.green)
                .accessibilityHidden(true)
            Text(model.statusDetail)
                .monospacedDigit()
            Text("·").foregroundStyle(.tertiary)
            Text(model.keyLabel)
            Spacer(minLength: 8)
            if let activity = model.activity, -activity.at.timeIntervalSinceNow < 60 {
                CapsuleTag("\(activity.name) · \(activity.outcome.lowercased())", color: activity.ok ? .orange : .secondary)
            }
        }
        .font(.caption)
        .foregroundStyle(.secondary)
        .lineLimit(1)
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(.bar)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Harness ready, \(model.statusDetail), \(model.keyLabel)")
    }

    /// While the guest boots: what is happening, how far, and the last log lines.
    private var startup: some View {
        ScrollView {
            VStack(spacing: 18) {
                if model.failed {
                    Image(systemName: "exclamationmark.triangle")
                        .font(.system(size: 40, weight: .light))
                        .foregroundStyle(.secondary)
                        .accessibilityHidden(true)
                } else if model.bootProgress >= 0 {
                    ProgressView(value: model.bootProgress)
                        .progressViewStyle(.linear)
                        .frame(maxWidth: 280)
                } else if model.state == .stopped || (model.bootPhase == .idle && !model.isBusy) {
                    Image(systemName: "terminal")
                        .font(.system(size: 40, weight: .light))
                        .foregroundStyle(.secondary)
                        .accessibilityHidden(true)
                } else {
                    ProgressView()
                        .controlSize(.large)
                }
                VStack(spacing: 6) {
                    Text(model.statusMessage)
                        .font(.headline)
                        .multilineTextAlignment(.center)
                    Text("Alpine Linux, Node and the DeepSeek Harness run in an emulator on this phone. Sessions and the workspace stay in the guest; only its model calls leave.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: 360)
                if model.failed {
                    HStack(spacing: 10) {
                        Button("Retry") { model.restart() }
                            .glassProminentButton()
                        Button("Harness log") { showLogs = true }
                            .glassButton()
                    }
                } else if model.state == .stopped || (model.bootPhase == .idle && !model.isBusy) {
                    Button("Start harness") { model.start() }
                        .glassProminentButton()
                }
                if !model.logTail.isEmpty, !model.failed {
                    Text(model.logTail.suffix(3).joined(separator: "\n"))
                        .font(.caption2.monospaced())
                        .foregroundStyle(.tertiary)
                        .lineLimit(3)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: 360)
                        .accessibilityHidden(true)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 28)
            .padding(.top, 80)
            .padding(.bottom, 40)
        }
    }
}

// MARK: - Web view

/// The loopback page. Loads only when [HarnessModel.loadGeneration] changes: a new port, an
/// explicit reload, a failed page, a web content process that died. Links that leave the local
/// server open in Safari.
private struct HarnessWebView: UIViewRepresentable {
    let model: HarnessModel

    func makeCoordinator() -> Coordinator { Coordinator(model: model) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        let view = WKWebView(frame: .zero, configuration: config)
        view.navigationDelegate = context.coordinator
        view.uiDelegate = context.coordinator
        view.allowsBackForwardNavigationGestures = false
        view.isOpaque = false
        view.backgroundColor = .systemBackground
        view.scrollView.backgroundColor = .systemBackground
        view.scrollView.contentInsetAdjustmentBehavior = .never
        view.accessibilityIdentifier = "harness.page"
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {
        guard let url = model.baseURL, context.coordinator.loadedGeneration != model.loadGeneration else { return }
        context.coordinator.loadedGeneration = model.loadGeneration
        var request = URLRequest(url: url)
        request.cachePolicy = .reloadIgnoringLocalCacheData
        view.load(request)
    }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        let model: HarnessModel
        var loadedGeneration = -1

        init(model: HarnessModel) { self.model = model }

        private func isLocal(_ url: URL) -> Bool {
            guard let base = model.baseURL else { return false }
            return url.host == base.host && (url.port ?? 80) == (base.port ?? 80)
        }

        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void) {
            guard let url = action.request.url else { decisionHandler(.allow); return }
            let scheme = url.scheme ?? ""
            if isLocal(url) || scheme == "about" || scheme == "blob" || scheme == "data" {
                decisionHandler(.allow)
                return
            }
            // Anything leaving the local server (docs, sign-in pages) is the user's tap: Safari.
            if scheme.hasPrefix("http") || scheme == "mailto" {
                UIApplication.shared.open(url)
                decisionHandler(.cancel)
                return
            }
            decisionHandler(.allow)
        }

        func webView(_ webView: WKWebView, decidePolicyFor response: WKNavigationResponse, decisionHandler: @escaping @MainActor (WKNavigationResponsePolicy) -> Void) {
            decisionHandler(response.canShowMIMEType ? .allow : .cancel)
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            model.pageDidLoad()
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            model.pageFailed(error)
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            model.pageFailed(error)
        }

        func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
            model.webProcessTerminated()
        }

        /// `target="_blank"` stays in this page; the local server has no second window.
        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
            if let url = action.request.url {
                if isLocal(url) { webView.load(action.request) } else { UIApplication.shared.open(url) }
            }
            return nil
        }
    }
}

// MARK: - Sheets

/// The guest log: a shareable plain-text report of the harness and its server.
private struct HarnessLogSheet: View {
    @Environment(\.dismiss) private var dismiss
    let model: HarnessModel

    var body: some View {
        NavigationStack {
            ScrollView {
                Text(model.diagnosticReport)
                    .font(.caption.monospaced())
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
                    .textSelection(.enabled)
            }
            .navigationTitle("Harness log")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
                ToolbarItem(placement: .topBarLeading) {
                    ShareLink(item: model.diagnosticReport) {
                        Label("Share", systemImage: "square.and.arrow.up")
                    }
                }
            }
        }
    }
}

/// A shell into the running guest (the emulator's `/bin/sh`): `apk add`, `npm i -g`, a look at
/// `~/.dsh`. Commands run as root inside the emulator, once the guest has booted.
private struct HarnessShellSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var command = ""
    @State private var lines: [String] = []
    @State private var running = false
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ScrollViewReader { proxy in
                    ScrollView {
                        if lines.isEmpty {
                            Text("The Alpine shell of the guest. Commands run as root inside the emulator.")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(16)
                        } else {
                            Text(lines.joined(separator: "\n"))
                                .font(.caption.monospaced())
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(16)
                                .textSelection(.enabled)
                        }
                        Color.clear.frame(height: 1).id("end")
                    }
                    .onChange(of: lines.count) { _, _ in
                        withAnimation(.easeOut(duration: 0.15)) { proxy.scrollTo("end", anchor: .bottom) }
                    }
                }
                Divider()
                HStack(spacing: 10) {
                    TextField("Command", text: $command)
                        .font(.body.monospaced())
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .submitLabel(.go)
                        .focused($focused)
                        .onSubmit { run() }
                        .textFieldStyle(.roundedBorder)
                    Button("Run", systemImage: "play.fill") { run() }
                        .labelStyle(.iconOnly)
                        .glassProminentButton()
                        .disabled(running || command.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
                .padding(12)
                .background(.bar)
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
        guard !command.isEmpty, !running else { return }
        running = true
        lines.append("$ \(command)")
        self.command = ""
        let pid = ISHShellExecutor.executeCommand(command, lineCallback: { line, isStdErr in
            MainActor.assumeIsolated {
                lines.append((isStdErr ? "! " : "") + line)
            }
        }, completion: { result in
            MainActor.assumeIsolated {
                if result.exitCode != 0 { lines.append("exit \(result.exitCode)") }
                running = false
            }
        })
        if pid < 0 {
            lines.append("! could not start the command (\(pid))")
            running = false
        }
    }
}
