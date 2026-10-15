import AutobotShared
import SwiftUI

/// One row of the session list.
struct ConversationItem: Identifiable, Equatable {
    let id: String
    let title: String
    let subtitle: String
    let updatedAt: Int64
    let incognito: Bool
    var running = false
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
    case folderMenu(id: String, name: String)
    case renameFolder(id: String, name: String)
    case deleteFolder(id: String, name: String)

    var id: String {
        switch self {
        case .rename(let id, _): "rename:\(id)"
        case .move(let ids, _): "move:\(ids.joined(separator: ","))"
        case .newFolder: "new-folder"
        case .folderMenu(let id, _): "folder:\(id)"
        case .renameFolder(let id, _): "rename-folder:\(id)"
        case .deleteFolder(let id, _): "delete-folder:\(id)"
        }
    }
}

/**
 All sessions (port of `ChatListScreen.kt`): live indicators, incognito toggle, new session,
 search over titles and messages, sorting, filters (all, pinned, incognito, folders, archive),
 day groups, swipe / context actions, multi-select, and deletion with undo.
 */
struct ChatListScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c
    /// `nil` until the user toggles; falls back to the persisted default.
    @State private var incognitoOverride: Bool?
    @State private var filterKey: String
    @State private var query: String
    @State private var hits: [String: ChatSearchHit]?
    @State private var selection: Set<String> = []
    @State private var dialog: ListDialog?
    @State private var nameDraft = ""
    @AppStorage("chatSort") private var sortKey = "RECENT"

    init(filter: String = "all", query: String = "") {
        _filterKey = State(initialValue: filter)
        _query = State(initialValue: query)
    }

    private var incognito: Bool { incognitoOverride ?? app.settings.settings.incognitoByDefault }
    private var sort: ChatSort { ChatSort.companion.parse(name: sortKey) }
    private var selecting: Bool { !selection.isEmpty }

    private struct Model {
        var sections: [ChatListSectionModel] = []
        var total = 0
        var live = 0
        var filter: any ChatFilter = ChatFilterAll.shared
        var showIncognitoChip = false
        var archivedCount = 0
        var folderCounts: [String: Int] = [:]
        var items: [ConversationItem] { sections.flatMap(\.items) }
    }

    private var model: Model {
        let names = Dictionary(app.providers.providers.map { ($0.id, $0.displayName) }, uniquingKeysWith: { a, _ in a })
        let running = app.agent.running
        let hidden = app.chats.hidden
        let visible = app.conversations.conversations.filter { !hidden.contains($0.id) }
        let byId = Dictionary(visible.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        let entries = visible.map { conv in
            ChatEntry(
                id: conv.id, title: conv.title, createdAt: conv.createdAt, updatedAt: conv.updatedAt, pinned: conv.pinned,
                archived: conv.archived, folderId: conv.folderId, incognito: app.conversations.isIncognito(conv.id),
            )
        }
        var filter = ChatFilterCompanion.shared.parse(key: filterKey)
        // A deleted folder falls back to everything.
        if let folder = filter as? ChatFilterFolder, !app.conversations.folders.contains(where: { $0.id == folder.id }) { filter = ChatFilterAll.shared }
        let searched = hits.map { h in entries.filter { h[$0.id] != nil } } ?? entries
        let organizer = ChatOrganizer.shared
        let sections = organizer.sections(entries: searched, filter: filter, sort: sort, startOfToday: Self.startOfToday()).map { section in
            ChatListSectionModel(label: section.group.label, items: section.entries.compactMap { e in
                guard let conv = byId[e.id] else { return nil }
                return ConversationItem(
                    id: conv.id,
                    title: conv.title,
                    subtitle: [conv.providerId.flatMap { names[$0] }, conv.model].compactMap { $0 }.joined(separator: " · "),
                    updatedAt: conv.updatedAt,
                    incognito: e.incognito,
                    running: running.contains(conv.id),
                    pinned: conv.pinned,
                    archived: conv.archived,
                    folderId: conv.folderId,
                    hit: hits?[conv.id],
                )
            })
        }
        var m = Model()
        m.sections = sections
        m.total = entries.filter { !$0.archived }.count
        m.live = entries.filter { !$0.archived && running.contains($0.id) }.count
        m.filter = filter
        m.showIncognitoChip = organizer.showsIncognitoChip(entries: entries) || filter is ChatFilterIncognito
        m.archivedCount = entries.filter(\.archived).count
        m.folderCounts = organizer.folderCounts(entries: entries).reduce(into: [:]) { $0[$1.key] = Int(truncating: $1.value) }
        return m
    }

    var body: some View {
        let m = model
        let loaded = app.dataLoaded
        VStack(spacing: 0) {
            VStack(spacing: 0) {
                if selecting { selectionBar(m) } else { header(m) }
                searchRow
                chips(m)
            }
            .background(c.background)
            .hairlineEdge()
            if loaded && app.providers.providers.isEmpty {
                Panel(title: "No model provider", accent: AutobotColors.amber) {
                    Text("Add DeepSeek, OpenRouter, a local Ollama server or any OpenAI-compatible endpoint.")
                        .textStyle(.bodySmall)
                        .foregroundStyle(c.onSurfaceVariant)
                    TextAction("Add provider", color: c.primary) { nav.push(.providers) }
                }
                .padding(12)
            }
            if loaded && m.sections.isEmpty {
                empty(m)
            } else {
                list(m)
            }
        }
        .background(c.background)
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
        .confirmationDialog(folderMenuTitle, isPresented: folderMenuBinding, titleVisibility: .visible) {
            if case .folderMenu(let id, let name) = dialog {
                Button("Rename") { open(.renameFolder(id: id, name: name)) }
                Button("Delete folder", role: .destructive) { open(.deleteFolder(id: id, name: name)) }
                Button("Cancel", role: .cancel) { dialog = nil }
            }
        } message: {
            Text("Deleting a folder keeps its chats; they move out of it.")
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

    // MARK: Header

    private func header(_ m: Model) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                if !nav.path.isEmpty {
                    IconAction(symbol: "arrow.left", label: "Back") { nav.back() }
                }
                VStack(alignment: .leading, spacing: 0) {
                    Text("SESSIONS").textStyle(.titleLarge).foregroundStyle(c.onBackground)
                    MicroLabel("\(m.total) sessions · \(m.live) live")
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                PrivacyStatusPill(status: app.settings.settings.mode.privacyStatus) { nav.push(.privacyCenter) }
            }
            .padding(.leading, nav.path.isEmpty ? 16 : 4)
            .padding(.trailing, 12)
            .padding(.top, 10)
            .padding(.bottom, 6)
            HStack(spacing: 10) {
                Image(systemName: "lock.fill")
                    .font(.system(size: 12))
                    .foregroundStyle(incognito ? AutobotColors.uv : c.outline)
                VStack(alignment: .leading, spacing: 0) {
                    MicroLabel("incognito", color: incognito ? AutobotColors.uv : c.onSurfaceVariant)
                    MicroLabel("memory only · never written to disk", color: c.outline)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                RisoToggle(isOn: Binding(get: { incognito }, set: { incognitoOverride = $0 }))
                Button(action: newChat) {
                    HStack(spacing: 8) {
                        AutobotIconView(icon: .terminal, size: 16, color: incognito ? AutobotColors.paperInk : c.onPrimary)
                        Text("NEW").textStyle(.labelLarge)
                    }
                    .foregroundStyle(incognito ? AutobotColors.paperInk : c.onPrimary)
                    .padding(.horizontal, 14)
                    .frame(height: 40)
                    .background(RoundedRectangle(cornerRadius: AutobotShapes.small).fill(incognito ? AutobotColors.uv : c.primary))
                }
                .buttonStyle(PressStyle())
            }
            .padding(.leading, 16)
            .padding(.trailing, 12)
            .padding(.bottom, 8)
        }
    }

    /// Multi-select: count, then pin / delete, and more (rename one, move, archive, select all).
    private func selectionBar(_ m: Model) -> some View {
        let chosen = m.items.filter { selection.contains($0.id) }
        let ids = chosen.map(\.id)
        let allPinned = !chosen.isEmpty && chosen.allSatisfy(\.pinned)
        let allArchived = !chosen.isEmpty && chosen.allSatisfy(\.archived)
        return HStack(spacing: 0) {
            IconAction(symbol: "xmark", label: "Cancel selection") { selection = [] }
            VStack(alignment: .leading, spacing: 0) {
                Text("\(chosen.count) SELECTED").textStyle(.titleLarge).foregroundStyle(c.onBackground)
                MicroLabel("tap to add or remove")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            IconAction(symbol: allPinned ? "pin.slash" : "pin", label: allPinned ? "Unpin" : "Pin", tint: allPinned ? c.primary : c.onSurface) {
                app.conversations.setPinned(ids, !allPinned)
                selection = []
            }
            IconAction(symbol: "trash", label: "Delete", tint: c.error) { delete(ids, titles: chosen.map(\.title)) }
            Menu {
                if chosen.count == 1 { Button("Rename") { open(.rename(id: chosen[0].id, title: chosen[0].title)) } }
                Button("Move to folder") { open(.move(ids: ids, current: Set(chosen.map(\.folderId)).count == 1 ? chosen.first?.folderId : nil)) }
                Button(allArchived ? "Unarchive" : "Archive") {
                    app.conversations.setArchived(ids, !allArchived)
                    selection = []
                }
                Button("Select all") { selection = Set(m.items.map(\.id)) }
            } label: {
                Image(systemName: "ellipsis").frame(width: 44, height: 44).foregroundStyle(c.onSurface)
            }
            .accessibilityLabel("More actions")
        }
        .padding(.horizontal, 4)
        .padding(.top, 10)
        .padding(.bottom, 6)
    }

    private var searchRow: some View {
        HStack(spacing: 8) {
            ConsoleTextField(text: $query, placeholder: "Search titles and messages")
                .overlay(alignment: .trailing) {
                    if !query.isEmpty {
                        IconAction(symbol: "xmark.circle.fill", label: "Clear search", tint: c.outline, size: 16) { query = "" }
                            .padding(.trailing, 4)
                    }
                }
            Menu {
                ForEach(ChatSort.entries, id: \.self) { s in
                    Button { sortKey = s.name } label: {
                        if s == sort { Label(s.label, systemImage: "checkmark") } else { Text(s.label) }
                    }
                }
            } label: {
                Tag("\(sort.label) ▾")
            }
            .accessibilityLabel("Sort: \(sort.label)")
        }
        .padding(.leading, 16)
        .padding(.trailing, 12)
        .padding(.bottom, 6)
    }

    /// All · Pinned · Incognito · folders · Archive · + folder. Long-press a folder to rename or delete it.
    private func chips(_ m: Model) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                chip("All", key: "all", m)
                chip("Pinned", key: "pinned", m)
                if m.showIncognitoChip { chip("Incognito", key: "incognito", m, accent: AutobotColors.uv) }
                ForEach(app.conversations.folders) { f in
                    chip("\(f.name) \(m.folderCounts[f.id] ?? 0)", key: "folder:\(f.id)", m)
                        .contextMenu {
                            Button("Rename") { open(.renameFolder(id: f.id, name: f.name)) }
                            Button("Delete folder", role: .destructive) { open(.deleteFolder(id: f.id, name: f.name)) }
                        }
                        .onLongPressGesture { open(.folderMenu(id: f.id, name: f.name)) }
                }
                if m.archivedCount > 0 || m.filter is ChatFilterArchived { chip("Archive \(m.archivedCount)", key: "archived", m) }
                Tag("+ Folder", accent: c.outline) { open(.newFolder(moving: [])) }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 10)
        }
    }

    private func chip(_ text: String, key: String, _ m: Model, accent: Color? = nil) -> some View {
        let selected = m.filter.key == key
        return Tag(text, accent: selected ? (accent ?? c.primary) : c.onSurfaceVariant, filled: selected) {
            filterKey = key
            selection = []
        }
    }

    // MARK: List

    private func list(_ m: Model) -> some View {
        List {
            ForEach(m.sections) { section in
                if !section.label.isEmpty {
                    MicroLabel(section.label, color: c.outline)
                        .padding(.leading, 16)
                        .padding(.top, 14)
                        .padding(.bottom, 4)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .listRowInsets(EdgeInsets())
                        .listRowSeparator(.hidden)
                        .listRowBackground(c.background)
                }
                ForEach(section.items) { item in
                    row(item)
                }
            }
            Color.clear.frame(height: 24).listRowInsets(EdgeInsets()).listRowSeparator(.hidden).listRowBackground(c.background)
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(c.background)
        .environment(\.defaultMinListRowHeight, 0)
        .animation(.easeInOut(duration: 0.2), value: m.sections)
    }

    private func row(_ item: ConversationItem) -> some View {
        VStack(spacing: 0) {
            ConversationRow(item: item, selecting: selecting, selected: selection.contains(item.id)) {
                if selecting { toggle(item.id) } else { nav.push(.chat(conversationId: item.id)) }
            }
            Hairline().padding(.leading, 16)
        }
        .listRowInsets(EdgeInsets())
        .listRowSeparator(.hidden)
        .listRowBackground(selection.contains(item.id) ? c.primary.opacity(0.12) : c.background)
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
            Button(role: .destructive) { delete([item.id], titles: [item.title]) } label: { Label("Delete", systemImage: "trash") }
        }
        .swipeActions(edge: .leading, allowsFullSwipe: true) {
            Button { app.conversations.setArchived([item.id], !item.archived) } label: {
                Label(item.archived ? "Unarchive" : "Archive", systemImage: item.archived ? "tray.and.arrow.up" : "archivebox")
            }
            .tint(c.secondary)
        }
        .contextMenu {
            Button { open(.rename(id: item.id, title: item.title)) } label: { Label("Rename", systemImage: "pencil") }
            Button { app.conversations.setPinned([item.id], !item.pinned) } label: { Label(item.pinned ? "Unpin" : "Pin to top", systemImage: item.pinned ? "pin.slash" : "pin") }
            Button { open(.move(ids: [item.id], current: item.folderId)) } label: { Label("Move to folder", systemImage: "folder") }
            Button { app.conversations.setArchived([item.id], !item.archived) } label: {
                Label(item.archived ? "Unarchive" : "Archive", systemImage: item.archived ? "tray.and.arrow.up" : "archivebox")
            }
            Button { toggle(item.id) } label: { Label("Select", systemImage: "checkmark.circle") }
            Button(role: .destructive) { delete([item.id], titles: [item.title]) } label: { Label("Delete", systemImage: "trash") }
        }
    }

    private func empty(_ m: Model) -> some View {
        let (title, detail): (String, String) = if !query.trimmingCharacters(in: .whitespaces).isEmpty {
            ("no matches", "titles and messages on this device were searched")
        } else if m.filter is ChatFilterArchived {
            ("archive is empty", "archived chats are kept here, out of the list")
        } else if m.filter is ChatFilterPinned {
            ("nothing pinned", "pin a chat to keep it on top")
        } else if m.filter is ChatFilterFolder {
            ("empty folder", "long-press a chat, then move it here")
        } else {
            ("no sessions", "everything stays encrypted on this device")
        }
        return VStack(spacing: 6) {
            Spacer()
            AutobotIconView(icon: .terminal, size: 28, color: c.outline)
            MicroLabel(title)
            MicroLabel(detail, color: c.outline)
            Spacer()
        }
        .frame(maxWidth: .infinity)
        .padding(32)
    }

    @ViewBuilder private var undoToast: some View {
        if let pending = app.chats.pending.last {
            HStack(spacing: 12) {
                Text(pending.label).textStyle(.bodyMedium).foregroundStyle(c.inverseOnSurface).lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                TextAction("Undo", color: c.inversePrimary) { app.chats.undo(pending.batch) }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(RoundedRectangle(cornerRadius: AutobotShapes.small).fill(c.inverseSurface))
            .padding(.horizontal, 12)
            .padding(.bottom, 12)
            .transition(.move(edge: .bottom).combined(with: .opacity))
            .id(pending.batch)
        }
    }

    // MARK: Actions

    private func newChat() {
        let provider = app.providers.providers.first
        let conversation = app.conversations.create(incognito: incognito, providerId: provider?.id, model: provider?.defaultModel)
        nav.push(.chat(conversationId: conversation.id))
    }

    private func toggle(_ id: String) {
        if selection.contains(id) { selection.remove(id) } else { selection.insert(id) }
    }

    /// Hides the chats at once (running turns are cancelled) and deletes them unless undone in time.
    private func delete(_ ids: [String], titles: [String]) {
        let label = ids.count == 1 ? (titles.first.map { "Deleted “\($0)”" } ?? "Chat deleted") : "Deleted \(ids.count) chats"
        withAnimation(.easeInOut(duration: 0.2)) { _ = app.chats.trash(ids, label: label) }
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

    private var folderMenuTitle: String { if case .folderMenu(_, let name) = dialog { name } else { "" } }

    private var folderMenuBinding: Binding<Bool> {
        Binding(get: { if case .folderMenu = dialog { true } else { false } }, set: { if !$0, case .folderMenu = dialog { dialog = nil } })
    }

    private var deleteFolderTitle: String { if case .deleteFolder(_, let name) = dialog { "Delete “\(name)”?" } else { "" } }

    private var deleteFolderBinding: Binding<Bool> {
        Binding(get: { if case .deleteFolder = dialog { true } else { false } }, set: { if !$0, case .deleteFolder = dialog { dialog = nil } })
    }

    // Search

    private var searchKey: String { "\(query)|\(app.conversations.conversations.count)" }

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

private struct ConversationRow: View {
    let item: ConversationItem
    let selecting: Bool
    let selected: Bool
    let onClick: () -> Void
    @Environment(\.scheme) private var c

    var body: some View {
        Button(action: onClick) {
            HStack(spacing: 12) {
                ZStack {
                    if selecting {
                        Image(systemName: selected ? "checkmark.square.fill" : "square")
                            .font(.system(size: 16))
                            .foregroundStyle(selected ? c.primary : c.outline)
                    } else if item.running {
                        StatusDot(color: AutobotColors.acid, live: true)
                    } else if item.incognito {
                        StatusDot(color: AutobotColors.uv)
                    } else {
                        Rectangle().fill(c.outline).frame(width: 4, height: 4)
                    }
                }
                .frame(width: selecting ? 18 : 8)
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        if item.pinned {
                            Image(systemName: "pin.fill").font(.system(size: 10)).foregroundStyle(c.primary).accessibilityLabel("Pinned")
                        }
                        Text(highlighted(item.title, item.hit?.titleSpans ?? [])).textStyle(.bodyLarge).foregroundStyle(c.onSurface).lineLimit(1)
                    }
                    if let snippet = item.hit?.snippet {
                        Text(highlighted(snippet, item.hit?.snippetSpans ?? []))
                            .textStyle(.bodySmall)
                            .foregroundStyle(c.onSurfaceVariant)
                            .lineLimit(2)
                    }
                    Text([item.subtitle, relativeTime(item.updatedAt)].filter { !$0.isEmpty }.joined(separator: " · "))
                        .textStyle(.codeBlock)
                        .foregroundStyle(c.onSurfaceVariant)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.leading, 16)
            .padding(.trailing, 12)
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle())
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
