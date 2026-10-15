package dev.wckdboy.autobot.agent.runtime.workspace

import dev.wckdboy.autobot.agent.core.Lock
import dev.wckdboy.autobot.agent.core.loop.Agent
import dev.wckdboy.autobot.agent.core.loop.AgentStatus
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.shared.IoDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.IOException
import okio.Path

/**
 * Copy-on-write undo journal for the agent's workspace ("undo for agents").
 *
 * Before `write`, `edit` or `delete` changes a file, [record] keeps the file's previous content
 * (or notes that it did not exist) under the session, the turn and the tool call. Reverting a
 * step or a whole turn puts the files back in reverse order; the revert first keeps the state it
 * replaces, so it can be redone.
 *
 * Layout: `<rootDir>/<session>/index.json` plus one blob per kept copy in `blobs/`. The journal
 * lives next to the workspace (never inside it, where the agent could read or edit it), so it is
 * protected the way the workspace is: app-private storage on Android, Application Support with
 * `NSFileProtectionComplete` on iOS. Copies are capped per session ([maxSessionBytes], oldest
 * turns dropped first, see [Recorded.pruned]); a file over [maxFileBytes] is not copied and its
 * step cannot be undone.
 *
 * Undoing a step also undoes later steps that touched the same files (the file goes back to how
 * it was before the step), so the workspace never ends up with half of a later edit.
 */
