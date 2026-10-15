package dev.wckdboy.autobot.agent.core.approval

import dev.wckdboy.autobot.agent.core.Hooks
import dev.wckdboy.autobot.agent.core.Lock
import dev.wckdboy.autobot.agent.core.Plugin
import dev.wckdboy.autobot.agent.core.PreToolDecision
import dev.wckdboy.autobot.agent.core.loop.Agent
import dev.wckdboy.autobot.agent.core.plugin
import dev.wckdboy.autobot.agent.core.prompt.SystemPromptService
import dev.wckdboy.autobot.agent.core.session.ApprovalOutcome
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.UserMessageKind
import dev.wckdboy.autobot.agent.core.tools.TaskTool
import dev.wckdboy.autobot.agent.core.tools.ToolKind
import dev.wckdboy.autobot.agent.core.tools.ToolRegistry
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Marks data as the user's private data for taint tracking: the results of a tool
 * ([dev.wckdboy.autobot.agent.core.tools.ToolDefinition.privateSource]) or the text of a prompt
 * context ([dev.wckdboy.autobot.agent.core.prompt.PromptContext.privateSource]).
 */
class PrivateSource(
    /** Names the source in the approval reason: "memory", "workspace file". */
    val label: String,
    /** Also track capitalised names (prose such as memory notes); off for code-heavy sources. */
    val names: Boolean = false,
    /** The private part of a result (e.g. without line numbers or boilerplate). */
    val clean: (String) -> String = { it },
)

/** One private fragment found in a network tool's arguments. */
data class TaintHit(
    /** The fragment as it reads (shown and highlighted on the approval card). */
    val text: String,
    /** Where it came from ([PrivateSource.label]). */
    val source: String,
    internal val keys: List<String>,
)

/**
 * Taint tracking for network tools (ROADMAP Phase 2.5, item 4). Private data the agent has seen
 * (memory notes, workspace file contents, text read off an attached image) is indexed as
 * fragments; when a network tool ([ToolKind.FETCH]: web_fetch, web_search, MCP) is about to
 * carry one of them, the call asks first — even under full access or for a trusted MCP server —
 * and the approval card shows exactly which fragment would leave. Normal browsing is untouched.
 *
 * Fragments: emails, numbers of four or more digits (not years), tokens mixing letters and
 * digits (ids, keys), capitalised names from prose sources, and runs of three or more words
 * ([PHRASE_CHARS]+ characters, for text copied verbatim). Arguments are compared case-folded,
 * after URL-decoding and base64-decoding where that yields text. Anything the user typed in this
 * conversation is theirs to send and never counts; neither does a fragment the user allowed
 * "for this session" at a taint prompt.
 *
 * Everything is derived from the session log (tool results, recalled contexts, approvals), so
 * the index rebuilds after a restart. A subagent shares its root session's index both ways: it
 * starts with the parent's taint and what it reads taints the parent.
 */
class TaintTracker {

    private class Index {
        /** `e:`, `n:` and `w:` keys (emails, numbers, words). */
        val fragments = HashMap<String, TaintHit>()
        /** Phrase shingle hash → source label. */
        val phrases = HashMap<Long, String>()
        val declassified = HashSet<String>()
        /** Strict keys (emails, numbers, id-like tokens) of every text the model was shown, for images. */
        val seen = HashSet<String>()
        var images = false
        val cursors = HashMap<String, Long>()
        val pending = HashMap<String, List<String>>()
        /** Private texts already indexed (label + content hash): a file read twice is indexed once. */
        val indexed = HashSet<Long>()
        /** `agentId:callId` of results [record] indexed already; [ingest] skips them once. */
        val recorded = HashSet<String>()
        /** The root agent and its subagents seen so far: a network call ingests all of them. */
        val agents = LinkedHashMap<String, Agent>()
    }

