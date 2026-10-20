import AutobotShared
import SwiftUI

/// One row of the session list.
struct ConversationItem: Identifiable, Equatable {
    let id: String
    let title: String
    let subtitle: String
    let updatedAt: Int64
    let incognito: Bool
    var pinned = false
    var archived = false
    var folderId: String? = nil
    /// Search: highlighted title ranges and an excerpt of the matching message.
    var hit: ChatSearchHit? = nil
}

/// A heading ("Today", "Pinned", … or none) and its chats.
struct ChatListSectionModel: Identifiable, Equatable {
    var id: String { label.isEmpty ? "all" : label }
    let label: String
    let items: [ConversationItem]
}

/// Which dialog of the list is open.
private enum ListDialog: Identifiable, Equatable {
    case rename(id: String, title: String)
    case move(ids: [String], current: String?)
    case newFolder(moving: [String])
    case renameFolder(id: String, name: String)
    case deleteFolder(id: String, name: String)

    var id: String {
        switch self {
        case .rename(let id, _): "rename:\(id)"
        case .move(let ids, _): "move:\(ids.joined(separator: ","))"
        case .newFolder: "new-folder"
        case .renameFolder(let id, _): "rename-folder:\(id)"
        case .deleteFolder(let id, _): "delete-folder:\(id)"
        }
    }
}

/**
 All chats (port of `ChatListScreen.kt`): live indicators, new (or new incognito) chat,
 search over titles and messages, sorting, filters (all, pinned, incognito, folders, archive),
 day groups, swipe / context actions, multi-select, and deletion with undo.
 */
