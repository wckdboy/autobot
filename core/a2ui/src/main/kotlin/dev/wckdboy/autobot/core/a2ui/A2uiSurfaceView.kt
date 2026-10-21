package dev.wckdboy.autobot.core.a2ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import dev.wckdboy.autobot.core.designsystem.component.LinkPrompt
import dev.wckdboy.autobot.shared.a2ui.A2uiActionResult
import dev.wckdboy.autobot.shared.a2ui.A2uiError
import dev.wckdboy.autobot.shared.a2ui.A2uiHost
import dev.wckdboy.autobot.shared.a2ui.A2uiListener
import dev.wckdboy.autobot.shared.a2ui.A2uiTranscript
import dev.wckdboy.autobot.shared.a2ui.RenderNode
import dev.wckdboy.autobot.shared.session.SessionItem

/**
 * Where a surface's media comes from. The core never fetches; an Image shows its host and a
 * "Load image" button, and only a tap calls [image] (the app routes it through its network
 * policy: kill switch, route, audit log). Video and audio open in the browser after the link
 * prompt; there is no in-app player yet.
 */
@Stable
interface A2uiMedia {
    /** The image at [url] (always http or https), or null when it cannot be loaded. */
    suspend fun image(url: String): ImageBitmap?

    /** Load images without a tap (false: the user decides per image). */
    val loadsWithoutAsking: Boolean get() = false

    /**
     * Why an image waits for a tap, shown under it (e.g. "Tap to load · goes through Tor", see
     * [dev.wckdboy.autobot.shared.a2ui.A2uiChat.imageHint]); null shows nothing.
     */
    val loadHint: String? get() = null

    companion object {
        /** No loader: images offer to open their link instead. */
        val None: A2uiMedia = object : A2uiMedia {
            override suspend fun image(url: String): ImageBitmap? = null
        }
    }
}

/**
 * Draws [surfaceId] of [host] with Material 3 components in the current theme and keeps it in
 * step with the host (agent updates, input write-back). Inputs write back through the host;
 * a Button (or other action) dispatches through it: [A2uiActionResult.OpenUrl] is handled here
 * with the app's link prompt (nothing opens without "Open"), everything else goes to
 * [onAction] ([A2uiActionResult.SendEvent] for the agent, [A2uiActionResult.Refused] when a
 * check fails, [A2uiActionResult.Done]). Draws nothing while the surface has no root.
 *
 * A read-only surface ([A2uiHost.isReadOnly], a sent form) draws disabled inputs and disabled
 * send buttons. [note] is read whenever the surface changes; a non-null text is drawn under it
 * (the chat's "Sent").
 */
@Composable
fun A2uiSurfaceView(
    host: A2uiHost,
    surfaceId: String,
    onAction: (A2uiActionResult) -> Unit,
    modifier: Modifier = Modifier,
    media: A2uiMedia = A2uiMedia.None,
    note: () -> String? = { null },
) {
    var tree by remember(host, surfaceId) { mutableStateOf(host.render(surfaceId)) }
    var readOnly by remember(host, surfaceId) { mutableStateOf(host.isReadOnly(surfaceId)) }
    var footer by remember(host, surfaceId) { mutableStateOf(note()) }
    val currentNote by rememberUpdatedState(note)
    DisposableEffect(host, surfaceId) {
        val target = surfaceId
        val listener = object : A2uiListener {
            override fun onSurfaceUpdated(surfaceId: String, root: RenderNode?) {
                if (surfaceId != target) return
                tree = root
                readOnly = host.isReadOnly(target)
                footer = currentNote()
            }

            override fun onSurfaceDeleted(surfaceId: String) {
                if (surfaceId == target) tree = null
            }

            override fun onErrors(errors: List<A2uiError>) = Unit
        }
        host.addListener(listener)
        tree = host.render(surfaceId)
        readOnly = host.isReadOnly(surfaceId)
        footer = currentNote()
        onDispose { host.removeListener(listener) }
    }
    var link by remember { mutableStateOf<String?>(null) }
    val currentOnAction by rememberUpdatedState(onAction)
    val ctx = remember(host, media) {
        A2uiContext(
            host = host,
            media = media,
            dispatch = { node ->
                when (val r = host.dispatch(node)) {
                    is A2uiActionResult.OpenUrl -> link = r.url
                    else -> currentOnAction(r)
                }
            },
            openLink = { url -> if (isWebUrl(url)) link = url },
        )
    }
    // Leaves inherit their colour (the catalog guide): a surface starts from the theme's on-surface.
    tree?.let {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface, LocalA2uiReadOnly provides readOnly) {
            Column(modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().testTag("a2ui:surface:$surfaceId")) { A2uiNode(it, ctx, Modifier.fillMaxWidth()) }
                footer?.let { text ->
                    Text(
                        text,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp).testTag("a2ui:note:$surfaceId"),
                    )
                }
            }
        }
    }
    link?.let { LinkPrompt(it) { link = null } }
}

/**
 * A chat's generative UI for a screen: the shared [A2uiTranscript] (one host per chat, the
 * `show_ui` streams replayed in order) fed from [items], the screen's current transcript. Each
 * read syncs first, so a row always finds its own surfaces; surfaces that later streams delete
 * or replace update through the host. Use it from the UI thread.
 */
@Stable
class A2uiChatState(
    val media: A2uiMedia = A2uiMedia.None,
    val transcript: A2uiTranscript = A2uiTranscript(),
    private val items: () -> List<SessionItem>,
) {
    val host: A2uiHost get() = transcript.host

    /** The surfaces drawn under the transcript item [itemKey]. */
    fun surfaces(itemKey: String): List<String> {
        transcript.sync(items())
        return transcript.surfaces(itemKey)
    }

    /**
     * Applies the current transcript now. Call it whenever the items change (rows that are not
     * recomposed still lock or unlock: the host tells their views).
     */
    fun sync(): Boolean = transcript.sync(items())

    /**
     * A tap's result, before the app sends it: a [A2uiActionResult.SendEvent] locks its surface
     * at once ([A2uiTranscript.markSubmitted]); the logged turn keeps it locked.
     */
    fun onAction(action: A2uiActionResult) {
        if (action is A2uiActionResult.SendEvent) transcript.markSubmitted(action)
    }

    /** The line under [surfaceId]: "Sent" once its action is in the chat, "Sending…" before. */
    fun note(surfaceId: String): String? = when {
        transcript.isPending(surfaceId) -> SENDING
        transcript.isSubmitted(surfaceId) -> SENT
        else -> null
    }

    companion object {
        const val SENT = "Sent"
        const val SENDING = "Sending…"
    }
}

/**
 * The surfaces a `show_ui` call created, drawn in place of its tool row (the chat's single call
 * site). Draws nothing when the call has none alive (deleted, or the history was replaced). A
 * sent surface is drawn read-only with "Sent" under it ("Sending…" until its turn is logged).
 */
@Composable
fun A2uiToolSurfaces(state: A2uiChatState, itemKey: String, onAction: (A2uiActionResult) -> Unit, modifier: Modifier = Modifier) {
    val ids = state.surfaces(itemKey)
    if (ids.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ids.forEach { id -> A2uiSurfaceView(state.host, id, onAction, media = state.media, note = { state.note(id) }) }
    }
}

internal fun isWebUrl(url: String): Boolean = url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)
