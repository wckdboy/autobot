import AutobotShared
import Foundation
import SwiftUI

/**
 A chat's generative UI (A2UI, the agent's `show_ui` tool): one shared [A2uiTranscript] — one
 [A2uiHost] — per open chat, replaying the transcript's `show_ui` streams and sent actions in
 order, so surfaces, their data and what the user typed survive new turns, and a reopened chat
 draws them again from its log. The Swift side of Android's `A2uiChatState`; the replay and lock
 rules are the shared Kotlin ones.

 Sent forms are locked: a tap marks the surface submitted at once ("Sending…"), the logged turn
 keeps it locked ("Sent", also after a restart, with the input as sent), a later stream that
 changes the surface unlocks it. A send that could not be handed over, or a stop that dropped the
 queued turn, unlocks it again.

 Main actor only, like the host. [sync] on every new transcript (not only when a row draws):
 rows that are not re-rendered still lock and unlock, the host tells their views.
 */
@MainActor
@Observable
final class A2uiChatSurfaces {
    static let sent = "Sent"
    static let sending = "Sending…"

    @ObservationIgnored let transcript: A2uiTranscript
    var host: A2uiHost { transcript.host }

    /// Bumped when a sync applied something or a surface was locked or unlocked: rows re-read.
    private(set) var revision = 0

    /// One model per surface, all on [host] (a surface keeps its model across rows' re-renders).
    @ObservationIgnored private var models: [String: A2uiSurfaceModel] = [:]

    init() {
        transcript = A2uiTranscript(
            catalogs: [A2uiCatalog.companion.basic],
            limits: A2uiChat.shared.limits,
            nowMillis: { KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) },
        )
    }

    /// Applies what [transcript] adds; true when anything changed.
    @discardableResult
    func sync(_ transcript: SessionTranscript) -> Bool {
        sync(transcript.items, sent: transcript.projection.a2uiSent)
    }

    /// Applies the `show_ui` streams of [items] and the actions sent from them ([sent], by user
    /// item key) not applied yet; true when anything changed.
    @discardableResult
    func sync(_ items: [SessionItem], sent: [String: A2uiSent] = [:]) -> Bool {
        let make = A2uiTranscriptItems.shared
        var ops: [any AutobotShared.SessionItem] = []
        for item in items {
            switch item {
            case .tool(let tool):
                if let stream = tool.a2ui { ops.append(make.stream(key: tool.key, stream: stream)) }
            case .user(let key, _, _):
                if let s = sent[key] {
                    ops.append(make.submitted(key: key, submission: A2uiSubmission(surfaceId: s.surfaceId, action: s.action, label: s.label, dataModel: s.dataModel)))
                }
            default:
                break
            }
        }
        guard transcript.sync(items: ops) else { return false }
        revision += 1
        return true
    }

    /// The live surfaces the transcript item [itemKey] created, in creation order.
    func surfaces(_ itemKey: String) -> [A2uiSurfaceModel] {
        _ = revision
        return transcript.surfaces(itemKey: itemKey).map { id in
            if let model = models[id] { return model }
            let model = A2uiSurfaceModel(host: host, surfaceId: id)
            models[id] = model
            return model
        }
    }

    /// Whether [surfaceId] was sent (logged or on its way): drawn read-only.
    func isLocked(_ surfaceId: String) -> Bool {
        _ = revision
        return transcript.isSubmitted(surfaceId: surfaceId)
    }

    /// The line under [surfaceId]: "Sending…" until its turn is logged, then "Sent".
    func note(_ surfaceId: String) -> String? {
        _ = revision
        if transcript.isPending(surfaceId: surfaceId) { return Self.sending }
        return transcript.isSubmitted(surfaceId: surfaceId) ? Self.sent : nil
    }

    /**
     A tap's `.send`: locks the surface now, then hands the action to [runtime] as the chat's
     next user turn; unlocks it when that failed.
     */
    func send(_ action: A2uiAction, conversationId: String, runtime: any AgentRuntime) async {
        guard case let .send(surfaceId, name, messageJson, dataModelJson, userMessage, snapshotJson) = action else { return }
        let event = A2uiTranscriptItems.shared.sendEvent(
            surfaceId: surfaceId, name: name, messageJson: messageJson, dataModelJson: dataModelJson, userMessage: userMessage, snapshotJson: snapshotJson,
        )
        transcript.markSubmitted(event: event)
        revision += 1
        if await !runtime.sendA2uiAction(conversationId, action) {
            transcript.unmarkSubmitted(surfaceId: surfaceId)
            revision += 1
        }
    }

    /// The user stopped the agent: queued turns are dropped, so forms sent behind the running turn are editable again.
    func stopped() {
        transcript.clearPending()
        revision += 1
    }
}

/**
 The surfaces a `show_ui` call created, drawn in place of its step row: the chat screen's call
 site. Draws nothing when the call has none alive (deleted, or the history was replaced).

 Accents in the app's tint (the brand purple); images through the app's policy-bound client and only after a tap
 (nothing loads without asking, as on Android); links ask first ([A2uiSurfaceView]); a `.send`
 action becomes the chat's next user turn, shown as its label; a sent surface is read-only with
 "Sending…" / "Sent" under it.
 */
struct A2uiToolSurfaces: View {
    let chat: A2uiChatSurfaces
    let conversationId: String
    let itemKey: String
    /// A refused action's reason (a failing check), for the chat's notice banner.
    var onRefused: (String) -> Void = { _ in }

    @Environment(AppContainer.self) private var app

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(chat.surfaces(itemKey), id: \.surfaceId) { model in
                VStack(alignment: .leading, spacing: 4) {
                    A2uiSurfaceView(model: model, onAction: act)
                    if let note = chat.note(model.surfaceId) {
                        Text(note)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .padding(.leading, 4)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .environment(\.a2uiImageLoader, .policyBound(app.clients))
        .environment(\.a2uiImagesOnTap, true)
        .environment(\.a2uiImageHint, imageHint)
    }

    /// The route an image load would take now (Settings' network mode; the policy's SOCKS fallback).
    private var imageHint: String {
        _ = app.settings.settings.networkMode
        let mode = RouteResolver.effectiveMode(app.policy.mode, .inherit, socksFallback: app.policy.socksFallback)
        return A2uiChat.shared.imageHint(mode: mode.shared)
    }

    private func act(_ action: A2uiAction) {
        switch action {
        case .send:
            let session = app.agent.existingSession(conversationId)
            if !(session?.running ?? false) && !(session?.isLive ?? false) { ChatTiming.sent() }
            let (chat, id, runtime) = (chat, conversationId, app.agent)
            Task { await chat.send(action, conversationId: id, runtime: runtime) }
        case .refused(let reason):
            onRefused(reason)
        case .openUrl, .done:
            break
        }
    }
}