struct ChatListScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @State private var filterKey: String
    @State private var query: String
    @State private var hits: [String: ChatSearchHit]?
    @State private var selection: Set<String> = []
    @State private var dialog: ListDialog?
    @State private var nameDraft = ""
    @AppStorage("chatSort") private var sortKey = "RECENT"

    /// The list as the sidebar of a wide window: a chat opens beside it (the Run stack) instead of on top.
    let sidebar: Bool

    init(filter: String = "all", query: String = "", sidebar: Bool = false) {
        self.sidebar = sidebar
        _filterKey = State(initialValue: filter)
        _query = State(initialValue: query)
    }

    private var incognitoByDefault: Bool { app.settings.settings.incognitoByDefault }
    private var sort: ChatSort { ChatSort.companion.parse(name: sortKey) }
    private var selecting: Bool { !selection.isEmpty }

    /// The list's model, memoized ([ChatListModel]): rebuilt only when its inputs change.
    @State private var lists = ChatListModel()

    private var model: ChatListModel.Model {
        lists.model(.init(
            revision: app.conversations.revision,
            // Read for observation: the screen follows the list (the revision is not observed).
            count: app.conversations.conversations.count,
            folders: app.conversations.folders.map(\.id),
            providers: app.providers.providers.map { "\($0.id)\u{0}\($0.displayName)" },
            hidden: app.chats.hidden,
            filterKey: filterKey,
            sortKey: sortKey,
            hits: hits,
            startOfToday: Self.startOfToday(),
        )) { [app] in (app.conversations.conversations, app.conversations.folders, app.providers.providers) }
    }

    var body: some View {
        let _ = InkProbe.hit("body.chatList")
        let m = model
        let loaded = app.dataLoaded
        list(m)
            .overlay {
                if loaded && m.sections.isEmpty {
                    MarkUnavailable(ChatListModel.emptyText(query: query, filter: m.filter))
                        .allowsHitTesting(false)
                }
            }
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: ChatListWords.shared.SEARCH)
            .navigationTitle(selecting ? ChatListWords.shared.selected(count: Int32(m.items.filter { selection.contains($0.id) }.count)) : ChatListWords.shared.TITLE)
            .navigationBarTitleDisplayMode(.inline)
            .navigationBarBackButtonHidden(selecting)
            .toolbar {
                if selecting {
                    selectionToolbar(m)
                } else {
                    ToolbarItem(placement: .principal) {
                        HStack(spacing: 8) {
                            TitleWithDot(ChatListWords.shared.TITLE, mark: sidebar || nav.path.isEmpty)
                            LiveCountLabel(active: m.activeIds)
                        }
                    }
                    newToolbar
                }
            }
            .overlay(alignment: .bottom) { undoToast }
            .task(id: searchKey) { await runSearch() }
            .alert(nameTitle, isPresented: nameBinding) {
                TextField("Name", text: $nameDraft)
                    .textInputAutocapitalization(.sentences)
                Button(nameConfirm) { confirmName() }
                    .disabled(nameProblem != nil)
                Button("Cancel", role: .cancel) { dialog = nil }
            } message: {
                if let problem = nameProblem, !nameDraft.isEmpty { Text(problem) }
            }
            .confirmationDialog("Move to folder", isPresented: moveBinding, titleVisibility: .visible) {
                if case .move(let ids, _) = dialog {
                    Button("No folder") { app.conversations.move(ids, to: nil); finish() }
                    ForEach(app.conversations.folders) { f in
                        Button(f.name) { app.conversations.move(ids, to: f.id); finish() }
                    }
                    Button("New folder…") { open(.newFolder(moving: ids)) }
                    Button("Cancel", role: .cancel) { dialog = nil }
                }
            }
            .alert(deleteFolderTitle, isPresented: deleteFolderBinding) {
                Button("Delete", role: .destructive) {
                    if case .deleteFolder(let id, _) = dialog {
                        if filterKey == "folder:\(id)" { filterKey = "all" }
                        app.conversations.deleteFolder(id)
                    }
                    dialog = nil
                }
                Button("Cancel", role: .cancel) { dialog = nil }
            } message: {
                Text("Its chats are kept and move out of the folder.")
            }
    }

    // MARK: Toolbar

    /// New chat, and an incognito chat one tap away beside it (unless every new chat already is
    /// one, Settings › Incognito by default); the sort order and a new folder in the menu.
    @ToolbarContentBuilder private var newToolbar: some ToolbarContent {
        ToolbarItemGroup(placement: .topBarTrailing) {
            Menu {
                Picker("Sort", selection: $sortKey) {
                    ForEach(ChatSort.entries, id: \.self) { s in
                        Text(s.label).tag(s.name)
                    }
                }
                Button { open(.newFolder(moving: [])) } label: { Label("New folder", systemImage: "folder.badge.plus") }
            } label: {
                Label("Sort: \(sort.label)", systemImage: "arrow.up.arrow.down")
            }
            .accessibilityLabel("Sort: \(sort.label)")
            if !incognitoByDefault {
                Button { newChat(incognito: true) } label: {
                    Label(ChatListWords.shared.NEW_INCOGNITO, systemImage: "lock")
                }
            }
            Button { newChat(incognito: nil) } label: {
                Label(incognitoByDefault ? ChatListWords.shared.NEW_INCOGNITO : "New chat", systemImage: "square.and.pencil")
            }
        }
    }

    /// Multi-select: cancel, then pin / delete, and more (rename one, move, archive, select all).
    @ToolbarContentBuilder private func selectionToolbar(_ m: ChatListModel.Model) -> some ToolbarContent {
        let chosen = m.items.filter { selection.contains($0.id) }
        let ids = chosen.map(\.id)
        let allPinned = !chosen.isEmpty && chosen.allSatisfy(\.pinned)
        let allArchived = !chosen.isEmpty && chosen.allSatisfy(\.archived)
        ToolbarItem(placement: .topBarLeading) {
            Button { selection = [] } label: { Label("Cancel selection", systemImage: "xmark") }
        }
        ToolbarItemGroup(placement: .topBarTrailing) {
            Button {
                app.conversations.setPinned(ids, !allPinned)
                selection = []
            } label: {
                Label(allPinned ? "Unpin" : "Pin", systemImage: allPinned ? "pin.slash" : "pin")
            }
            Button(role: .destructive) { delete(ids, titles: chosen.map(\.title)) } label: {
                Label("Delete", systemImage: "trash")
            }
            .tint(.red)
            Menu {
                if chosen.count == 1 { Button("Rename") { open(.rename(id: chosen[0].id, title: chosen[0].title)) } }
                Button("Move to folder") { open(.move(ids: ids, current: Set(chosen.map(\.folderId)).count == 1 ? chosen.first?.folderId : nil)) }
                Button(allArchived ? "Unarchive" : "Archive") {
                    app.conversations.setArchived(ids, !allArchived)
                    selection = []
                }
                Button("Select all") { selection = Set(m.items.map(\.id)) }
            } label: {
                Label("More actions", systemImage: "ellipsis")
            }
            .accessibilityLabel("More actions")
        }
    }

    /// All · Pinned · Incognito · folders · Archive · + folder. Long-press a folder to rename or delete it.
    private func chips(_ m: ChatListModel.Model) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                chip("All", key: "all", m)
                chip("Pinned", key: "pinned", m)
                if m.showIncognitoChip { chip("Incognito", key: "incognito", m) }
                ForEach(app.conversations.folders) { f in
                    chip("\(f.name) \(m.folderCounts[f.id] ?? 0)", key: "folder:\(f.id)", m)
                        .contextMenu {
                            Button("Rename") { open(.renameFolder(id: f.id, name: f.name)) }
                            Button("Delete folder", role: .destructive) { open(.deleteFolder(id: f.id, name: f.name)) }
                        }
                }
                if m.archivedCount > 0 || m.filter is ChatFilterArchived { chip("Archive \(m.archivedCount)", key: "archived", m) }
                Button { open(.newFolder(moving: [])) } label: { Label("Folder", systemImage: "plus") }
                    .buttonStyle(.bordered)
                    .buttonBorderShape(.capsule)
                    .controlSize(.small)
                    .tint(.secondary)
                    .accessibilityLabel("New folder")
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 4)
        }
    }

    @ViewBuilder private func chip(_ text: String, key: String, _ m: ChatListModel.Model) -> some View {
        let selected = m.filter.key == key
        let button = Button {
            filterKey = key
            selection = []
        } label: {
            Text(text)
        }
        .buttonBorderShape(.capsule)
        .controlSize(.small)
        .accessibilityAddTraits(selected ? .isSelected : [])
        if selected {
            button.buttonStyle(.borderedProminent)
        } else {
            button.buttonStyle(.bordered).tint(.secondary)
        }
    }

    // MARK: List

    private func list(_ m: ChatListModel.Model) -> some View {
        List {
            chips(m)
                .listRowInsets(EdgeInsets())
                .listRowSeparator(.hidden)
                .listRowBackground(Color.clear)
            if app.dataLoaded && app.providers.providers.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    Label(ChatListWords.shared.NO_MODEL, systemImage: "exclamationmark.triangle")
                        .font(.subheadline)
                    Button(ChatListWords.shared.ADD_PROVIDER) { nav.push(.providers) }
                        .glassProminentButton()
                }
                .listRowSeparator(.hidden)
            }
            ForEach(m.sections) { section in
                Section {
                    ForEach(section.items) { item in
                        row(item)
                    }
                } header: {
                    if !section.label.isEmpty { Text(section.label) }
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(Color(.systemBackground))
        .animation(.inkDissolve, value: m.order)
    }

    private func row(_ item: ConversationItem) -> some View {
        ChatListRow(item: item, selecting: selecting, selected: selection.contains(item.id), current: sidebar && currentChat == item.id, actions: ChatListRow.Actions(
            open: { if selecting { toggle(item.id) } else { openChat(item.id) } },
            delete: { delete([item.id], titles: [item.title]) },
            archive: { app.conversations.setArchived([item.id], !item.archived) },
            pin: { app.conversations.setPinned([item.id], !item.pinned) },
            rename: { open(.rename(id: item.id, title: item.title)) },
            move: { open(.move(ids: [item.id], current: item.folderId)) },
            select: { toggle(item.id) },
        ))
        .equatable()
        // On the row as the List sees it: inside the equatable view it was ignored.
        .listRowBackground((selection.contains(item.id) || (sidebar && currentChat == item.id)) ? Rectangle().fill(.tint.opacity(0.15)) : nil)
    }

    @ViewBuilder private var undoToast: some View {
        if let pending = app.chats.pending.last {
            HStack(spacing: 12) {
                Text(pending.label).font(.subheadline).lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button("Undo") { app.chats.undo(pending.batch) }
                    .font(.subheadline.weight(.semibold))
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 12)
            .glassSurface(in: Capsule())
            .padding(.horizontal, 16)
            .padding(.bottom, 12)
            .transition(.move(edge: .bottom).combined(with: .opacity))
            .id(pending.batch)
        }
    }

    /// The chat open beside the list (sidebar on a wide window): the Run stack's top.
    private var currentChat: String? {
        if case .chat(let id) = nav.paths[.run]?.last { return id }
        return nil
    }

    /// Opens a chat: pushed on this stack, or, as the sidebar of a wide window, shown beside the list.
    private func openChat(_ id: String) {
        if sidebar {
            nav.show(.chat(conversationId: id), in: .run)
        } else {
            nav.push(.chat(conversationId: id))
        }
    }

    // MARK: Actions

    /// A new chat: incognito when [incognito] is true, else as Settings › Incognito by default says.
    private func newChat(incognito: Bool?) {
        let provider = app.providers.providers.first
        let conversation = app.conversations.create(incognito: incognito ?? incognitoByDefault, providerId: provider?.id, model: provider?.defaultModel)
        openChat(conversation.id)
    }

    private func toggle(_ id: String) {
        if selection.contains(id) { selection.remove(id) } else { selection.insert(id) }
    }

    /// Hides the chats at once (running turns are cancelled) and deletes them unless undone in time.
    private func delete(_ ids: [String], titles: [String]) {
        let label = ChatListModel.deletedLabel(count: ids.count, title: titles.first)
        withAnimation(.inkDissolve) { _ = app.chats.trash(ids, label: label) }
        selection = []
    }

    private func open(_ d: ListDialog) {
        switch d {
        case .rename(_, let title): nameDraft = title
        case .renameFolder(_, let name): nameDraft = name
        case .newFolder: nameDraft = ""
        default: break
        }
        // A confirmation dialog must close before the next one opens.
        if dialog != nil {
            dialog = nil
            Task { @MainActor in dialog = d }
        } else {
            dialog = d
        }
    }

    private func finish() {
        dialog = nil
        selection = []
    }

    // Name prompt: rename a chat, create or rename a folder.

    private var nameTitle: String {
        switch dialog {
        case .rename: "Rename chat"
        case .newFolder: "New folder"
        case .renameFolder: "Rename folder"
        default: ""
        }
    }

    private var nameConfirm: String {
        if case .newFolder = dialog { return "Create" }
        return "Rename"
    }

    private var nameProblem: String? {
        switch dialog {
        case .rename: ChatNames.shared.title(title: nameDraft) == nil ? "Enter a name" : nil
        case .newFolder: app.conversations.folderProblem(nameDraft)
        case .renameFolder(let id, _): app.conversations.folderProblem(nameDraft, except: id)
        default: nil
        }
    }

    private func confirmName() {
        switch dialog {
        case .rename(let id, _): app.conversations.rename(id, nameDraft)
        case .newFolder(let moving):
            if let folder = app.conversations.createFolder(nameDraft) {
                if moving.isEmpty { filterKey = "folder:\(folder.id)" } else { app.conversations.move(moving, to: folder.id) }
            }
        case .renameFolder(let id, _): app.conversations.renameFolder(id, nameDraft)
        default: break
        }
        finish()
    }

    private var nameBinding: Binding<Bool> {
        Binding(get: {
            switch dialog {
            case .rename, .newFolder, .renameFolder: true
            default: false
            }
        }, set: { if !$0 { dialog = nil } })
    }

    private var moveBinding: Binding<Bool> {
        Binding(get: { if case .move = dialog { true } else { false } }, set: { if !$0, case .move = dialog { dialog = nil } })
    }

    private var deleteFolderTitle: String { if case .deleteFolder(_, let name) = dialog { "Delete “\(name)”?" } else { "" } }

    private var deleteFolderBinding: Binding<Bool> {
        Binding(get: { if case .deleteFolder = dialog { true } else { false } }, set: { if !$0, case .deleteFolder = dialog { dialog = nil } })
    }

    // Search

    /// The query only: a new or renamed chat does not search every chat again.
    private var searchKey: String { query }

    private func runSearch() async {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else {
            hits = nil
            return
        }
        try? await Task.sleep(for: .milliseconds(150))
        guard !Task.isCancelled else { return }
        let found = await app.chats.search(q, in: app.conversations.conversations)
        guard !Task.isCancelled else { return }
        hits = found
    }

    private static func startOfToday() -> Int64 { Int64(Calendar.current.startOfDay(for: Date()).timeIntervalSince1970 * 1000) }
}

