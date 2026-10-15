package dev.wckdboy.autobot.providers.remote

/**
 * API keys as pasted: copied keys often carry a newline, a zero-width or no-break space, or
 * smart quotes, and an HTTP header cannot hold any of them (OkHttp throws). [clean] removes what
 * is clearly not part of a key; [problem] explains what is still unusable.
 */
object ApiKeys {
    private val INVISIBLE = setOf('​', '‌', '‍', '⁠', '﻿', ' ', ' ')
    private val QUOTES = setOf('"', '\'', '“', '”', '‘', '’', '`')

    /** Trims whitespace and quotes around the key and drops invisible characters and line breaks inside it. */
    fun clean(raw: String): String =
        raw.filterNot { it in INVISIBLE || it == '\n' || it == '\r' || it == '\t' }.trim().trim { it in QUOTES || it.isWhitespace() }

    /** Why [key] (already [clean]ed) cannot be sent, or null when it can. */
    fun problem(key: String): String? {
        val bad = key.firstOrNull { it.code !in 0x21..0x7E }
        return when {
            bad == null -> null
            bad == ' ' -> "The API key contains a space — paste only the key"
            else -> "The API key contains a character keys never have (U+%04X) — paste it again".format(bad.code)
        }
    }

    /** [raw] ready for a header, or null when it is empty or unusable (see [problem]). */
    fun usable(raw: String?): String? = raw?.let(::clean)?.takeIf { it.isNotEmpty() && problem(it) == null }
}
