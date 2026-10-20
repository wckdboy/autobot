import AutobotShared
import SwiftUI

/// The private vault (port of `VaultScreen.kt`): the switch, the embedding model, adding files, indexing, search and the documents.
struct VaultScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
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
                    Button { confirmDeleteAll = true } label: { Image(systemName: "trash") }
                        .accessibilityLabel(VaultWords.shared.DELETE_ALL)
                }
                if vault.modelId != nil && !vault.running {
                    Button { picking = true } label: { Image(systemName: "plus") }
                        .accessibilityLabel(VaultWords.shared.ADD)
                }
            }
            List {
                Section {
                    InfoToggle(
                        title: VaultWords.shared.AGENT_SEARCH,
                        info: VaultWords.shared.AGENT_SEARCH_INFO,
                        isOn: Binding(get: { settings.vaultEnabled }, set: { app.settings.setVaultEnabled($0) }),
                    )
                    PushRow(title: VaultWords.shared.EMBEDDING_MODEL, value: model?.title ?? VaultWords.shared.NO_MODEL) { nav.switchTo(.models) }
                    if let progress = vault.progress {
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                Text(progress.name.map { "indexing \($0) · \(progress.done)/\(progress.total)" } ?? "reading files…")
                                    .font(.footnote).foregroundStyle(.secondary)
                                Spacer()
                                Button("Stop") { vault.cancel() }.buttonStyle(.borderless)
                            }
                            ProgressView(value: progress.fraction)
                        }
                    } else if vault.stale > 0 {
                        HStack {
                            Label(VaultWords.shared.stale(count: Int32(vault.stale)), systemImage: "exclamationmark.triangle")
                                .font(.footnote)
                                .foregroundStyle(.orange)
                            Spacer()
                            Button(VaultWords.shared.REINDEX.sentenceCased) { vault.reindex() }.buttonStyle(.borderless)
                        }
                    }
                }
                if vault.searching || vault.results != nil {
                    Section {
                        if vault.searching { ProgressView().frame(maxWidth: .infinity) }
                        if let results = vault.results {
                            if results.isEmpty && !vault.searching {
                                Text(VaultWords.shared.NO_MATCH).foregroundStyle(.secondary)
                            }
                            ForEach(Array(results.enumerated()), id: \.offset) { _, hit in
                                VStack(alignment: .leading, spacing: 3) {
                                    Text("\(hit.position) · \(String(format: "%.2f", hit.score))").font(.caption).foregroundStyle(.secondary)
                                    Text(hit.snippet).font(.subheadline).lineLimit(6)
                                }
                            }
                        }
                    }
                }
                Section(VaultWords.shared.count(documents: Int32(vault.documents.count), passages: Int32(vault.documents.reduce(0) { $0 + Int($1.chunks) })).sentenceCased) {
                    if vault.documents.isEmpty {
                        // Files need an embedding model; without one there is no "+" to point at.
                        if vault.modelId == nil {
                            MarkUnavailableView(title: VaultWords.shared.empty(hasModel: false)) {
                                Button("Models") { nav.switchTo(.models) }.primaryActionStyle()
                            }
                        } else {
                            MarkUnavailableView(VaultWords.shared.empty(hasModel: true))
                        }
                    } else {
                        ForEach(vault.documents, id: \.id) { doc in row(doc) }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .modifier(VaultSearch(enabled: !vault.documents.isEmpty, query: $query) { vault.search(query) })
            .onChange(of: query) { _, q in if q.isEmpty { vault.clearResults() } }
        }
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
        VStack(alignment: .leading, spacing: 3) {
            Text(doc.name).lineLimit(2)
            Text(Self.details(doc)).font(.caption).foregroundStyle(.secondary)
        }
        .swipeActions {
            Button("Remove", systemImage: "trash", role: .destructive) { app.vault.remove(doc.id) }
        }
        .contextMenu {
            Button("Remove \(doc.name)", systemImage: "trash", role: .destructive) { app.vault.remove(doc.id) }
        }
        .accessibilityAction(named: "Remove \(doc.name)") { app.vault.remove(doc.id) }
    }

    static func details(_ doc: VaultDocumentInfo) -> String {
        var parts = [doc.type, formatBytes(doc.sizeBytes)]
        if doc.pages > 0 { parts.append("\(doc.pages) pages") }
        parts.append("\(doc.chunks) passages")
        if !doc.current { parts.append("needs re-index") }
        return parts.joined(separator: " · ")
    }
}

/// The vault's search field, in the navigation bar once there are documents; submit searches.
private struct VaultSearch: ViewModifier {
    let enabled: Bool
    @Binding var query: String
    let onSubmit: () -> Void

    func body(content: Content) -> some View {
        if enabled {
            content
                .searchable(text: $query, prompt: VaultWords.shared.SEARCH)
                .onSubmit(of: .search, onSubmit)
        } else {
            content
        }
    }
}
