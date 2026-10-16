import AutobotShared
import SwiftUI

/// The private vault (port of `VaultScreen.kt`): the switch, the embedding model, adding files, indexing, search and the documents.
struct VaultScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c
    @State private var query = ""
    @State private var picking = false
    @State private var confirmDeleteAll = false

    var body: some View {
        let settings = app.settings.settings
        let vault = app.vault
        let model = vault.modelId.flatMap { app.library.get($0) }
        VStack(spacing: 0) {
            AutobotTopBar(title: "Vault", onBack: { nav.back() }) {
                if !vault.documents.isEmpty {
                    IconAction(symbol: "trash", label: "Delete all documents", tint: c.onSurfaceVariant, size: 18) { confirmDeleteAll = true }
                }
                if vault.modelId != nil && !vault.running {
                    IconAction(symbol: "plus", label: "Add files", tint: c.primary) { picking = true }
                }
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    SwitchRow(
                        title: "Let the agent search the vault",
                        subtitle: settings.vaultEnabled
                            ? "The agent can look up passages with vault_search."
                            : "Off: the agent neither sees nor searches your files.",
                        isOn: Binding(get: { settings.vaultEnabled }, set: { app.settings.setVaultEnabled($0) }),
                    )
                    Text("Indexed on this phone, encrypted, never uploaded.")
                        .textStyle(.bodyMedium)
                        .foregroundStyle(c.onSurface)
                        .padding(.horizontal, Tokens.Spacing.gutter)
                        .padding(.vertical, 6)
                    NavigationRow(
                        title: model?.title ?? "No embedding model",
                        subtitle: model == nil
                            ? "Download one in Models (embedding · vault) to add files"
                            : "Text, Markdown, PDF and photos (read with on-device OCR)",
                    ) { nav.switchTo(.models) }
                    if let progress = vault.progress {
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                MicroLabel(progress.name.map { "indexing \($0) · \(progress.done)/\(progress.total)" } ?? "reading files…")
                                Spacer()
                                Button("Stop") { vault.cancel() }.textStyle(.labelMedium).foregroundStyle(c.primary)
                            }
                            InkProgressBar(fraction: progress.fraction, height: 3, running: true)
                                .frame(height: 3)
                        }
                        .padding(.horizontal, Tokens.Spacing.gutter)
                        .padding(.vertical, 10)
                    } else if vault.stale > 0 {
                        VStack(alignment: .leading, spacing: 8) {
                            MicroLabel("\(vault.stale) document(s) use another embedding model and are not searched until re-indexed (from the stored text, no files needed)", lines: 3)
                            GhostButton("Re-index") { vault.reindex() }
                        }
                        .padding(.horizontal, Tokens.Spacing.gutter)
                        .padding(.vertical, 10)
                    }
                    if !vault.documents.isEmpty {
                        ConsoleTextField(text: $query, placeholder: "search your files", kind: .mono, onSubmit: { vault.search(query) })
                            .padding(.horizontal, Tokens.Spacing.gutter)
                            .padding(.vertical, 6)
                            .onChange(of: query) { _, q in if q.isEmpty { vault.clearResults() } }
                    }
                    if vault.searching {
                        InkProgressBar(fraction: 0.3, height: 2, running: true)
                            .frame(height: 2)
                            .padding(.horizontal, Tokens.Spacing.gutter)
                    }
                    if let results = vault.results {
                        if results.isEmpty && !vault.searching {
                            Text("No passages match.").textStyle(.bodyMedium).foregroundStyle(c.onSurfaceVariant).padding(Tokens.Spacing.gutter)
                        }
                        ForEach(Array(results.enumerated()), id: \.offset) { _, hit in
                            VStack(alignment: .leading, spacing: 3) {
                                MicroLabel("\(hit.position) · \(String(format: "%.2f", hit.score))")
                                Text(hit.snippet).textStyle(.bodySmall).foregroundStyle(c.onSurface).lineLimit(6)
                            }
                            .padding(.vertical, 10)
                            .padding(.horizontal, Tokens.Spacing.gutter)
                            Hairline()
                        }
                    }
                    SectionLabel("documents · \(vault.documents.count) · \(vault.documents.reduce(0) { $0 + Int($1.chunks) }) / \(VaultRules.shared.MAX_CHUNKS) passages")
                        .padding(.horizontal, Tokens.Spacing.gutter)
                        .padding(.vertical, 8)
                    if vault.documents.isEmpty {
                        Text("No files yet. Tap + to add notes, Markdown, PDFs or photos: they are split into passages and embedded on this phone.")
                            .textStyle(.bodyMedium)
                            .foregroundStyle(c.onSurfaceVariant)
                            .multilineTextAlignment(.center)
                            .frame(maxWidth: .infinity)
                            .padding(32)
                    } else {
                        ForEach(vault.documents, id: \.id) { doc in row(doc) }
                            .padding(.horizontal, Tokens.Spacing.gutter)
                    }
                }
                .padding(.bottom, 96)
            }
        }
        .background(c.background)
        .onAppear { vault.load() }
        .fileImporter(isPresented: $picking, allowedContentTypes: VaultImport.contentTypes, allowsMultipleSelection: true) { result in
            if case .success(let urls) = result, !urls.isEmpty { vault.add(urls) }
        }
        .risoAlert($confirmDeleteAll, title: "Empty the vault?", message: "All \(counted(vault.documents.count, "document")) and their index are deleted from this phone. Your original files are not touched.", confirm: "Delete all") {
            vault.clear()
        }
        .alert("Some files were not added", isPresented: Binding(get: { !vault.problems.isEmpty && !vault.running }, set: { if !$0 { vault.problems = [] } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(vault.problems.joined(separator: "\n"))
        }
    }

    private func row(_ doc: VaultDocumentInfo) -> some View {
        VStack(spacing: 0) {
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(doc.name).textStyle(.bodyMedium).foregroundStyle(c.onSurface).lineLimit(2)
                    MicroLabel(Self.details(doc))
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                IconAction(symbol: "trash", label: "Remove \(doc.name)", tint: c.onSurfaceVariant, size: 16) { app.vault.remove(doc.id) }
            }
            .padding(.vertical, 12)
            Hairline()
        }
    }

    static func details(_ doc: VaultDocumentInfo) -> String {
        var parts = [doc.type, formatBytes(doc.sizeBytes)]
        if doc.pages > 0 { parts.append("\(doc.pages) pages") }
        parts.append("\(doc.chunks) passages")
        if !doc.current { parts.append("needs re-index") }
        return parts.joined(separator: " · ")
    }
}
