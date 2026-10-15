package dev.wckdboy.autobot.agent.runtime.workspace

import dev.wckdboy.autobot.agent.core.approval.PrivateSource
import dev.wckdboy.autobot.agent.core.tools.ToolDefinition
import dev.wckdboy.autobot.agent.core.tools.ToolKind
import dev.wckdboy.autobot.agent.core.tools.ToolOutput
import dev.wckdboy.autobot.agent.core.tools.ToolRunContext
import dev.wckdboy.autobot.agent.core.tools.bool
import dev.wckdboy.autobot.agent.core.tools.int
import dev.wckdboy.autobot.agent.core.tools.schema
import dev.wckdboy.autobot.agent.core.tools.string
import dev.wckdboy.autobot.agent.core.Lock
import dev.wckdboy.autobot.agent.runtime.InterruptibleChars
import dev.wckdboy.autobot.agent.runtime.RegexTimeoutException
import dev.wckdboy.autobot.shared.IoDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okio.Path

/**
 * DSH file tools (`read`, `write`, `edit`, `glob`, `grep`) over a [Workspace], plus `delete`.
 *
 * Implements DSH's fs-observation policy: an existing file must have been read in this session
 * before it can be overwritten or edited, and must not have changed on disk since.
 */
class FileTools(private val workspace: Workspace) {

    private val fs = workspace.fs
    private val lock = Lock()

    /** sessionId → (canonical path → lastModified at read time). */
    private val observed = HashMap<String, HashMap<String, Long?>>()

    private fun Path.exists() = fs.exists(this)
    private val Path.isDirectory get() = fs.metadataOrNull(this)?.isDirectory == true
    private val Path.isFile get() = fs.metadataOrNull(this)?.isRegularFile == true
    private fun Path.length() = fs.metadataOrNull(this)?.size ?: 0L
    private fun Path.lastModified() = fs.metadataOrNull(this)?.lastModifiedAtMillis
    private fun Path.readBytes() = fs.read(this) { readByteArray() }
    private fun Path.readText() = fs.read(this) { readUtf8() }
    private fun Path.writeText(text: String) = fs.write(this) { writeUtf8(text) }

    /** Files and directories under [base], newest first is up to the caller; `.git*` directories are skipped. */
    private fun walk(base: Path): Sequence<Path> = sequence {
        for (child in fs.listOrNull(base).orEmpty()) {
            yield(child)
            if (child.isDirectory && !child.name.startsWith(".git")) yieldAll(walk(child))
        }
    }

    private fun observe(session: String, file: Path) {
        val modified = file.lastModified()
        lock.withLock { observed.getOrPut(session) { HashMap() }[file.toString()] = modified }
    }

    private fun checkObserved(session: String, file: Path): String? {
        if (!file.exists()) return null
        val known = lock.withLock { observed[session]?.containsKey(file.toString()) == true }
        if (!known) return "read ${workspace.display(file)} before modifying it"
        val seen = lock.withLock { observed[session]?.get(file.toString()) }
        if (seen != file.lastModified()) return "${workspace.display(file)} changed since it was read; read it again"
        return null
    }

    val all: List<ToolDefinition> get() = listOf(Read(), Write(), Edit(), Glob(), Grep(), Delete())

    private suspend fun <T> io(block: () -> T): T = withContext(IoDispatcher) { block() }

    private fun pathArg(args: JsonObject, key: String = "file_path"): Path = workspace.resolve(args.string(key).orEmpty())