    /**
     * What one log entry adds to the index, extracted outside the lock (regex passes over at most
     * [MAX_SOURCE_CHARS] per text) and merged under it.
     */
    private sealed interface Delta {
        class Prompt(val keys: Set<String>, val strict: Set<String>, val images: Boolean) : Delta
        class Private(val id: Long, val fragments: List<Pair<String, String>>, val phrases: List<Long>, val label: String, val strict: Set<String>) : Delta
        class Seen(val strict: Set<String>) : Delta
        class Decided(val callId: String, val allowed: Boolean) : Delta
        class Skip(val recorded: String, val strict: Set<String>) : Delta
    }

    private val lock = Lock()
    private val indexes = HashMap<String, Index>()

    private fun index(agent: Agent): Index = indexes.getOrPut(rootId(agent.id)) { Index() }

    /** Remembers [agent] (a root session or one of its subagents) for [ingestAll]. Cheap. */
    fun track(agent: Agent) {
        lock.withLock { index(agent).agents.getOrPut(agent.id) { agent } }
    }

    /** Stops tracking a disposed subagent, after indexing what it read last. */
    fun untrack(agent: Agent) {
        ingest(agent)
        lock.withLock { indexes[rootId(agent.id)]?.agents?.remove(agent.id) }
    }

    /**
     * Brings the shared index of [agent]'s root session up to date with every tracked agent of
     * that session (the root and its running subagents). Run before a network tool only: other
     * tools never need it, and private results are already indexed by [record] as they arrive.
     */
    fun ingestAll(agent: Agent) {
        track(agent)
        lock.withLock { ingestAllCalls++ }
        val all = lock.withLock { index(agent).agents.values.toList() }
        all.forEach { ingest(it) }
    }

    /** Indexes the log entries of [agent] not seen yet. */
    fun ingest(agent: Agent) {
        val entries = agent.log.entries.value
        val last = entries.lastOrNull()?.seq ?: return
        val prefix = agent.id + ":"
        val (cursor, recorded) = lock.withLock {
            val index = index(agent)
            (index.cursors[agent.id] ?: 0L) to index.recorded.filterTo(HashSet()) { it.startsWith(prefix) }
        }
        if (last <= cursor) return
        val tools = agent.ctx[ToolRegistry.Key]
        val prompts = agent.ctx[SystemPromptService.Key]
        val root = !TaskTool.isSubagent(agent.id)
        // Extraction (the expensive part) runs outside the lock, so parallel tools don't queue on it.
        val deltas = ArrayList<Pair<Long, Delta>>()
        for (entry in entries) {
            if (entry.seq <= cursor) continue
            val delta: Delta = when (val e = entry.event) {
                is SessionEvent.UserMessage -> when (e.kind) {
                    // A subagent's prompt was written by the parent model, not by the user.
                    UserMessageKind.PROMPT -> if (root) Delta.Prompt(Fragments.keys(e.text), strictKeys(e.text), e.images.isNotEmpty()) else null
                    UserMessageKind.CONTEXT -> {
                        val source = e.contextId?.let { prompts?.privateSource(it) }
                        source?.let { private(e.text, it) } ?: Delta.Seen(strictKeys(e.text))
                    }
                    UserMessageKind.SUMMARY -> Delta.Seen(strictKeys(e.text))
                }
                is SessionEvent.ToolResult -> when {
                    e.isError -> null
                    prefix + e.callId in recorded -> Delta.Skip(prefix + e.callId, strictKeys(e.content))
                    else -> {
                        val live = tools?.get(e.name)?.privateSource
                        val stamped = e.meta?.get(META_KEY) as? JsonObject
                        val label = live?.label ?: (stamped?.get("label") as? JsonPrimitive)?.content
                        if (label != null) {
                            val names = live?.names ?: ((stamped?.get("names") as? JsonPrimitive)?.booleanOrNull == true)
                            private(e.content, PrivateSource(label, names, live?.clean ?: { it }))
                        } else {
                            Delta.Seen(strictKeys(e.content))
                        }
                    }
                }
                is SessionEvent.ApprovalDecided -> Delta.Decided(e.callId, e.outcome == ApprovalOutcome.ALLOWED_FOR_SESSION)
                else -> null
            } ?: continue
            deltas += entry.seq to delta
        }
        lock.withLock {
            val index = index(agent)
            val now = index.cursors[agent.id] ?: 0L
            for ((seq, delta) in deltas) if (seq > now) index.merge(delta)
            index.cursors[agent.id] = maxOf(now, last)
        }
    }

