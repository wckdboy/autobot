package dev.wckdboy.autobot.shared.chats

import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.SessionJson
import dev.wckdboy.autobot.agent.core.session.UserMessageKind

/** Characters [start] until [end] (exclusive) to highlight. */
data class HighlightSpan(val start: Int, val end: Int)

/**
 * A chat that matched a search: highlight spans in its title, and a short excerpt of the
 * conversation around the first match (null when only the title matched).
 */
data class ChatMatch(
    val titleSpans: List<HighlightSpan>,
    val snippet: String?,
    val snippetSpans: List<HighlightSpan>,
)

/**
 * On-device chat search over titles and message text. A query is split into terms; a chat matches
 * when every term occurs (ignoring case) in its title or its messages. Both apps keep each chat's
 * searchable text ([textOf]) next to the encrypted log and run the matching here.
 */
object ChatSearch {
    /** Stored searchable text per chat is capped (the start of very long chats is kept). */
    const val MAX_TEXT = 200_000
    private const val BEFORE = 36
    private const val SNIPPET = 140
    private val whitespace = Regex("\\s+")

    fun terms(query: String): List<String> = query.lowercase().split(whitespace).filter { it.isNotEmpty() }.distinct()

    /** The match of [terms] in a chat, or null. No terms: no match. */
    fun match(terms: List<String>, title: String, text: String): ChatMatch? {
        if (terms.isEmpty()) return null
        val lowerTitle = title.lowercase()
        val lowerText = text.lowercase()
        if (!terms.all { lowerTitle.contains(it) || lowerText.contains(it) }) return null
        val first = terms.mapNotNull { t -> lowerText.indexOf(t).takeIf { it >= 0 } }.minOrNull()
        val snippet = first?.let { excerpt(text, it) }
        return ChatMatch(spans(title, terms), snippet, snippet?.let { spans(it, terms) }.orEmpty())
    }

    fun match(query: String, title: String, text: String): ChatMatch? = match(terms(query), title, text)

    /** Every occurrence of [terms] in [text], merged and in order. */
    fun spans(text: String, terms: List<String>): List<HighlightSpan> {
        val lower = text.lowercase()
        if (lower.length != text.length) return emptyList() // case mapping changed lengths: offsets would lie
        val raw = ArrayList<HighlightSpan>()
        for (term in terms) {
            var from = 0
            while (true) {
                val at = lower.indexOf(term, from)
                if (at < 0) break
                raw += HighlightSpan(at, at + term.length)
                from = at + term.length
            }
        }
        raw.sortBy { it.start }
        val merged = ArrayList<HighlightSpan>()
        for (s in raw) {
            val last = merged.lastOrNull()
            if (last != null && s.start <= last.end) merged[merged.size - 1] = HighlightSpan(last.start, maxOf(last.end, s.end)) else merged += s
        }
        return merged
    }

    /** About [SNIPPET] characters of [text] around [at], on word boundaries, whitespace collapsed. */
    internal fun excerpt(text: String, at: Int): String {
        var start = (at - BEFORE).coerceAtLeast(0)
        if (start > 0) {
            val boundary = text.indexOfAny(charArrayOf(' ', '\n', '\t'), start)
            if (boundary in start until at) start = boundary + 1
        }
        var end = (start + SNIPPET).coerceAtMost(text.length)
        if (end < text.length) {
            val boundary = text.lastIndexOfAny(charArrayOf(' ', '\n', '\t'), end)
            if (boundary > at) end = boundary
        }
        val body = text.substring(start, end).replace(whitespace, " ").trim()
        return (if (start > 0) "…" else "") + body + (if (end < text.length) "…" else "")
    }

    /** The term to pre-filter with in SQL (`LIKE`): the longest one, most selective. */
    fun prefilterTerm(terms: List<String>): String? = terms.maxByOrNull { it.length }

    /** A `LIKE` pattern containing [term], escaped with `\`. */
    fun likePattern(term: String): String =
        "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    /** The searchable text of one event: what the user typed and the visible replies. */
    fun textOf(event: SessionEvent): String? = when (event) {
        is SessionEvent.UserMessage -> event.text.takeIf { event.kind == UserMessageKind.PROMPT && it.isNotBlank() }
        is SessionEvent.AssistantMessage -> event.text.takeIf { it.isNotBlank() }
        else -> null
    }

    /** [existing] with [entries]' text appended (one line per message), capped at [MAX_TEXT]. */
    fun append(existing: String, entries: List<LogEntry>): String {
        if (existing.length >= MAX_TEXT) return existing
        val sb = StringBuilder(existing)
        for (entry in entries) {
            val text = textOf(entry.event) ?: continue
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(text)
            if (sb.length >= MAX_TEXT) break
        }
        return if (sb.length > MAX_TEXT) sb.substring(0, MAX_TEXT) else sb.toString()
    }

    /** Searchable text of a log given as JSON lines (Swift); unreadable lines are skipped. */
    fun textOfLines(lines: List<String>): String = append("", decode(lines))

    /** Attachment ids a log references (deleted with the chat). */
    fun attachments(entries: List<LogEntry>): Set<String> =
        entries.flatMapTo(LinkedHashSet()) { (it.event as? SessionEvent.UserMessage)?.images.orEmpty() }

    fun attachmentsOfLines(lines: List<String>): List<String> = attachments(decode(lines)).toList()

    private fun decode(lines: List<String>): List<LogEntry> = lines.mapNotNull { line ->
        runCatching { SessionJson.decodeFromString(LogEntry.serializer(), line) }.getOrNull()
    }
}
