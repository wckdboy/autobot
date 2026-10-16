import AutobotShared
import Foundation
import Observation

/// Chats deleted moments ago, still restorable until the undo window closes.
struct PendingDeletion: Equatable, Sendable {
    let batch: Int
    let ids: [String]
    let label: String
}

/// A search hit: highlighted title ranges and an excerpt of the matching message (shared `ChatSearch`).
struct ChatSearchHit: Equatable, Sendable {
    var titleSpans: [Range<Int>]
    var snippet: String?
    var snippetSpans: [Range<Int>]
}

/**
 Deleting and searching chats across the app (port of Android's `ChatLibrary`).

 Deletion is soft for [undoWindow]: [trash] cancels the chats' running turns at once and hides
 them ([pending]); then the conversation, its session log, search text and attached images are
 removed for good, unless [undo] came first. A process killed inside the window keeps the chats.
 */
@MainActor
@Observable
final class ChatLibrary {
    nonisolated static let undoWindow: Duration = .seconds(5)

    private(set) var pending: [PendingDeletion] = []
    @ObservationIgnored private var timers: [Int: Task<Void, Never>] = [:]
    @ObservationIgnored private var batches = 0
    @ObservationIgnored private let conversations: ConversationRepository
    @ObservationIgnored private let agent: any AgentRuntime
    @ObservationIgnored private let sessions: SessionStore
    @ObservationIgnored private let attachments: AttachmentRepository
    @ObservationIgnored private let window: Duration

    init(conversations: ConversationRepository, agent: any AgentRuntime, sessions: SessionStore, attachments: AttachmentRepository, window: Duration = ChatLibrary.undoWindow) {
        self.conversations = conversations
        self.agent = agent
        self.sessions = sessions
        self.attachments = attachments
        self.window = window
    }

    /// Ids hidden while their deletion can be undone.
    var hidden: Set<String> { Set(pending.flatMap(\.ids)) }

    /// Hides [ids] now and deletes them after the undo window. Returns the batch to [undo].
    @discardableResult
    func trash(_ ids: [String], label: String) -> Int {
        batches += 1
        let batch = batches
        var seen = Set<String>()
        let list = ids.filter { seen.insert($0).inserted }
        guard !list.isEmpty else { return batch }
        // A running turn is cancelled first, so nothing writes to a chat being deleted.
        list.forEach { agent.cancel($0) }
        pending.append(PendingDeletion(batch: batch, ids: list, label: label))
        let window = window
        timers[batch] = Task { [weak self] in
            try? await Task.sleep(for: window)
            guard !Task.isCancelled else { return }
            self?.commit(batch)
        }
        return batch
    }

    func undo(_ batch: Int) {
        timers.removeValue(forKey: batch)?.cancel()
        pending.removeAll { $0.batch == batch }
    }

    /// Ends the undo window of [batch] now.
    func commit(_ batch: Int) {
        timers.removeValue(forKey: batch)?.cancel()
        guard let entry = pending.first(where: { $0.batch == batch }) else { return }
        purge(entry.ids)
        pending.removeAll { $0.batch == batch }
    }

    /// "Delete all chats": every chat, at once and for good (no undo).
    func deleteAll() {
        timers.values.forEach { $0.cancel() }
        timers.removeAll()
        purge(conversations.conversations.map(\.id))
        pending.removeAll()
    }

    /**
     Stops the chats' agents and drops them from the list now; their logs, search text and
     attached images are deleted off the main thread (secure delete overwrites every row, which
     takes a while for long chats or "Delete all").
     */
    private func purge(_ ids: [String]) {
        guard !ids.isEmpty else { return }
        ids.forEach(agent.discard)
        conversations.forget(ids)
        let sessions = sessions, attachments = attachments
        let task = Task.detached(priority: .utility) {
            for id in ids {
                // Read before the log goes: the images the chat referenced.
                let images = sessions.attachmentIds(id)
                sessions.delete(id)
                images.forEach(attachments.delete)
            }
        }
        purging.append(task)
    }

    /// Deletions still running in the background.
    @ObservationIgnored private var purging: [Task<Void, Never>] = []

    /// Waits for the background deletions started so far (tests, panic wipe ordering).
    func waitForPurges() async {
        let running = purging
        purging.removeAll()
        for task in running { await task.value }
    }

    /**
     Chats among [candidates] matching [query] in their title or messages. Runs off the main
     actor on the chats' stored search text (a chat indexed before that text was kept is
     indexed from its log once).
     */
    func search(_ query: String, in candidates: [Conversation]) async -> [String: ChatSearchHit] {
        let terms = ChatSearch.shared.terms(query: query)
        guard !terms.isEmpty else { return [:] }
        let items = candidates.map { ($0.id, $0.title) }
        let sessions = sessions
        return await Task.detached(priority: .userInitiated) {
            var out: [String: ChatSearchHit] = [:]
            // One read of the stored search text (no log is decrypted for an indexed chat).
            let texts = sessions.searchTexts(items.map(\.0))
            for (id, title) in items {
                if Task.isCancelled { break }
                let text = texts[id] ?? ""
                // A quick Swift pre-check; the shared matcher decides and builds the excerpt.
                guard terms.allSatisfy({ title.range(of: $0, options: .caseInsensitive) != nil || text.range(of: $0, options: .caseInsensitive) != nil }) else { continue }
                guard let m = ChatSearch.shared.match(terms: terms, title: title, text: text) else { continue }
                out[id] = ChatSearchHit(
                    titleSpans: m.titleSpans.map { Int($0.start) ..< Int($0.end) },
                    snippet: m.snippet,
                    snippetSpans: m.snippetSpans.map { Int($0.start) ..< Int($0.end) },
                )
            }
            return out
        }.value
    }
}