/// "N live": the only part of the header that follows the running set.
struct LiveCountLabel: View {
    let active: Set<String>
    @Environment(AppContainer.self) private var app

    /// Only what runs: the list itself shows how many chats there are.
    static func text(live: Int) -> String? { ChatListWords.shared.live(live: Int32(live)) }

    var body: some View {
        if let text = Self.text(live: app.agent.running.intersection(active).count) { CapsuleTag(text) }
    }
}

/**
 One list row with its swipe actions and context menu, equatable on what it shows: when the list
 changes (a chat moves, one title changes) the other visible rows are skipped whole, instead of
 rebuilding their row modifiers, swipe actions and menus on every screen update.
 */
private struct ChatListRow: View, Equatable {
    struct Actions {
        let open: () -> Void
        let delete: () -> Void
        let archive: () -> Void
        let pin: () -> Void
        let rename: () -> Void
        let move: () -> Void
        let select: () -> Void
    }

    let item: ConversationItem
    let selecting: Bool
    let selected: Bool
    /// Open beside the list (sidebar).
    var current = false
    let actions: Actions

    nonisolated static func == (a: Self, b: Self) -> Bool { a.item == b.item && a.selecting == b.selecting && a.selected == b.selected && a.current == b.current }

    var body: some View {
        ConversationRow(item: item, selecting: selecting, selected: selected, onClick: actions.open)
            .equatable()
            .transition(.inkDissolve)
            .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                Button(role: .destructive, action: actions.delete) { Label("Delete", systemImage: "trash") }
            }
            .swipeActions(edge: .leading, allowsFullSwipe: true) {
                Button(action: actions.archive) {
                    Label(item.archived ? "Unarchive" : "Archive", systemImage: item.archived ? "tray.and.arrow.up" : "archivebox")
                }
                .tint(.indigo)
            }
            .contextMenu {
                Button(action: actions.rename) { Label("Rename", systemImage: "pencil") }
                Button(action: actions.pin) { Label(item.pinned ? "Unpin" : "Pin to top", systemImage: item.pinned ? "pin.slash" : "pin") }
                Button(action: actions.move) { Label("Move to folder", systemImage: "folder") }
                Button(action: actions.archive) {
                    Label(item.archived ? "Unarchive" : "Archive", systemImage: item.archived ? "tray.and.arrow.up" : "archivebox")
                }
                Button(action: actions.select) { Label("Select", systemImage: "checkmark.circle") }
                Button(role: .destructive, action: actions.delete) { Label("Delete", systemImage: "trash") }
            }
    }
}

