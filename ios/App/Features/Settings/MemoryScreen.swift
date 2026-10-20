import AutobotShared
import SwiftUI

/// Local memory (port of `MemoryScreen.kt`): the switch, search, and every note to read, edit or delete.
struct MemoryScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @State private var query = ""
    @State private var editor: NoteDraft?
    @State private var confirmDeleteAll = false
    @State private var message: String?

    var body: some View {
        let settings = app.settings.settings
        let total = app.memory.notes.count
        let notes = app.memory.filtered(query)
        VStack(spacing: 0) {
            AutobotTopBar(title: "Memory", onBack: { nav.back() }) {
                if total > 0 {
                    Button { confirmDeleteAll = true } label: { Image(systemName: "trash") }
                        .accessibilityLabel(MemoryWords.shared.DELETE_ALL)
                }
                Button { editor = NoteDraft() } label: { Image(systemName: "plus") }
                    .accessibilityLabel(MemoryWords.shared.ADD)
            }
            List {
                Section {
                    InfoToggle(
                        title: MemoryWords.shared.USE_IN_CHATS,
                        info: MemoryWords.shared.USE_INFO,
                        isOn: Binding(get: { settings.memoryEnabled }, set: { app.settings.setMemoryEnabled($0) }),
                    )
                }
                Section(MemoryWords.shared.count(total: Int32(total)).sentenceCased) {
                    if notes.isEmpty {
                        MarkUnavailableView(MemoryWords.shared.empty(total: Int32(total)))
                    } else {
                        ForEach(notes) { row($0) }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .searchable(text: $query, prompt: MemoryWords.shared.SEARCH)
            .animation(.default, value: notes.map(\.id))
        }
        .sheet(item: $editor) { draft in
            NoteEditorSheet(initial: draft) { outcome in
                editor = nil
                message = Self.describe(outcome)
            } onCancel: {
                editor = nil
            }
        }
        .risoAlert($confirmDeleteAll, title: "Delete all notes?", message: "All \(total) memory notes are deleted from this phone. This cannot be undone.", confirm: "Delete all") {
            app.memory.deleteAll()
        }
        .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("OK", role: .cancel) {}
        }
    }

    private func row(_ note: MemoryRecord) -> some View {
        Button { editor = NoteDraft(id: note.id, text: note.text, tags: note.tags.joined(separator: ", ")) } label: {
            VStack(alignment: .leading, spacing: 3) {
                Text(note.text).lineLimit(4).multilineTextAlignment(.leading)
                if !note.tags.isEmpty {
                    Text(note.tags.map { "#\($0)" }.joined(separator: " ")).font(.caption).foregroundStyle(.tint)
                }
                Text("\(note.id) · \(relativeTime(note.updatedAt))").font(.caption).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .foregroundStyle(.primary)
        .swipeActions {
            Button("Delete", systemImage: "trash", role: .destructive) { app.memory.delete(note.id) }
        }
        .contextMenu {
            Button("Delete note \(note.id)", systemImage: "trash", role: .destructive) { app.memory.delete(note.id) }
        }
        .accessibilityAction(named: "Delete note \(note.id)") { app.memory.delete(note.id) }
    }

    static func describe(_ outcome: MemoryRepository.SaveOutcome) -> String? {
        switch outcome {
        case .saved: nil
        case .duplicate(let id): "Already in memory (\(id)); tags merged."
        case .empty: "A note needs some text."
        case .full: "Memory is full (\(MemoryRules.shared.MAX_NOTES) notes). Delete some first."
        case .notFound: "That note no longer exists."
        }
    }
}

/// A note being written; [id] nil for a new one.
struct NoteDraft: Identifiable, Equatable {
    var id: String?
    var text = ""
    var tags = ""

    var key: String { id ?? "new" }
}

private struct NoteEditorSheet: View {
    let onDone: (MemoryRepository.SaveOutcome) -> Void
    let onCancel: () -> Void
    @State private var draft: NoteDraft
    @Environment(AppContainer.self) private var app

    init(initial: NoteDraft, onDone: @escaping (MemoryRepository.SaveOutcome) -> Void, onCancel: @escaping () -> Void) {
        _draft = State(initialValue: initial)
        self.onDone = onDone
        self.onCancel = onCancel
    }

    var body: some View {
        let max = Int(MemoryRules.shared.MAX_TEXT)
        NavigationStack {
            Form {
                Section {
                    TextField("Note", text: Binding(get: { draft.text }, set: { draft.text = String($0.prefix(max)) }), axis: .vertical)
                        .lineLimit(3 ... 10)
                } footer: {
                    Text("\(draft.text.count) / \(max) · one fact per note · never store passwords or keys")
                }
                Section("Tags") {
                    FormField("Tags", text: $draft.tags, prompt: "work, travel", kind: .mono)
                }
            }
            .formStyle(.grouped)
            .navigationTitle(draft.id == nil ? "New note" : "Edit note")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onCancel) }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { onDone(app.memory.save(id: draft.id, text: draft.text, tags: draft.tags)) }
                        .disabled(draft.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
        .presentationDetents([.large])
    }
}
