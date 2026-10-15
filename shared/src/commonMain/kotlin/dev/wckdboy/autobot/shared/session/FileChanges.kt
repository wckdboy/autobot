package dev.wckdboy.autobot.shared.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
enum class ChangeOp {
    @SerialName("created") CREATED,
    @SerialName("modified") MODIFIED,
    @SerialName("deleted") DELETED,
}

/**
 * What one file tool call (`write`, `edit`, `delete`) did to one workspace file, carried in the
 * tool result's `meta.change` so the transcript can show it without touching the files.
 *
 * @property path workspace-relative (`notes/a.md`).
 * @property diff unified-diff lines (`@@ …`, ` ctx`, `-old`, `+new`) for text files; empty for
 *   binary content or when nothing changed.
 * @property undoable false when the undo journal kept no copy (too large, or journal off).
 * @property note why the step cannot be undone, or why there is no diff.
 */
@Serializable
data class FileChange(
    val path: String,
    val op: ChangeOp,
    val added: Int = 0,
    val removed: Int = 0,
    val diff: List<String> = emptyList(),
    val truncated: Boolean = false,
    val undoable: Boolean = true,
    val note: String? = null,
) {
    companion object {
        const val META_KEY = "change"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

        fun toJson(change: FileChange): JsonElement = json.encodeToJsonElement(serializer(), change)

        /** The change in a tool result's meta, or null (older results, other tools). */
        fun from(meta: JsonObject?): FileChange? = meta?.get(META_KEY)?.let { runCatching { json.decodeFromJsonElement(serializer(), it) }.getOrNull() }
    }
}

/** Undo state of a turn, on the turn's last answer: "Undo turn (2 files)" or "Redo turn". */
@Serializable
data class TurnChanges(
    val turn: Int,
    /** Distinct files the turn's own steps changed. */
    val files: Int,
    /** Every undoable step of the turn is undone. */
    val undone: Boolean = false,
    /** The revert that undid the whole turn (for redo), when one did. */
    val revertId: String? = null,
    /** Some step can still be undone. */
    val undoable: Boolean = true,
)

/** One file in the session's "changes" sheet. */
@Serializable
data class ChangedFile(
    val path: String,
    val op: ChangeOp,
    val added: Int,
    val removed: Int,
    val undone: Boolean,
    /** The step that last changed it in this turn. */
    val callId: String,
)

/** The files one turn changed. */
@Serializable
data class ChangeGroup(val turn: Int, val files: List<ChangedFile>)