/// Equatable on what it shows (the click action is not compared): a list change re-renders only
/// the rows that changed.
private struct ConversationRow: View, Equatable {
    let item: ConversationItem
    let selecting: Bool
    let selected: Bool
    let onClick: () -> Void
    @Environment(AppContainer.self) private var app
    @Environment(\.scheme) private var c

    nonisolated static func == (a: Self, b: Self) -> Bool { a.item == b.item && a.selecting == b.selecting && a.selected == b.selected }

    var body: some View {
        let _ = InkProbe.hit("body.conversationRow")
        let minute = MinuteTicker.shared.now
        Button(action: onClick) {
            HStack(spacing: 12) {
                // A marker only when it says something (selecting, running, incognito), so plain
                // rows line up with the list's inset.
                let running = app.agent.running.contains(item.id)
                if selecting {
                    Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                        .font(.title3)
                        .foregroundStyle(selected ? AnyShapeStyle(.tint) : AnyShapeStyle(.tertiary))
                        .accessibilityHidden(true)
                } else if running {
                    ProgressView().controlSize(.small).accessibilityLabel("Running")
                } else if item.incognito {
                    Image(systemName: "lock.fill").font(.caption).foregroundStyle(.secondary).accessibilityLabel("Incognito")
                }
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        if item.pinned {
                            Image(systemName: "pin.fill").font(.caption2).foregroundStyle(.tint).accessibilityLabel("Pinned")
                        }
                        title.font(.body).foregroundStyle(.primary).lineLimit(1)
                    }
                    if let snippet = item.hit?.snippet {
                        Text(highlighted(snippet, item.hit?.snippetSpans ?? []))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                    }
                    Text([item.subtitle, relativeTime(item.updatedAt, now: minute)].filter { !$0.isEmpty }.joined(separator: " · "))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .contentShape(Rectangle())
        }
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    /// The title; an attributed string only when search marks part of it (plain text lays out faster).
    private var title: Text {
        guard let spans = item.hit?.titleSpans, !spans.isEmpty else { return Text(verbatim: item.title) }
        return Text(highlighted(item.title, spans))
    }

    /// [text] with the UTF-16 [spans] (shared `ChatSearch` offsets) marked.
    private func highlighted(_ text: String, _ spans: [Range<Int>]) -> AttributedString {
        var out = AttributedString(text)
        let utf16 = text.utf16.count
        for span in spans where span.lowerBound >= 0 && span.upperBound <= utf16 {
            let lower = String.Index(utf16Offset: span.lowerBound, in: text)
            let upper = String.Index(utf16Offset: span.upperBound, in: text)
            guard let range = Range(lower ..< upper, in: out) else { continue }
            out[range].backgroundColor = c.primary.opacity(0.28)
            out[range].inlinePresentationIntent = .stronglyEmphasized
        }
        return out
    }
}

/**
 The chat list's sections and counts, memoized: rebuilding them maps every chat into a shared
 `ChatEntry` and runs the organizer, so it happens when an input changes (a chat, a folder, the
 filter, the sort, search hits, hidden chats, provider names, the day), not on every render.
 */
@MainActor
final class ChatListModel {
    struct Model {
        var sections: [ChatListSectionModel] = []
        var total = 0
        /// Non-archived chats (the header counts how many of them run).
        var activeIds: Set<String> = []
        var filter: any ChatFilter = ChatFilterAll.shared
        var showIncognitoChip = false
        var archivedCount = 0
        var folderCounts: [String: Int] = [:]
        /// Changes when the visible order changes (drives the list animation).
        var order = 0
        var items: [ConversationItem] { sections.flatMap(\.items) }
    }

