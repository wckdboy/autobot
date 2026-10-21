package dev.wckdboy.autobot.shared.a2ui

import dev.wckdboy.autobot.shared.session.SessionItem
import dev.wckdboy.autobot.shared.session.ToolState

/**
 * [A2uiTranscript.sync] for hosts that keep their own copy of the transcript (iOS decodes the
 * projection's JSON into Swift values): the `show_ui` items' [keys] and [streams]
 * ([SessionItem.Tool.a2ui]), in transcript order. Same result as syncing the items themselves.
 */
fun A2uiTranscript.syncStreams(keys: List<String>, streams: List<String>): Boolean {
    require(keys.size == streams.size) { "keys and streams differ in size" }
    return sync(
        keys.zip(streams) { key, stream ->
            SessionItem.Tool(key, callId = "", name = A2uiChat.TOOL, summary = "", state = ToolState.OK, output = null, code = null, galleryIds = emptyList(), a2ui = stream)
        },
    )
}