    /** Indexes a private tool result right away (a subagent may end its turn without another call). */
    fun record(agent: Agent, text: String, source: PrivateSource) {
        val delta = private(text, source)
        lock.withLock { index(agent).merge(delta) }
    }

    /** [record] for [callId]'s result, which [ingest] then does not index again. */
    fun record(agent: Agent, callId: String, text: String, source: PrivateSource) {
        val delta = private(text, source)
        lock.withLock {
            val index = index(agent)
            index.merge(delta)
            if (index.recorded.size < MAX_RECORDED) index.recorded += "${agent.id}:$callId"
        }
    }

    /** Private fragments in [args] (a network tool's arguments), in the order they occur. */
    fun check(agent: Agent, args: JsonObject): List<TaintHit> = lock.withLock {
        val index = index(agent)
        if (index.fragments.isEmpty() && index.phrases.isEmpty() && !index.images) return@withLock emptyList()
        val hits = LinkedHashMap<String, TaintHit>()
        for (raw in Fragments.strings(args)) {
            for (text in Fragments.variants(raw)) index.match(text, hits)
        }
        hits.values.toList()
    }

    /** Remembers which fragments a pending approval is about, so "allow for this session" covers them. */
    fun asked(agent: Agent, callId: String, hits: List<TaintHit>) {
        lock.withLock { index(agent).pending[callId] = hits.flatMap { it.keys } }
    }

    /** [ingestAll] calls so far (tests: only network tools bring the index up to date). */
    internal var ingestAllCalls = 0
        private set

    /** Sizes of [sessionId]'s index (tests: the sets stay bounded, texts are indexed once). */
    internal fun sizes(sessionId: String): Map<String, Int> = lock.withLock {
        val index = indexes[rootId(sessionId)] ?: return@withLock emptyMap()
        mapOf(
            "fragments" to index.fragments.size,
            "phrases" to index.phrases.size,
            "seen" to index.seen.size,
            "declassified" to index.declassified.size,
            "indexed" to index.indexed.size,
            "recorded" to index.recorded.size,
        )
    }

    fun forget(sessionId: String) {
        lock.withLock { indexes.remove(rootId(sessionId)) }
    }

    private fun Index.merge(delta: Delta) {
        when (delta) {
            is Delta.Prompt -> {
                if (declassified.size < MAX_KEYS) declassified += delta.keys
                addSeen(delta.strict)
                if (delta.images) images = true
            }
            is Delta.Private -> {
                addSeen(delta.strict)
                if (!indexed.add(delta.id)) return
                if (indexed.size > MAX_INDEXED) indexed.clear()
                for ((key, display) in delta.fragments) {
                    if (fragments.size >= MAX_KEYS) break
                    if (key !in fragments) fragments[key] = TaintHit(display, delta.label, listOf(key))
                }
                for (hash in delta.phrases) {
                    if (phrases.size >= MAX_PHRASES) break
                    phrases.getOrPut(hash) { delta.label }
                }
            }
            is Delta.Seen -> addSeen(delta.strict)
            is Delta.Decided -> {
                val keys = pending.remove(delta.callId)
                if (keys != null && delta.allowed && declassified.size < MAX_KEYS) declassified += keys
            }
            is Delta.Skip -> {
                recorded -= delta.recorded
                addSeen(delta.strict)
            }
        }
    }

    private fun Index.addSeen(keys: Set<String>) {
        if (seen.size < MAX_KEYS) seen += keys
    }