    inner class Read : ToolDefinition {
        override val name = "read"
        override val kind = ToolKind.READ
        override val description =
            "Read a UTF-8 text file from the workspace. Returns numbered lines (`  12→text`). Use offset/limit for large " +
                "files. Use this rather than asking the user to paste file contents."
        override val parameters = schema {
            string("file_path", "Path relative to /workspace (or absolute under /workspace)", required = true)
            integer("offset", "1-based line to start at", minimum = 1)
            integer("limit", "Maximum lines to return (default 2000)", minimum = 1, maximum = 10_000)
        }

        override fun isConcurrencySafe(args: JsonObject) = true
        override fun describeCall(args: JsonObject) = args.string("file_path").orEmpty()

        /** File contents are private; line numbers are not. */
        override val privateSource = PrivateSource(PRIVATE_LABEL) { text -> text.lineSequence().filter { '→' in it }.joinToString("\n") { it.substringAfter('→') } }

        override suspend fun execute(args: JsonObject, run: ToolRunContext): ToolOutput = io {
            val file = pathArg(args)
            when {
                !file.exists() -> ToolOutput.error("${workspace.display(file)} does not exist", "NOT_FOUND")
                file.isDirectory -> ToolOutput.error("${workspace.display(file)} is a directory; use glob to list it", "IS_DIRECTORY")
                file.length() > MAX_READ_BYTES -> ToolOutput.error("file is larger than ${MAX_READ_BYTES / 1024} KiB", "TOO_LARGE")
                else -> {
                    val text = decodeUtf8(file.readBytes()) ?: return@io ToolOutput.error("not a UTF-8 text file", "BINARY")
                    observe(run.sessionId, file)
                    val lines = text.lines()
                    val offset = (args.int("offset") ?: 1).coerceAtLeast(1)
                    val limit = args.int("limit") ?: DEFAULT_LIMIT
                    val slice = lines.drop(offset - 1).take(limit)
                    if (slice.isEmpty()) return@io ToolOutput("(empty — file has ${lines.size} lines)")
                    val body = slice.mapIndexed { i, line -> "${(offset + i).toString().padStart(6)}→$line" }.joinToString("\n")
                    val more = lines.size - (offset - 1 + slice.size)
                    ToolOutput(if (more > 0) "$body\n… $more more lines (use offset=${offset + slice.size})" else body)
                }
            }
        }
    }

    inner class Write : ToolDefinition {
        override val name = "write"
        override val kind = ToolKind.EDIT
        override val description =
            "Create or overwrite a text file in the workspace. Parent directories are created. Overwriting an existing " +
                "file requires reading it first; prefer `edit` for changes to existing files."
        override val parameters = schema {
            string("file_path", "Path relative to /workspace", required = true)
            string("content", "Full new file content", required = true)
        }

        override fun describeCall(args: JsonObject) = "${args.string("file_path")}  (${args.string("content")?.length ?: 0} chars)"

        override suspend fun execute(args: JsonObject, run: ToolRunContext): ToolOutput = io {
            val file = pathArg(args)
            if (file.isDirectory) return@io ToolOutput.error("${workspace.display(file)} is a directory", "IS_DIRECTORY")
            checkObserved(run.sessionId, file)?.let { return@io ToolOutput.error(it, "NOT_OBSERVED") }
            val existed = file.exists()
            file.parent?.let(fs::createDirectories)
            file.writeText(args.string("content").orEmpty())
            observe(run.sessionId, file)
            ToolOutput("${if (existed) "Overwrote" else "Created"} ${workspace.display(file)} (${file.length()} bytes).")
        }
    }

