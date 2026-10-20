import AutobotShared
import SwiftUI

/// Local memory (port of `MemoryScreen.kt`): the switch, search, and every note to read, edit or delete.
struct MemoryScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c
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
                    IconAction(symbol: "trash", label: MemoryWords.shared.DELETE_ALL, tint: c.onSurfaceVariant, size: 18) { confirmDeleteAll = true }
                }
                IconAction(symbol: "plus", label: MemoryWords.shared.ADD, tint: c.primary) { editor = NoteDraft() }
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    SwitchRow(
                        title: MemoryWords.shared.USE_IN_CHATS,
                        isOn: Binding(get: { settings.memoryEnabled }, set: { app.settings.setMemoryEnabled($0) }),
                        info: MemoryWords.shared.USE_INFO,
                    )
                    ConsoleTextField(text: $query, placeholder: MemoryWords.shared.SEARCH, kind: .mono)
                        .padding(.horizontal, Tokens.Spacing.gutter)
                        .padding(.vertical, 6)
                    SectionLabel(MemoryWords.shared.count(total: Int32(total)))
                        .padding(.horizontal, Tokens.Spacing.gutter)
                        .padding(.vertical, 8)
                    if notes.isEmpty {
                        EmptyState(MemoryWords.shared.empty(total: Int32(total)))
                    } else {
                        ForEach(notes) { note in row(note).transition(.inkDissolve) }
                            .padding(.horizontal, Tokens.Spacing.gutter)
                    }
                }
                .animation(.inkDissolve, value: notes.map(\.id))
                .padding(.bottom, 96)
            }
        }
        .background(c.background)
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
        VStack(spacing: 0) {
            HStack(alignment: .top, spacing: 12) {
                Button { editor = NoteDraft(id: note.id, text: note.text, tags: note.tags.joined(separator: ", ")) } label: {
                    VStack(alignment: .leading, spacing: 3) {
                        MicroLabel("\(note.id) · \(relativeTime(note.updatedAt))")
                        Text(note.text).textStyle(.bodyMedium).foregroundStyle(c.onSurface).lineLimit(4).multilineTextAlignment(.leading)
                        if !note.tags.isEmpty {
                            Text(note.tags.map { "#\($0)" }.joined(separator: " ")).textStyle(.micro).foregroundStyle(c.onSurfaceVariant)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(PressStyle())
                IconAction(symbol: "trash", label: "Delete note \(note.id)", tint: c.onSurfaceVariant, size: 16) { app.memory.delete(note.id) }
            }
            .padding(.vertical, 12)
            Hairline()
        }
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
    @Environment(\.scheme) private var c

    init(initial: NoteDraft, onDone: @escaping (MemoryRepository.SaveOutcome) -> Void, onCancel: @escaping () -> Void) {
        _draft = State(initialValue: initial)
        self.onDone = onDone
        self.onCancel = onCancel
    }

    var body: some View {
        let max = Int(MemoryRules.shared.MAX_TEXT)
        ScrollView {
            InkStack(alignment: .leading, spacing: 12) {
                SectionLabel(draft.id == nil ? "new note" : "edit note")
                ConsoleTextField(
                    text: Binding(get: { draft.text }, set: { draft.text = String($0.prefix(max)) }),
                    label: "Note",
                    minLines: 3,
                    maxLines: 10,
                )
                MicroLabel("\(draft.text.count) / \(max) · one fact per note · never store passwords or keys", lines: 2)
                ConsoleTextField(text: $draft.tags, placeholder: "work, travel", label: "Tags", kind: .mono)
                PrimaryButton("Save", enabled: !draft.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) {
                    onDone(app.memory.save(id: draft.id, text: draft.text, tags: draft.tags))
                }
                TextAction("Cancel", color: c.onSurfaceVariant, action: onCancel).frame(maxWidth: .infinity)
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 20)
        }
        .background(c.surfaceContainerLow.ignoresSafeArea())
        .presentationDetents([.large])
    }
}