class WorkspaceJournal(
    private val workspace: Workspace,
    rootDir: Path,
    private val maxSessionBytes: Long = MAX_SESSION_BYTES,
    private val maxFileBytes: Long = MAX_FILE_BYTES,
) {
    private val fs = workspace.fs
    private val root: Path = rootDir.also { fs.createDirectories(it) }
    private val lock = Lock()
    private val cache = HashMap<String, Index>()

    @Serializable
    internal enum class Kind { ABSENT, FILE, DIRECTORY, SKIPPED }

    /** The state of [path] before [group] (a tool call, or a revert) changed it. */
    @Serializable
    internal data class Record(val seq: Long, val group: String, val turn: Int, val path: String, val kind: Kind, val size: Long = 0)

    @Serializable
    internal data class Revert(val id: String, val groups: List<String>, val turn: Int? = null, val redone: Boolean = false)

    @Serializable
    internal data class Index(val next: Long = 1, val records: List<Record> = emptyList(), val reverts: List<Revert> = emptyList())

    /**
     * Outcome of [record]: whether a copy was [kept] (the step can be undone), why not ([note]),
     * and the turns dropped to make room ([pruned]).
     */
    data class Recorded(val kept: Boolean, val note: String? = null, val pruned: List<Int> = emptyList())

    /** What a revert or redo did, as the session event that tells the model. */
    data class Result(
        val id: String,
        val groups: List<String>,
        val turn: Int?,
        val redoOf: String?,
        val restored: List<String>,
        val deleted: List<String>,
        val missing: List<String>,
    ) {
        fun event() = SessionEvent.WorkspaceRevert(id, groups, turn, redoOf, restored, deleted, missing)
    }

    // -----------------------------------------------------------------------------------------
    // Recording

    /** Keeps [file]'s current state for [group] (a tool call) of [turn]. Call before changing it. */
    fun record(sessionId: String, turn: Int, group: String, file: Path): Recorded = lock.withLock {
        var index = load(sessionId)
        val rel = workspace.relative(file)
        index.records.firstOrNull { it.group == group && it.path == rel }?.let { return Recorded(it.kind != Kind.SKIPPED) }
        val meta = fs.metadataOrNull(file)
        val size = meta?.size ?: 0L
        var pruned = emptyList<Int>()
        val (kind, note) = when {
            meta == null -> Kind.ABSENT to null
            meta.isDirectory -> Kind.DIRECTORY to null
            size > maxFileBytes -> Kind.SKIPPED to "too large to keep a copy (over ${maxFileBytes / MB} MB), so this step can't be undone"
            else -> {
                val (kept, dropped) = makeRoom(sessionId, index, size, turn)
                index = kept
                pruned = dropped
                if (bytes(index) + size > maxSessionBytes) Kind.SKIPPED to "undo history is full (${maxSessionBytes / MB} MB per chat)" else Kind.FILE to null
            }
        }
        val record = Record(index.next, group, turn, rel, kind, if (kind == Kind.FILE) size else 0)
        if (kind == Kind.FILE) {
            fs.createDirectories(blobs(sessionId))
            fs.copy(file, blob(sessionId, record.seq))
        }
        save(sessionId, index.copy(next = index.next + 1, records = index.records + record))
        Recorded(kind != Kind.SKIPPED, note, pruned)
    }

    /** Drops whole turns, oldest first and never [turn] itself, until [size] more bytes fit. */
    private fun makeRoom(sessionId: String, index: Index, size: Long, turn: Int): Pair<Index, List<Int>> {
        var current = index
        val dropped = mutableListOf<Int>()
        while (bytes(current) + size > maxSessionBytes) {
            val oldest = current.records.map { it.turn }.filter { it < turn }.minOrNull() ?: break
            current.records.filter { it.turn == oldest && it.kind == Kind.FILE }.forEach { fs.delete(blob(sessionId, it.seq)) }
            val records = current.records.filter { it.turn != oldest }
            val groups = records.map { it.group }.toSet()
            current = current.copy(records = records, reverts = current.reverts.filter { r -> r.id in groups || r.groups.any { it in groups } })
            dropped += oldest
        }
        return current to dropped
    }

    private fun bytes(index: Index) = index.records.sumOf { if (it.kind == Kind.FILE) it.size else 0L }

    // -----------------------------------------------------------------------------------------
    // Revert and redo

    /** Undoes the changes of tool calls [groups] (and later changes to the same files). */
    fun revertCalls(sessionId: String, groups: Collection<String>): Result? = lock.withLock {
        revert(sessionId, load(sessionId), groups.toSet(), turn = null)
    }

    /** Undoes every change of [turn] still in effect, subagents' included. */
    fun revertTurn(sessionId: String, turn: Int): Result? = lock.withLock {
        val index = load(sessionId)
        revert(sessionId, index, index.records.filter { it.turn == turn && !isRevert(it.group) }.map { it.group }.toSet(), turn)
    }

    /** Re-applies what revert [revertId] undid. */
    fun redo(sessionId: String, revertId: String): Result? = lock.withLock {
        val index = load(sessionId)
        val revert = index.reverts.firstOrNull { it.id == revertId && !it.redone } ?: return null
        val records = index.records.filter { it.group == revertId }.sortedByDescending { it.seq }
        if (records.isEmpty()) return null
        val restored = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val missing = mutableListOf<String>()
        for (r in records) {
            val existed = runCatching { fs.exists(workspace.resolve(r.path)) }.getOrDefault(false)
            when {
                !restore(sessionId, r) -> missing += r.path
                r.kind == Kind.ABSENT -> if (existed) deleted += r.path
                else -> restored += r.path
            }
        }
        val id = "$REVERT_PREFIX${index.next}"
        save(sessionId, index.copy(next = index.next + 1, reverts = index.reverts.map { if (it.id == revertId) it.copy(redone = true) else it }))
        Result(id, revert.groups, revert.turn, revertId, restored, deleted, missing)
    }

    private fun revert(sessionId: String, index: Index, seed: Set<String>, turn: Int?): Result? {
        val undone = index.reverts.filterNot { it.redone }.flatMap { it.groups }.toSet()
        val live = index.records.filter { !isRevert(it.group) && it.group !in undone }
        val groups = live.map { it.group }.filter { it in seed }.toMutableSet()
        if (groups.isEmpty()) return null
        // A file goes back to how it was before the step, so later steps on it are undone too.
        while (true) {
            val first = HashMap<String, Long>()
            live.filter { it.group in groups }.forEach { r -> first[r.path] = minOf(first[r.path] ?: Long.MAX_VALUE, r.seq) }
            val more = live.filter { it.group !in groups && (first[it.path]?.let { s -> it.seq > s } == true) }.map { it.group }
            if (more.isEmpty()) break
            groups += more
        }
        val targets = live.filter { it.group in groups }.sortedByDescending { it.seq }
        val id = "$REVERT_PREFIX${index.next}"
        var next = index.next + 1
        val revertTurn = targets.maxOf { it.turn }

        // Keep what the revert replaces, so it can be redone.
        val captured = targets.map { it.path }.distinct().map { path -> capture(sessionId, path, id, revertTurn, next++) }
        val ok = HashMap<String, Boolean>()
        for (r in targets) ok[r.path] = restore(sessionId, r) && ok[r.path] != false
        val earliest = targets.groupBy { it.path }.mapValues { (_, rs) -> rs.minBy { it.seq } }

        val restored = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val missing = mutableListOf<String>()
        for ((path, r) in earliest) {
            val before = captured.first { it.path == path }.kind
            when {
                r.kind == Kind.SKIPPED || ok[path] == false -> missing += path
                r.kind == Kind.ABSENT -> if (before != Kind.ABSENT) deleted += path
                else -> restored += path
            }
        }
        val ordered = groups.sortedBy { g -> targets.filter { it.group == g }.minOf { it.seq } }
        save(
            sessionId,
            index.copy(next = next, records = index.records + captured, reverts = index.reverts + Revert(id, ordered, turn)),
        )
        return Result(id, ordered, turn, null, restored.sorted(), deleted.sorted(), missing.sorted())
    }

    private fun capture(sessionId: String, path: String, group: String, turn: Int, seq: Long): Record {
        val file = runCatching { workspace.resolve(path) }.getOrNull()
        val meta = file?.let { fs.metadataOrNull(it) }
        val size = meta?.size ?: 0L
        val kind = when {
            meta == null -> Kind.ABSENT
            meta.isDirectory -> Kind.DIRECTORY
            size > maxFileBytes -> Kind.SKIPPED
            else -> Kind.FILE
        }
        if (kind == Kind.FILE && file != null) {
            fs.createDirectories(blobs(sessionId))
            fs.copy(file, blob(sessionId, seq))
        }
        return Record(seq, group, turn, path, kind, if (kind == Kind.FILE) size else 0)
    }

    /** Puts [r]'s path back to the recorded state. False when that is impossible. */
    private fun restore(sessionId: String, r: Record): Boolean = try {
        val file = workspace.resolve(r.path)
        val meta = fs.metadataOrNull(file)
        when (r.kind) {
            Kind.SKIPPED -> false
            Kind.ABSENT -> {
                if (meta != null) fs.delete(file)
                true
            }
            Kind.DIRECTORY -> {
                if (meta != null && !meta.isDirectory) fs.delete(file)
                fs.createDirectories(file)
                true
            }
            Kind.FILE -> {
                val blob = blob(sessionId, r.seq)
                if (!fs.exists(blob) || meta?.isDirectory == true) {
                    false
                } else {
                    file.parent?.let(fs::createDirectories)
                    fs.copy(blob, file)
                    true
                }
            }
        }
    } catch (_: IOException) {
        false
    }

    // -----------------------------------------------------------------------------------------
    // Agent-level operations: revert, then tell the model with a `workspace/revert` event.

    /** Undoes one step ([callId]) of [agent]'s session. Null when idle work was not possible or nothing changed. */
    suspend fun undoStep(agent: Agent, callId: String): SessionEvent.WorkspaceRevert? = apply(agent) { revertCalls(agent.id, listOf(callId)) }

    /** Undoes every file change of [turn]. */
    suspend fun undoTurn(agent: Agent, turn: Int): SessionEvent.WorkspaceRevert? = apply(agent) { revertTurn(agent.id, turn) }

    /** Re-applies what revert [revertId] undid. */
    suspend fun redo(agent: Agent, revertId: String): SessionEvent.WorkspaceRevert? = apply(agent) { redo(agent.id, revertId) }

    /** Reverts only between turns: a running turn may be editing the same files. */
    private suspend fun apply(agent: Agent, op: () -> Result?): SessionEvent.WorkspaceRevert? {
        if (agent.status.value == AgentStatus.RUNNING) return null
        val result = withContext(IoDispatcher) { op() } ?: return null
        return result.event().also { agent.log.append(it) }
    }

    /** Forgets [sessionId]'s journal (the conversation was deleted). */
    fun drop(sessionId: String) = lock.withLock {
        cache.remove(sessionId)
        runCatching { fs.deleteRecursively(dir(sessionId)) }
        Unit
    }

    // -----------------------------------------------------------------------------------------
    // Storage

    private fun dir(sessionId: String): Path = root / sessionId.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")

    private fun blobs(sessionId: String): Path = dir(sessionId) / "blobs"

    private fun blob(sessionId: String, seq: Long): Path = blobs(sessionId) / seq.toString()

    private fun load(sessionId: String): Index = cache.getOrPut(sessionId) {
        val file = dir(sessionId) / INDEX
        try {
            if (fs.exists(file)) json.decodeFromString(Index.serializer(), fs.read(file) { readUtf8() }) else Index()
        } catch (_: Exception) {
            Index()
        }
    }

    private fun save(sessionId: String, index: Index) {
        val dir = dir(sessionId)
        fs.createDirectories(dir)
        val tmp = dir / "$INDEX.tmp"
        fs.write(tmp) { writeUtf8(json.encodeToString(Index.serializer(), index)) }
        fs.atomicMove(tmp, dir / INDEX)
        cache[sessionId] = index
    }

    companion object {
        private const val MB = 1024L * 1024L
        const val MAX_SESSION_BYTES = 50 * MB
        const val MAX_FILE_BYTES = 10 * MB
        const val REVERT_PREFIX = "revert-"
        private const val INDEX = "index.json"
        private val json = Json { ignoreUnknownKeys = true }

        private fun isRevert(group: String) = group.startsWith(REVERT_PREFIX)
    }
}