    struct Key: Equatable {
        var revision: Int
        var count: Int
        var folders: [String]
        var providers: [String]
        var hidden: Set<String>
        var filterKey: String
        var sortKey: String
        var hits: [String: ChatSearchHit]?
        var startOfToday: Int64
    }

    private var key: Key?
    private var cached = Model()
    private var lastOrder: [String] = []
    /// Times the model was rebuilt (tests).
    private(set) var builds = 0

    /// The model for [key]; [inputs] (conversations, folders, providers) are read only to rebuild.
    func model(_ key: Key, inputs: () -> ([Conversation], [ChatFolder], [Provider])) -> Model {
        if key == self.key { return cached }
        let (conversations, folders, providers) = inputs()
        var m = Self.build(key, conversations: conversations, folders: folders, providers: providers)
        let order = m.sections.flatMap { $0.items.map(\.id) }
        m.order = order == lastOrder ? cached.order : cached.order + 1
        lastOrder = order
        self.key = key
        cached = m
        builds += 1
        return m
    }

    /// What an empty list says, by why it is empty.
    /// One line for an empty list (the shared words, as on Android).
    static func emptyText(query: String, filter: any ChatFilter) -> String {
        ChatListWords.shared.empty(searching: !query.trimmingCharacters(in: .whitespaces).isEmpty, filter: filter)
    }

