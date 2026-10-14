package dev.wckdboy.autobot.agent.runtime.workspace

import dev.wckdboy.autobot.shared.SystemFileSystem
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath

/**
 * The agent's file sandbox. The model only ever sees virtual paths under [VIRTUAL_ROOT]; real
 * device paths are never disclosed. Every path is normalized and must stay inside [root], and
 * the nearest existing ancestor is canonicalized too, so `..`, absolute paths elsewhere and
 * symlink tricks are rejected.
 */
class Workspace(rootDir: Path, val fs: FileSystem = SystemFileSystem) {
    val root: Path = rootDir.also { fs.createDirectories(it) }.let(fs::canonicalize)

    class EscapeException(path: String) : IOException("Path is outside the workspace: $path")

    /** Resolves a model-supplied path (`notes.md`, `./a/b`, `/workspace/a`) to a real path. */
    fun resolve(path: String): Path {
        val trimmed = path.trim().ifEmpty { "." }
        val relative = when {
            trimmed == VIRTUAL_ROOT -> "."
            trimmed.startsWith("$VIRTUAL_ROOT/") -> trimmed.removePrefix("$VIRTUAL_ROOT/")
            trimmed.startsWith("/") -> throw EscapeException(path)
            else -> trimmed
        }
        val lexical = root.resolve(relative.toPath(), normalize = true)
        if (!inside(lexical)) throw EscapeException(path)
        // Follow symlinks on the part that exists: it must not lead out of the root either.
        var existing: Path? = lexical
        while (existing != null && !fs.exists(existing)) existing = existing.parent
        val real = existing?.let(fs::canonicalize) ?: throw EscapeException(path)
        if (!inside(real)) throw EscapeException(path)
        return if (existing == lexical) real else real.resolve(lexical.relativeTo(existing))
    }

    /** The virtual path shown to the model for [path]. */
    fun display(path: Path): String {
        if (!inside(path)) return path.name
        val rel = relative(path)
        return if (rel.isEmpty() || rel == ".") VIRTUAL_ROOT else "$VIRTUAL_ROOT/$rel"
    }

    fun relative(path: Path): String = path.relativeTo(root).segments.joinToString("/")

    private fun inside(path: Path): Boolean =
        path == root || path.toString().startsWith(root.toString().trimEnd('/') + "/")

    companion object {
        const val VIRTUAL_ROOT = "/workspace"
    }
}
