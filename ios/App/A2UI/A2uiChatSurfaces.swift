import AutobotShared
import Foundation
import SwiftUI

/**
 A chat's generative UI (A2UI, the agent's `show_ui` tool): one shared [A2uiTranscript] — one
 [A2uiHost] — per open chat, replaying the transcript's `show_ui` streams in order, so surfaces,
 their data and what the user typed survive new turns, and a reopened chat draws them again from
 its log. The Swift side of Android's `A2uiChatState`; the replay rules are the shared Kotlin ones.

 Main actor only, like the host. Syncing is idempotent: only streams not applied yet are applied,
 and a transcript that no longer extends what was applied (undo, reload) starts over.
 */
@MainActor
final class A2uiChatSurfaces {
    let transcript: A2uiTranscript
    var host: A2uiHost { transcript.host }

    /// One model per surface, all on [host] (a surface keeps its model across rows' re-renders).
    private var models: [String: A2uiSurfaceModel] = [:]

    init() {
        transcript = A2uiTranscript(
            catalogs: [A2uiCatalog.companion.basic],
            limits: A2uiChat.shared.limits,
            nowMillis: { KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) },
        )
    }

    /// Applies the `show_ui` streams of [items] not applied yet; true when anything changed.
    @discardableResult
    func sync(_ items: [SessionItem]) -> Bool {
        var keys: [String] = []
        var streams: [String] = []
        for case .tool(let tool) in items {
            guard let stream = tool.a2ui else { continue }
            keys.append(tool.key)
            streams.append(stream)
        }
        return transcript.syncStreams(keys: keys, streams: streams)
    }

    /// The live surfaces the transcript item [itemKey] created, in creation order.
    func surfaces(_ itemKey: String) -> [A2uiSurfaceModel] {
        transcript.surfaces(itemKey: itemKey).map { id in
            if let model = models[id] { return model }
            let model = A2uiSurfaceModel(host: host, surfaceId: id)
            models[id] = model
            return model
        }
    }

    /// Whether [surfaceId] takes no more input.
    // TODO(a2ui-lock): lock-after-submit lands in the shared A2uiTranscript (a2ui-android); once it
    // reports a surface as submitted, return that here — [A2uiToolSurfaces] already disables the
    // surface when this is true.
    func isLocked(_ surfaceId: String) -> Bool { false }
}

/**
 The surfaces a `show_ui` call created, drawn in place of its step row: the chat screen's single
 call site. Draws nothing when the call has none alive (deleted, or the history was replaced).

 Accents in the brand purple; images through the app's policy-bound client and only after a tap
 (nothing loads without asking, as on Android); links ask first ([A2uiSurfaceView]); a `.send`
 action becomes the chat's next user turn, shown as its label.
 */
struct A2uiToolSurfaces: View {
    let chat: A2uiChatSurfaces
    let conversationId: String
    let itemKey: String
    /// A refused action's reason (a failing check), for the chat's notice banner.
    var onRefused: (String) -> Void = { _ in }

    @Environment(AppContainer.self) private var app

    var body: some View {
        // The chat's transcript as the runtime has it now: every earlier stream, in order.
        let _ = chat.sync(app.agent.existingSession(conversationId)?.transcript.items ?? [])
        VStack(alignment: .leading, spacing: 8) {
            ForEach(chat.surfaces(itemKey), id: \.surfaceId) { model in
                A2uiSurfaceView(model: model, tint: .a2uiBrand, onAction: act)
                    .disabled(chat.isLocked(model.surfaceId))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .environment(\.a2uiImageLoader, .policyBound(app.clients))
        .environment(\.a2uiImagesOnTap, true)
    }

    private func act(_ action: A2uiAction) {
        switch action {
        case .send:
            let session = app.agent.existingSession(conversationId)
            if !(session?.running ?? false) && !(session?.isLive ?? false) { ChatTiming.sent() }
            app.agent.sendA2uiAction(conversationId, action)
        case .refused(let reason):
            onRefused(reason)
        case .openUrl, .done:
            break
        }
    }
}
