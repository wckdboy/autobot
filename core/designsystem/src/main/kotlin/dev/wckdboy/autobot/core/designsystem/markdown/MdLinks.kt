package dev.wckdboy.autobot.core.designsystem.markdown

/** What the "open this link?" prompt shows about a URL (no lookups: string rules only). */
object MdLinks {
    /** `example.com` for `https://user@Example.com:8080/a?b`, the address for `mailto:`. */
    fun host(url: String): String {
        if (url.startsWith("mailto:", ignoreCase = true)) return url.substring(7).substringBefore('?')
        val scheme = url.indexOf("://")
        if (scheme < 0) return ""
        var authority = url.substring(scheme + 3)
        val end = authority.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (end >= 0) authority = authority.substring(0, end)
        authority = authority.substringAfterLast('@')
        if (!authority.startsWith('[')) authority = authority.substringBefore(':')
        return authority.lowercase()
    }

    /** Plain `http`: anyone on the network can read or change what it loads. */
    fun isInsecure(url: String): Boolean = url.startsWith("http://", ignoreCase = true)
}