    inner class Edit : ToolDefinition {
        override val name = "edit"
        override val kind = ToolKind.EDIT
        override val description =
            "Replace an exact string in a file you have read. `old_string` must match exactly (whitespace included) and be " +
                "unique unless `replace_all` is true. Include enough surrounding context to make it unique."
        override val parameters = schema {
            string("file_path", "Path relative to /workspace", required = true)
            string("old_string", "Exact text to replace", required = true)
            string("new_string", "Replacement text (must differ)", required = true)
            boolean("replace_all", "Replace every occurrence")
        }

        override fun describeCall(args: JsonObject) = args.string("file_path").orEmpty()

        override suspend fun execute(args: JsonObject, run: ToolRunContext): ToolOutput = io {
            val file = pathArg(args)
            if (!file.isFile) return@io ToolOutput.error("${workspace.display(file)} does not exist", "NOT_FOUND")
            checkObserved(run.sessionId, file)?.let { return@io ToolOutput.error(it, "NOT_OBSERVED") }
            val old = args.string("old_string").orEmpty()
            val new = args.string("new_string").orEmpty()
            if (old.isEmpty()) return@io ToolOutput.error("old_string is empty", "INVALID_ARGS")
            if (old == new) return@io ToolOutput.error("new_string is identical to old_string", "INVALID_ARGS")
            val text = file.readText()
            val count = text.windowedCount(old)
            when {
                count == 0 -> ToolOutput.error("old_string not found in ${workspace.display(file)}", "NO_MATCH")
                count > 1 && args.bool("replace_all") != true ->
                    ToolOutput.error("old_string matches $count times; add context or set replace_all", "AMBIGUOUS")
                else -> {
                    file.writeText(if (args.bool("replace_all") == true) text.replace(old, new) else text.replaceFirst(old, new))
                    observe(run.sessionId, file)
                    ToolOutput("Edited ${workspace.display(file)} ($count replacement${if (count == 1) "" else "s"}).")
                }
            }
        }
    }

    inner class Glob : ToolDefinition {
        override val name = "glob"
        override val kind = ToolKind.SEARCH
        override val description =
            "Find files by glob pattern (e.g. `**/*.md`, `notes/*`). Returns up to 100 paths, newest first. Use `*` to " +
                "list a directory."
        override val parameters = schema {
            string("pattern", "Glob pattern relative to `path`", required = true)
            string("path", "Directory to search (default /workspace)")
        }

        override fun isConcurrencySafe(args: JsonObject) = true
        override fun describeCall(args: JsonObject) = listOfNotNull(args.string("pattern"), args.string("path")).joinToString("  in ")

        override suspend fun execute(args: JsonObject, run: ToolRunContext): ToolOutput = io {
            val base = pathArg(args, "path").takeIf { args.string("path") != null } ?: workspace.root
            if (!base.isDirectory) return@io ToolOutput.error("${workspace.display(base)} is not a directory", "NOT_FOUND")
            val matcher = Glob(args.string("pattern").orEmpty())
            val hits = walk(base)
                .filter { matcher.matches(it.relativeTo(base).segments.joinToString("/")) }
                .sortedByDescending { it.lastModified() ?: 0L }
                .toList()
            if (hits.isEmpty()) return@io ToolOutput("No files match.")
            val shown = hits.take(MAX_GLOB).joinToString("\n") { workspace.display(it) + if (it.isDirectory) "/" else "" }
            ToolOutput(if (hits.size > MAX_GLOB) "$shown\n… ${hits.size - MAX_GLOB} more" else shown)
        }
    }