    private fun Index.match(text: String, hits: LinkedHashMap<String, TaintHit>) {
        val lower = text.lowercase()
        for ((key, _) in Fragments.extract(text, names = false, sink = true) + Fragments.wordKeys(lower)) {
            if (key in declassified || key in hits) continue
            val hit = fragments[key]
                // With an image attached, an id or number the model was never shown as text was read off it.
                ?: if (images && Fragments.isStrict(key) && key !in seen) TaintHit(Fragments.display(key, text), IMAGE_SOURCE, listOf(key)) else null
            if (hit != null) hits[key] = hit
        }
        // Numbers: a long private number also counts inside a longer one.
        for (digits in Fragments.digitRuns(lower).map { it.first }) {
            for (start in digits.indices) for (end in (start + LONG_NUMBER)..minOf(digits.length, start + MAX_NUMBER)) {
                val key = "n:" + digits.substring(start, end)
                if (key in declassified || key in hits) continue
                fragments[key]?.let { hits[key] = it }
            }
        }
        // Phrases: merge overlapping shingles into one span of the argument's words.
        val words = Fragments.words(lower)
        var spanStart = -1
        var spanEnd = -1
        var spanKeys = mutableListOf<String>()
        var spanSource = ""
        fun flush() {
            if (spanStart < 0) return
            val display = words.subList(spanStart, spanEnd).joinToString(" ")
            hits.getOrPut("p:$display") { TaintHit(display, spanSource, spanKeys) }
            spanStart = -1
            spanKeys = mutableListOf()
        }
        for ((start, end, hash) in Fragments.shingles(words)) {
            val source = phrases[hash] ?: continue
            val key = "p:$hash"
            if (key in declassified) continue
            if (spanStart >= 0 && start <= spanEnd) {
                spanEnd = maxOf(spanEnd, end)
            } else {
                flush()
                spanStart = start
                spanEnd = end
                spanSource = source
            }
            spanKeys += key
        }
        flush()
    }

    companion object {
        /** Outside the MCP gate (200) and the permission presets (100): taint asks even when they allow. */
        const val PRIORITY = 300
        const val META_KEY = "private"
        const val IMAGE_SOURCE = "attached image"
        const val PHRASE_CHARS = 24
        private const val LONG_NUMBER = 7
        private const val MAX_NUMBER = 20
        /** Characters of one text that are indexed (or scanned for strict keys). */
        const val MAX_SOURCE_CHARS = 64_000
        private const val MAX_PHRASES = 200_000
        /** Bound of each key set (fragments, seen, declassified) per root session. */
        const val MAX_KEYS = 100_000
        private const val MAX_INDEXED = 10_000
        private const val MAX_RECORDED = 10_000

        private fun strictKeys(text: String): Set<String> = Fragments.strictKeys(text.take(MAX_SOURCE_CHARS))

        private fun private(text: String, source: PrivateSource): Delta.Private {
            val clean = Fragments.stripUrls(source.clean(text).take(MAX_SOURCE_CHARS))
            val id = (source.label.hashCode().toLong() shl 32) xor (clean.hashCode().toLong() and 0xffffffffL) xor
                (if (source.names) 1L shl 63 else 0L) xor (clean.length.toLong() shl 20)
            return Delta.Private(
                id = id,
                fragments = Fragments.extract(clean, source.names),
                phrases = Fragments.shingles(Fragments.words(clean.lowercase())).map { it.third },
                label = source.label,
                strict = strictKeys(text),
            )
        }

        /** The session whose taint a (sub)agent shares. */
        fun rootId(sessionId: String): String = TaskTool.rootId(sessionId)

        /** "Would send private data: memory — 'Hannah Schmidt'". */
        fun reason(hits: List<TaintHit>): String =
            "Would send private data: " + hits.take(3).joinToString(", ") { "${it.source} — '${shorten(it.text)}'" } +
                if (hits.size > 3) " (+${hits.size - 3} more)" else ""

        private fun shorten(text: String) = if (text.length <= 48) text else text.take(47).trimEnd() + "…"
    }
}

