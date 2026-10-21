import SwiftUI
import UniformTypeIdentifiers

/// The finished export, handed to the system file exporter (the bundle is already on disk).
private struct BundleFile: FileDocument {
    static let readableContentTypes: [UTType] = [.data]
    let url: URL

    init(url: URL) { self.url = url }

    init(configuration: ReadConfiguration) throws { throw CocoaError(.featureUnsupported) }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { try FileWrapper(url: url) }
}

/**
 Export and import of the passphrase-sealed `.autobot` bundle (the same file the Android app
 reads); port of `BackupDialog.kt`. Files go through the system document picker.
 */
struct BackupSheet: View {
    private enum Status: Equatable {
        case idle, working(String), done(String), failed(String)
    }

    @Environment(AppContainer.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var passphrase = ""
    @State private var confirm = ""
    @State private var status = Status.idle
    @State private var exported: BundleFile?
    @State private var importing = false
    /// Which way the running job goes (its button shows the loader).
    @State private var exporting = false

    private var working: Bool { if case .working = status { true } else { false } }
    private var exportable: Bool { passphrase.count >= BackupService.minPassphrase && passphrase == confirm }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Sessions, gallery, attachments, providers (without API keys) and appearance settings, sealed with a passphrase (AES-256-GCM). The Android app opens the same file. Models are not included.")
                        .font(.subheadline)
                }
                Section {
                    FormField("Passphrase", text: $passphrase, kind: .secret).disabled(working)
                    FormField("Repeat (export only)", text: $confirm, kind: .secret).disabled(working)
                } footer: {
                    Text("at least \(BackupService.minPassphrase) characters · it cannot be recovered")
                }
                switch status {
                case .idle: EmptyView()
                case .working(let s):
                    Section {
                        ProgressView { Text(s.hasSuffix("…") ? String(s.dropLast()) : s).font(.footnote) }
                    }
                case .done(let s): Section { Text(s).font(.subheadline) }
                case .failed(let s): Section { Text(s).font(.subheadline).foregroundStyle(.red) }
                }
                Section {
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 12) { actions }
                        VStack(spacing: 12) { actions }
                    }
                }
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
            }
            .formStyle(.grouped)
            .navigationTitle("Export & import")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() }.disabled(working) }
            }
        }
        .presentationDetents([.large])
        .interactiveDismissDisabled(working)
        .fileExporter(
            isPresented: Binding(get: { exported != nil }, set: { if !$0 { exported = nil } }),
            document: exported,
            contentType: .data,
            defaultFilename: "autobot-\(Date().formatted(.iso8601.year().month().day())).autobot",
        ) { result in
            if let url = exported?.url { try? FileManager.default.removeItem(at: url) }
            switch result {
            case .success: status = .done("Exported. Keep the passphrase: without it the file cannot be opened.")
            case .failure(let error): status = .failed(error.localizedDescription)
            }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.data, .item]) { result in
            if case .success(let url) = result { importBundle(url) }
        }
    }

    @ViewBuilder private var actions: some View {
        Button { importing = true } label: {
            Group { if working && !exporting { ProgressView() } else { Text("Import") } }.frame(maxWidth: .infinity)
        }
        .glassButton()
        .controlSize(.large)
        .disabled(passphrase.isEmpty || working)
        Button { export() } label: {
            Group { if working && exporting { ProgressView() } else { Text("Export") } }.frame(maxWidth: .infinity)
        }
        .glassProminentButton()
        .controlSize(.large)
        .disabled(!exportable || working)
    }

    private func export() {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("autobot-\(UUID().uuidString).autobot")
        status = .working("export…")
        exporting = true
        Task {
            do {
                try await BackupService(app: app).export(to: url, passphrase: passphrase) { status = .working($0) }
                status = .working("choose where to save…")
                exported = BundleFile(url: url)
            } catch {
                status = .failed(error.localizedDescription.isEmpty ? "export failed" : error.localizedDescription)
            }
        }
    }

    private func importBundle(_ url: URL) {
        status = .working("import…")
        exporting = false
        Task {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            do {
                let added = try await BackupService(app: app).import(from: url, passphrase: passphrase) { status = .working($0) }
                status = .done("Imported \(counted(added.conversations, "session")), \(counted(added.images, "image")), \(counted(added.providers, "provider")), \(counted(added.memories, "memory note")), \(counted(added.studio, "Studio asset")). Add API keys again under Model providers.")
            } catch {
                status = .failed(error.localizedDescription.isEmpty ? "import failed" : error.localizedDescription)
            }
        }
    }
}
