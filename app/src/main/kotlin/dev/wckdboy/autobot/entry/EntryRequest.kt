package dev.wckdboy.autobot.entry

import android.content.Intent

/**
 * What a system entry point (share sheet, text selection menu, Quick Settings tile, launcher
 * shortcut, home screen widget) asked for. Requests wait until the app is unlocked and set up;
 * nothing they carry is ever sent on its own: shared or selected text only fills the composer.
 */
sealed interface EntryRequest {
    /** A new chat. [draft] goes into the composer, [images] (content URIs) are attached. */
    data class NewChat(val draft: String? = null, val images: List<String> = emptyList()) : EntryRequest

    /** A new chat in voice mode (listening starts on the voice button, never by itself). */
    data object Voice : EntryRequest

    /** The image studio, with [prompt] filled in (not generated). */
    data class Imagine(val prompt: String? = null) : EntryRequest

    /**
     * A ComfyUI workflow for the Studio's Workflows room: a shared `.json` file ([uri]) or its text
     * ([json]). It is only shown for review; nothing is saved or run on its own.
     */
    data class ImportWorkflow(val uri: String? = null, val json: String? = null) : EntryRequest
}

/** Explicit actions used by the tile, the launcher shortcuts and the widget. */
object EntryActions {
    const val NEW_CHAT = "ai.wckd.autobot.action.NEW_CHAT"
    const val VOICE = "ai.wckd.autobot.action.VOICE"
    const val IMAGINE = "ai.wckd.autobot.action.IMAGINE"

    /** Optional prompt for [IMAGINE]. */
    const val EXTRA_PROMPT = "ai.wckd.autobot.extra.PROMPT"
}

/**
 * Turns an incoming intent's fields into an [EntryRequest] (pure, so it is unit-tested on the
 * JVM; `Intent.toEntryRequest()` reads them off the intent).
 */
object EntryParser {
    /** Longest text taken from another app. */
    const val MAX_TEXT = 20_000

    /** Images per message (the composer's limit). */
    const val MAX_IMAGES = 4

    fun parse(
        action: String?,
        type: String? = null,
        text: CharSequence? = null,
        subject: CharSequence? = null,
        streams: List<String> = emptyList(),
        processText: CharSequence? = null,
        prompt: String? = null,
    ): EntryRequest? = when (action) {
        EntryActions.NEW_CHAT -> EntryRequest.NewChat()
        EntryActions.VOICE -> EntryRequest.Voice
        EntryActions.IMAGINE -> EntryRequest.Imagine(clean(prompt))
        Intent.ACTION_PROCESS_TEXT -> clean(processText)?.let { EntryRequest.NewChat(draft = it) }
        Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> shared(type, text, subject, streams)
        else -> null
    }

    private fun shared(type: String?, text: CharSequence?, subject: CharSequence?, streams: List<String>): EntryRequest? {
        if (type == "application/json") {
            return streams.firstOrNull(::isContentUri)?.let { EntryRequest.ImportWorkflow(uri = it) }
                ?: text?.toString()?.trim()?.takeIf { isWorkflowJson(it) }?.let { EntryRequest.ImportWorkflow(json = it) }
        }
        text?.toString()?.trim()?.takeIf { isWorkflowJson(it) }?.let { return EntryRequest.ImportWorkflow(json = it) }
        val images = if (type?.startsWith("image/") == true) streams.filter(::isContentUri).distinct().take(MAX_IMAGES) else emptyList()
        val body = clean(text)
        // Browsers share the page title as the subject and the URL as the text.
        val title = clean(subject)?.takeIf { body == null || !body.contains(it) }
        val draft = clean(listOfNotNull(title, body).joinToString("\n"))
        if (draft == null && images.isEmpty()) return null
        return EntryRequest.NewChat(draft, images)
    }

    /**
     * Only `content://` URIs: a `file://` URI sent by another app could point into Autobot's own
     * private storage and have it attach (and maybe upload) its own files.
     */
    fun isContentUri(uri: String): Boolean = uri.startsWith("content://", ignoreCase = true)

    /** Longest workflow text taken from another app (API graphs are large, but not this large). */
    const val MAX_WORKFLOW = 8 * 1024 * 1024

    /** Text that is a ComfyUI graph (API or editor format) rather than something to chat about. */
    fun isWorkflowJson(text: String): Boolean =
        text.length <= MAX_WORKFLOW && text.startsWith("{") && text.endsWith("}") && ("\"class_type\"" in text || ("\"nodes\"" in text && "\"links\"" in text))

    private fun clean(text: CharSequence?): String? = text?.toString()?.trim()?.take(MAX_TEXT)?.trim()?.ifEmpty { null }
}