/**
 * Applies [TaintTracker] in `tools/pre-execute` and stamps private tool results in
 * `tools/post-execute` (so the log keeps their source after a restart, even if the tool is gone).
 */
val TaintTrackingPlugin: Plugin = taintTrackingPlugin { TaintTracker() }

/** [TaintTrackingPlugin] with the tracker from [tracker] (one per install; tests inspect it). */
internal fun taintTrackingPlugin(priority: Int = TaintTracker.PRIORITY, tracker: () -> TaintTracker): Plugin = plugin("taint-tracking") { ctx ->
    val tracker = tracker()
    ctx.intercept(Hooks.ToolPreExecute, priority = priority) { input, next ->
        val agent: Agent = input.agent
        tracker.track(agent)
        // Only a network tool can carry private data off the phone: other calls skip the index.
        if (input.tool.kind != ToolKind.FETCH) return@intercept next(input)
        tracker.ingestAll(agent)
        val hits = tracker.check(agent, input.args)
        if (hits.isEmpty()) return@intercept next(input)
        val reason = TaintTracker.reason(hits)
        if (agent.approvalPolicy.value == ApprovalPolicy.NEVER) return@intercept PreToolDecision.Deny(reason)
        tracker.asked(agent, input.callId, hits)
        PreToolDecision.Ask(reason, hits.map { it.text }.take(8))
    }
    ctx.intercept(Hooks.ToolPostExecute) { input, next ->
        val output = next(input)
        val source = input.call.tool.privateSource
        if (source == null || output.isError) return@intercept output
        val stamp = buildJsonObject {
            put("label", source.label)
            put("names", source.names)
        }
        tracker.record(input.call.agent, input.call.callId, output.content, source)
        output.copy(meta = JsonObject((output.meta ?: JsonObject(emptyMap())) + (TaintTracker.META_KEY to stamp)))
    }
    ctx.on(Hooks.AgentDisposed) { agent -> if (TaskTool.isSubagent(agent.id)) tracker.untrack(agent) else tracker.forget(agent.id) }
}

/** Fragment extraction and normalisation (pure; shared by sources and sinks). */
internal object Fragments {
    private val EMAIL = Regex("""[\p{L}\p{N}._%+-]+@[\p{L}\p{N}-]+(?:\.[\p{L}\p{N}-]+)+""")
    private val URL = Regex("""(?i)\b(?:https?|ftp)://\S+""")
    private val TOKEN = Regex("""[\p{L}\p{N}]+""")
    private val DIGITS = Regex("""\d(?:[ \-./]?\d)*""")
    private val BASE64 = Regex("""[A-Za-z0-9+/_-]{12,}={0,2}""")

    fun stripUrls(text: String): String = URL.replace(text, " ")

    fun words(lower: String): List<String> = TOKEN.findAll(lower).map { it.value }.toList()

    /** `(digits, as written)` for every run of digits (single separators allowed between them). */
    fun digitRuns(text: String): List<Pair<String, String>> =
        DIGITS.findAll(text).map { m -> m.value.filter { it.isDigit() } to m.value }.toList()

