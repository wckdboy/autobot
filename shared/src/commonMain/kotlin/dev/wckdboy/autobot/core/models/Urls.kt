package dev.wckdboy.autobot.core.models

/* Just enough URL handling for hub requests and redirect hops, without a platform URL type. */

private const val HEX = "0123456789ABCDEF"

private fun percentEncode(s: String, keep: (Char) -> Boolean): String = buildString {
    for (b in s.encodeToByteArray()) {
        val c = (b.toInt() and 0xff).toChar()
        if (c.code < 0x80 && keep(c)) {
            append(c)
        } else {
            append('%').append(HEX[(b.toInt() shr 4) and 0xf]).append(HEX[b.toInt() and 0xf])
        }
    }
}

private fun Char.isUnreserved() = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this in "-._~"

/** One path segment (`/` is encoded). */
internal fun encodePathSegment(s: String): String = percentEncode(s) { it.isUnreserved() || it in "!$&'()*+,;=:@" }

/** A path of segments separated by `/`, each encoded. */
internal fun encodePath(path: String): String = path.split('/').joinToString("/") { encodePathSegment(it) }

/** A query name or value. */
internal fun encodeQuery(s: String): String = percentEncode(s) { it.isUnreserved() }

internal fun withQuery(url: String, query: List<Pair<String, String>>): String =
    if (query.isEmpty()) url else url + "?" + query.joinToString("&") { (k, v) -> encodeQuery(k) + "=" + encodeQuery(v) }

private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

/** The lower-case host of an absolute `http(s)` URL, or null. */
internal fun hostOf(url: String): String? {
    val scheme = SCHEME.find(url)?.groupValues?.get(1)?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    val rest = url.substring(scheme.length + 1)
    if (!rest.startsWith("//")) return null
    val authority = rest.substring(2).takeWhile { it != '/' && it != '?' && it != '#' }.substringAfterLast('@')
    val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
    return host.lowercase().takeIf { it.isNotEmpty() }
}

/** [location] (a redirect target) resolved against [base]; null when it is not an `http(s)` URL. */
internal fun resolveLocation(base: String, location: String): String? {
    val loc = location.trim()
    if (loc.isEmpty() || hostOf(base) == null) return null
    SCHEME.find(loc)?.let { return if (hostOf(loc) != null) loc else null }
    val scheme = base.substringBefore(':')
    val afterScheme = base.substring(scheme.length + 3)
    val origin = "$scheme://" + afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
    val path = base.substring(origin.length).substringBefore('#').substringBefore('?')
    return when {
        loc.startsWith("//") -> "$scheme:$loc"
        loc.startsWith("/") -> origin + loc
        loc.startsWith("?") -> origin + path + loc
        loc.startsWith("#") -> base.substringBefore('#') + loc
        else -> origin + path.substringBeforeLast('/', "") + "/" + loc
    }.takeIf { hostOf(it) != null }
}