    /// Titles of the catalog's models by id ("catalog:qwen3-4b-2507-q4_0" → "Qwen3 4B Instruct 2507").
    private static let catalogTitles = Dictionary(Catalog.all.map { ($0.id, $0.title) }, uniquingKeysWith: { a, _ in a })

    /// A chat's model as a person reads it: an on-device catalog model by its title, anything else as named.
    static func modelLabel(_ id: String) -> String { catalogTitles[id] ?? id }

    /// The undo toast after deleting [count] chats ([title]: the only one's).

    static func deletedLabel(count: Int, title: String?) -> String {
        count == 1 ? (title.map { "Deleted “\($0)”" } ?? "Chat deleted") : "Deleted \(counted(count, "chat"))"
    }

    private static func build(_ key: Key, conversations: [Conversation], folders: [ChatFolder], providers: [Provider]) -> Model {
        let names = Dictionary(providers.map { ($0.id, $0.displayName) }, uniquingKeysWith: { a, _ in a })
        let visible = conversations.filter { !key.hidden.contains($0.id) }
        let byId = Dictionary(visible.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        let entries = visible.map { conv in
            ChatEntry(
                id: conv.id, title: conv.title, createdAt: conv.createdAt, updatedAt: conv.updatedAt, pinned: conv.pinned,
                archived: conv.archived, folderId: conv.folderId, incognito: conv.id.hasPrefix(ConversationRepository.incognitoPrefix),
            )
        }
        var filter = ChatFilterCompanion.shared.parse(key: key.filterKey)
        // A deleted folder falls back to everything.
        if let folder = filter as? ChatFilterFolder, !folders.contains(where: { $0.id == folder.id }) { filter = ChatFilterAll.shared }
        let searched = key.hits.map { h in entries.filter { h[$0.id] != nil } } ?? entries
        let organizer = ChatOrganizer.shared
        let sort = ChatSort.companion.parse(name: key.sortKey)
        let sections = organizer.sections(entries: searched, filter: filter, sort: sort, startOfToday: key.startOfToday).map { section in
            ChatListSectionModel(label: section.group.label, items: section.entries.compactMap { e in
                guard let conv = byId[e.id] else { return nil }
                return ConversationItem(
                    id: conv.id,
                    title: conv.title,
                    subtitle: [conv.providerId.flatMap { names[$0] }, conv.model.map(modelLabel)].compactMap { $0 }.joined(separator: " · "),
                    updatedAt: conv.updatedAt,
                    incognito: e.incognito,
                    pinned: conv.pinned,
                    archived: conv.archived,
                    folderId: conv.folderId,
                    hit: key.hits?[conv.id],
                )
            })
        }
        var m = Model()
        m.sections = sections
        let active = entries.filter { !$0.archived }
        m.total = active.count
        m.activeIds = Set(active.map(\.id))
        m.filter = filter
        m.showIncognitoChip = organizer.showsIncognitoChip(entries: entries) || filter is ChatFilterIncognito
        m.archivedCount = entries.count - active.count
        m.folderCounts = organizer.folderCounts(entries: entries).reduce(into: [:]) { $0[$1.key] = Int(truncating: $1.value) }
        return m
    }
}