    inner class Grep : ToolDefinition {
        override val name = "grep"
        override val kind = ToolKind.SEARCH
        override val description =
            "Search file contents with a regular expression. Returns `path:line: text`, up to 250 matches. Narrow with " +
                "`include` (a glob such as `*.md`)."
        override val parameters = schema {
            string("pattern", "Regular expression (Java syntax)", required = true)
            string("path", "Directory or file to search (default /workspace)")
            string("include", "Only files whose name matches this glob")
        }

        override fun isConcurrencySafe(args: JsonObject) = true
        override fun describeCall(args: JsonObject) = listOfNotNull(args.string("pattern"), args.string("include")).joinToString("  ")

        /** Matched lines are private; their `path:line:` prefixes are not. */
        override val privateSource = PrivateSource(PRIVATE_LABEL) { text -> GREP_PREFIX.replace(text, "") }

        override suspend fun execute(args: JsonObject, run: ToolRunContext): ToolOutput = withContext(IoDispatcher) {
            val regex = runCatching { Regex(args.string("pattern").orEmpty()) }.getOrElse {
                return@withContext ToolOutput.error("invalid regex: ${it.message}", "INVALID_ARGS")
            }
            val base = pathArg(args, "path").takeIf { args.string("path") != null } ?: workspace.root
            val include = args.string("include")?.let(::Glob)
            val out = StringBuilder()
            var matches = 0
            val files = if (base.isFile) sequenceOf(base) else walk(base).filter { it.isFile }
            // The pattern is the model's: matching stops with the turn, and after a time budget.
            val job = coroutineContext[Job]
            val deadline = InterruptibleChars.deadline(GREP_BUDGET_MS)
            try {
                for (file in files) {
                    ensureActive()
                    if (include != null && !include.matches(file.name)) continue
                    if (file.length() > MAX_READ_BYTES) continue
                    val text = decodeUtf8(file.readBytes()) ?: continue
                    text.lineSequence().forEachIndexed { i, line ->
                        if (matches < MAX_GREP && regex.containsMatchIn(InterruptibleChars(line.take(MAX_GREP_LINE), job, deadline))) {
                            out.append(workspace.display(file)).append(':').append(i + 1).append(": ").append(line.take(300)).append('\n')
                            matches++
                        }
                    }
                    if (matches >= MAX_GREP) break
                }
            } catch (_: RegexTimeoutException) {
                return@withContext ToolOutput.error(
                    "the pattern took longer than ${GREP_BUDGET_MS / 1000} s (nested repetition such as (a+)+ backtracks); simplify it",
                    "TIMEOUT",
                )
            }
            ToolOutput(if (matches == 0) "No matches." else out.toString().trimEnd() + if (matches >= MAX_GREP) "\n… (capped at $MAX_GREP)" else "")
        }
    }

    inner class Delete : ToolDefinition {
        override val name = "delete"
        override val kind = ToolKind.DELETE
        override val description = "Delete a file or an empty directory in the workspace."
        override val parameters = schema { string("file_path", "Path relative to /workspace", required = true) }

        override fun describeCall(args: JsonObject) = args.string("file_path").orEmpty()

        override suspend fun execute(args: JsonObject, run: ToolRunContext): ToolOutput = io {
            val file = pathArg(args)
            when {
                file == workspace.root -> ToolOutput.error("refusing to delete the workspace root", "INVALID_ARGS")
                !file.exists() -> ToolOutput.error("${workspace.display(file)} does not exist", "NOT_FOUND")
                file.isDirectory && !fs.listOrNull(file).isNullOrEmpty() -> ToolOutput.error("directory is not empty", "NOT_EMPTY")
                runCatching { fs.delete(file, mustExist = true) }.isSuccess -> ToolOutput("Deleted ${workspace.display(file)}.")
                else -> ToolOutput.error("could not delete ${workspace.display(file)}", "IO_ERROR")
            }
        }
    }

    companion object {
        const val MAX_READ_BYTES = 1_048_576L
        const val DEFAULT_LIMIT = 2000
        const val MAX_GLOB = 100
        const val MAX_GREP = 250

        /** Characters of one line a grep pattern is matched against. */
        const val MAX_GREP_LINE = 10_000

        /** Time one grep may spend matching. */
        const val GREP_BUDGET_MS = 10_000L

        /** Taint label of workspace file contents (`read`, `grep`). */
        const val PRIVATE_LABEL = "workspace file"
        private val GREP_PREFIX = Regex("(?m)^[^\n]*?:\\d+: ")

        /** Strict UTF-8 decode; `null` for binary content. */
        fun decodeUtf8(bytes: ByteArray): String? = try {
            bytes.decodeToString(throwOnInvalidSequence = true).takeUnless { '\u0000' in it }
        } catch (_: CharacterCodingException) {
            null
        }

        private fun String.windowedCount(needle: String): Int {
            var count = 0
            var from = 0
            while (true) {
                val at = indexOf(needle, from)
                if (at < 0) return count
                count++
                from = at + needle.length
            }
        }
    }
}
