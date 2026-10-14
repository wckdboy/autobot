import SwiftUI

/// One row of the session list.
struct ConversationItem: Identifiable, Equatable {
    let id: String
    let title: String
    let subtitle: String
    let updatedAt: Int64
    let incognito: Bool
    var running = false
}

/// All sessions: live indicators, incognito toggle, new session (port of `ChatListScreen.kt`).
struct ChatListScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c
    /// `nil` until the user toggles; falls back to the persisted default.
    @State private var incognitoOverride: Bool?
    @State private var pendingDelete: String?

    private var incognito: Bool { incognitoOverride ?? app.settings.settings.incognitoByDefault }

    private var items: [ConversationItem] {
        let names = Dictionary(app.providers.providers.map { ($0.id, $0.displayName) }, uniquingKeysWith: { a, _ in a })
        let running = app.agent.running
        return app.conversations.conversations.map { conv in
            ConversationItem(
                id: conv.id,
                title: conv.title,
                subtitle: [conv.providerId.flatMap { names[$0] }, conv.model].compactMap { $0 }.joined(separator: " · "),
                updatedAt: conv.updatedAt,
                incognito: app.conversations.isIncognito(conv.id),
                running: running.contains(conv.id),
            )
        }
    }

    var body: some View {
        let list = items
        let loaded = app.dataLoaded
        VStack(spacing: 0) {
            header(list)
            if loaded && app.providers.providers.isEmpty {
                Panel(title: "No model provider", accent: AutobotColors.amber) {
                    Text("Add DeepSeek, OpenRouter, a local Ollama server or any OpenAI-compatible endpoint.")
                        .textStyle(.bodySmall)
                        .foregroundStyle(c.onSurfaceVariant)
                    TextAction("Add provider", color: c.primary) { nav.push(.providers) }
                }
                .padding(12)
            }
            if loaded && list.isEmpty {
                VStack(spacing: 6) {
                    Spacer()
                    AutobotIconView(icon: .terminal, size: 28, color: c.outline)
                    MicroLabel("no sessions")
                    MicroLabel("everything stays encrypted on this device", color: c.outline)
                    Spacer()
                }
                .frame(maxWidth: .infinity)
                .padding(32)
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(list) { item in
                            ConversationRow(item: item, onClick: { nav.push(.chat(conversationId: item.id)) }, onDelete: { pendingDelete = item.id })
                            Hairline().padding(.leading, 16)
                        }
                    }
                    .padding(.bottom, 24)
                }
            }
        }
        .background(c.background)
        .alert("Delete session?", isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } })) {
            Button("Delete", role: .destructive) {
                if let id = pendingDelete {
                    // Stop the session's agent before its log goes away with the conversation.
                    app.agent.discard(id)
                    app.conversations.delete(id)
                }
                pendingDelete = nil
            }
            Button("Cancel", role: .cancel) { pendingDelete = nil }
        } message: {
            Text("Its encrypted history, tool calls and approvals are removed. Workspace files stay.")
        }
    }

    private func header(_ list: [ConversationItem]) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                if !nav.path.isEmpty {
                    IconAction(symbol: "arrow.left", label: "Back") { nav.back() }
                }
                VStack(alignment: .leading, spacing: 0) {
                    Text("SESSIONS").textStyle(.titleLarge).foregroundStyle(c.onBackground)
                    MicroLabel("\(list.count) sessions · \(list.filter(\.running).count) live")
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
            .padding(.bottom, 10)
        }
        .background(c.background)
        .hairlineEdge()
    }

    private func newChat() {
        let provider = app.providers.providers.first
        let conversation = app.conversations.create(incognito: incognito, providerId: provider?.id, model: provider?.defaultModel)
        nav.push(.chat(conversationId: conversation.id))
    }
}

private struct ConversationRow: View {
    let item: ConversationItem
    let onClick: () -> Void
    let onDelete: () -> Void
    @Environment(\.scheme) private var c

    var body: some View {
        HStack(spacing: 12) {
            Button(action: onClick) {
                HStack(spacing: 12) {
                    ZStack {
                        if item.running {
                            StatusDot(color: AutobotColors.acid, live: true)
                        } else if item.incognito {
                            StatusDot(color: AutobotColors.uv)
                        } else {
                            Rectangle().fill(c.outline).frame(width: 4, height: 4)
                        }
                    }
                    .frame(width: 8)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(item.title).textStyle(.bodyLarge).foregroundStyle(c.onSurface).lineLimit(1)
                        Text([item.subtitle, relativeTime(item.updatedAt)].filter { !$0.isEmpty }.joined(separator: " · "))
                            .textStyle(.codeBlock)
                            .foregroundStyle(c.onSurfaceVariant)
                            .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(PressStyle())
            IconAction(symbol: "trash", label: "Delete session", tint: c.outline, size: 16, action: onDelete)
        }
        .padding(.leading, 16)
        .padding(.trailing, 4)
        .padding(.vertical, 10)
    }
}
