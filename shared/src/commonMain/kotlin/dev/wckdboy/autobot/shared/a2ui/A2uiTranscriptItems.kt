package dev.wckdboy.autobot.shared.a2ui

import dev.wckdboy.autobot.shared.session.SessionItem
import dev.wckdboy.autobot.shared.session.ToolState

/**
 * The transcript items [A2uiTranscript.sync] reads, for hosts that keep their own copy of the
 * transcript (iOS decodes the projection's JSON into Swift values): a successful `show_ui` step
 * with its [stream], and a user turn sent from a surface ([submission]). Built in transcript
 * order, they sync exactly as the projection's own items would.
 */
object A2uiTranscriptItems {
    /** The `show_ui` step [key] that showed [stream] ([SessionItem.Tool.a2ui]). */
    fun stream(key: String, stream: String): SessionItem =
        SessionItem.Tool(key, callId = "", name = A2uiChat.TOOL, summary = "", state = ToolState.OK, output = null, code = null, galleryIds = emptyList(), a2ui = stream)

    /** The user turn [key] that sent [submission] ([SessionItem.User.a2ui]). */
    fun submitted(key: String, submission: A2uiSubmission): SessionItem =
        SessionItem.User(key, submission.label, a2ui = submission)

    /**
     * The [A2uiActionResult.SendEvent] a Swift host received as plain fields (its `A2uiAction`),
     * for [A2uiTranscript.markSubmitted].
     */
    fun sendEvent(surfaceId: String, name: String, messageJson: String, dataModelJson: String?, userMessage: String?, snapshotJson: String): A2uiActionResult.SendEvent =
        A2uiActionResult.SendEvent(surfaceId, name, messageJson, dataModelJson, userMessage, snapshotJson)
}
