package dev.wckdboy.autobot.agent.runtime.workspace

import java.io.File
import java.io.IOException

/**
 * The agent's file sandbox. The model only ever sees virtual paths under [VIRTUAL_ROOT]; real
 * device paths are never disclosed. Every path is canonicalized and must stay inside [root],
 * so `..`, absolute paths elsewhere and symlink tricks are rejected.
 */
class Workspace(rootDir: File) {
    val root: File = rootDir.apply { mkdirs() }.canonicalFile

    class EscapeException(path: String) : IOException("Path is outside the workspace: $path")

    /** Resolves a model-supplied path (`notes.md`, `./a/b`, `/workspace/a`) to a real file. */
    fun resolve(path: String): File {
        val trimmed = path.trim().ifEmpty { "." }
        val relative = when {
            trimmed == VIRTUAL_ROOT -> "."
            trimmed.startsWith("$VIRTUAL_ROOT/") -> trimmed.removePrefix("$VIRTUAL_ROOT/")
            trimmed.startsWith("/") -> throw EscapeException(path)
            else -> trimmed
        }
        val file = File(root, relative).canonicalFile
        if (file != root && !file.path.startsWith(root.path + File.separator)) throw EscapeException(path)
        return file
    }

    /** The virtual path shown to the model for [file]. */
    fun display(file: File): String {
        val rel = file.canonicalFile.relativeToOrNull(root)?.invariantSeparatorsPath ?: return file.name
        return if (rel.isEmpty()) VIRTUAL_ROOT else "$VIRTUAL_ROOT/$rel"
    }

    fun relative(file: File): String = file.canonicalFile.relativeTo(root).invariantSeparatorsPath

    companion object {
        const val VIRTUAL_ROOT = "/workspace"
    }
}