    /**
     * `(key, display)` of the fragments in [text]: emails, numbers, id-like tokens and (when
     * [names]) capitalised names that do not start a sentence. On a [sink] every token counts.
     */
    fun extract(text: String, names: Boolean, sink: Boolean = false): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        EMAIL.findAll(text).forEach { out += "e:${it.value.lowercase()}" to it.value }
        for ((digits, written) in digitRuns(text)) if (isNumber(digits)) out += "n:$digits" to written.trim()
        if (sink) return out
        for (m in TOKEN.findAll(text)) {
            val token = m.value
            val lower = token.lowercase()
            if (isIdLike(token) && lower !in TECH_WORDS) {
                out += "w:$lower" to token
            } else if (names && token.length >= 4 && token[0].isUpperCase() && token.drop(1).any { it.isLowerCase() } &&
                lower !in STOPWORDS && !startsSentence(text, m.range.first)
            ) {
                out += "w:$lower" to token
            }
        }
        return out
    }

    /** Word keys of a (lower-cased) sink, matched against id-like tokens and names. */
    fun wordKeys(lower: String): List<Pair<String, String>> = words(lower).map { "w:$it" to it }

    /** Everything a user prompt contains, as keys (all words, numbers, emails, phrases). */
    fun keys(text: String): Set<String> {
        val lower = text.lowercase()
        val words = words(lower)
        return buildSet {
            words.forEach { add("w:$it") }
            extract(text, names = false, sink = true).forEach { add(it.first) }
            digitRuns(lower).forEach { add("n:${it.first}") }
            shingles(words).forEach { add("p:${it.third}") }
        }
    }

    /** Keys that an attached image could explain: emails, numbers, id-like tokens. */
    fun strictKeys(text: String): Set<String> = buildSet {
        extract(text, names = false, sink = true).forEach { add(it.first) }
        for (m in TOKEN.findAll(text)) if (isIdLike(m.value)) add("w:${m.value.lowercase()}")
    }

    fun isStrict(key: String) = key.startsWith("e:") || key.startsWith("n:") || (key.startsWith("w:") && isIdLike(key.substring(2)))

    fun display(key: String, text: String): String {
        val value = key.substringAfter(':')
        val at = text.lowercase().indexOf(value)
        return if (at >= 0 && key[0] != 'n') text.substring(at, at + value.length) else value
    }

    /**
     * Word shingles: from each word, the words up to [TaintTracker.PHRASE_CHARS] characters
     * (at least three words), as `(start, end, hash)`.
     */
    fun shingles(words: List<String>): List<Triple<Int, Int, Long>> {
        val out = ArrayList<Triple<Int, Int, Long>>()
        for (start in words.indices) {
            var length = words[start].length
            var end = start + 1
            while ((length < TaintTracker.PHRASE_CHARS || end - start < 3) && end < words.size) {
                length += 1 + words[end].length
                end++
            }
            if (length < TaintTracker.PHRASE_CHARS || end - start < 3) break
            out += Triple(start, end, hash(words, start, end))
        }
        return out
    }

    /** FNV-1a over the words joined by single spaces. */
    private fun hash(words: List<String>, start: Int, end: Int): Long {
        var h = -0x340d631b7bdddcdbL
        for (i in start until end) {
            if (i > start) h = (h xor ' '.code.toLong()) * 0x100000001b3L
            for (c in words[i]) h = (h xor c.code.toLong()) * 0x100000001b3L
        }
        return h
    }

    /** All string values in [element] (keys are the tool's own names, not data). */
    fun strings(element: JsonElement): List<String> = when (element) {
        is JsonPrimitive -> listOf(element.content)
        is JsonArray -> element.flatMap { strings(it) }
        is JsonObject -> element.values.flatMap { strings(it) }
    }

    /** [raw], URL-decoded (repeatedly), and base64 tokens that decode to text. */
    fun variants(raw: String): List<String> {
        val out = LinkedHashSet<String>()
        var current = raw
        out += current
        repeat(3) {
            val decoded = percentDecode(current)
            if (decoded != current) {
                out += decoded
                current = decoded
            }
        }
        for (text in out.toList()) for (m in BASE64.findAll(text)) {
            // A path segment may be the payload, so each segment is tried too.
            (listOf(m.value) + m.value.split('/')).forEach { candidate -> base64Text(candidate)?.let { out += it } }
        }
        return out.toList()
    }

    private fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = StringBuilder()
        val bytes = ArrayList<Byte>()
        fun flush() {
            if (bytes.isNotEmpty()) out.append(bytes.toByteArray().decodeToString())
            bytes.clear()
        }
        var i = 0
        while (i < s.length) {
            val hex = if (s[i] == '%' && i + 2 < s.length && isHex(s[i + 1]) && isHex(s[i + 2])) s.substring(i + 1, i + 3).toInt(16) else null
            if (hex != null) {
                bytes += hex.toByte()
                i += 3
            } else {
                flush()
                out.append(if (s[i] == '+') ' ' else s[i])
                i++
            }
        }
        flush()
        return out.toString()
    }

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    @OptIn(ExperimentalEncodingApi::class)
    private fun base64Text(token: String): String? {
        val body = token.trimEnd('=')
        if (body.length < 12 || body.all { it.isLetter() } || body.all { it.isDigit() }) return null
        val padded = body + "=".repeat((4 - body.length % 4) % 4)
        val codec = if ('-' in body || '_' in body) Base64.UrlSafe else Base64.Default
        val bytes = runCatching { codec.decode(padded) }.getOrNull() ?: return null
        val text = bytes.decodeToString()
        val printable = text.count { !it.isISOControl() || it == '\n' || it == '\t' } - text.count { it == '�' }
        return text.takeIf { it.isNotEmpty() && printable >= text.length * 0.9 }
    }

    private fun isNumber(digits: String): Boolean =
        digits.length >= 4 && !(digits.length == 4 && (digits.startsWith("19") || digits.startsWith("20")))

    /** Ids, keys, codes: six or more characters mixing letters and digits. */
    private fun isIdLike(token: String): Boolean =
        token.length >= 6 && token.any { it.isLetter() } && token.count { it.isDigit() } >= 2

    private fun startsSentence(text: String, at: Int): Boolean {
        var i = at - 1
        while (i >= 0 && (text[i] == ' ' || text[i] == '\t' || text[i] in "\"'(*-“‘")) i--
        return i < 0 || text[i] in ".!?:;\n]#→•"
    }

    private val TECH_WORDS = setOf(
        "base64", "base32", "sha256", "sha512", "sha384", "sha224", "x86_64", "aarch64", "arm64e", "utf16le", "utf16be",
        "utf32", "win32", "win64", "ipv4", "ipv6", "h264", "h265", "x264", "x265", "mp4a", "1080p", "720p", "2160p",
        "1440p", "4k60", "i18n", "l10n", "k8s", "ed25519", "rsa2048", "rsa4096", "aes128", "aes256", "es2015", "es2020",
        "es2022", "ecma262", "rfc2616", "rfc7231", "rfc9110", "rfc3339", "iso8601", "iso639", "w3c", "http2", "http3",
        "python3", "java17", "java21", "jdk17", "jdk21", "net6", "net8", "gpt4", "gpt4o", "o3mini", "llama3", "qwen3",
    )

    /** Capitalised words that are not names (sentence furniture, days, months, common nouns). */
    private val STOPWORDS = setOf(
        "user", "users", "this", "that", "these", "those", "there", "their", "they", "them", "then", "than", "when", "where",
        "what", "which", "while", "with", "will", "would", "should", "could", "have", "from", "into", "your", "yours",
        "also", "after", "before", "about", "above", "below", "because", "every", "some", "many", "most", "more", "much",
        "very", "only", "just", "even", "here", "note", "notes", "todo", "done", "none", "true", "false", "null",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "january", "february", "march",
        "april", "june", "july", "august", "september", "october", "november", "december", "today", "tomorrow",
        "yesterday", "morning", "evening", "night", "week", "weekend", "month", "year", "please", "thanks", "hello",
        "english", "german", "french", "spanish", "italian", "internet", "google", "apple", "android", "iphone",
        "email", "phone", "address", "name", "birthday", "favourite", "favorite", "prefers", "likes", "works", "lives",
        "born", "home", "work", "office", "school", "doctor", "mom", "mum", "dad", "mother", "father", "sister",
        "brother", "wife", "husband", "partner", "friend", "family", "kids", "children", "project", "team", "meeting",
    )
}
