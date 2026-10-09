package dev.wckdboy.autobot.core.network

/**
 * Loopback host detection without DNS lookups (resolving a name could itself leak).
 *
 * `10.0.2.2` (the emulator's alias for the host machine) is treated as loopback because it never
 * leaves the developer's machine.
 */
object Loopback {
    private val ipv4Loopback = Regex("^127(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")

    fun isLoopback(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.trim().lowercase().removePrefix("[").removeSuffix("]")
        return h == "localhost" ||
            h.endsWith(".localhost") ||
            h == "::1" ||
            h == "0:0:0:0:0:0:0:1" ||
            h == EMULATOR_HOST ||
            ipv4Loopback.matches(h)
    }

    /** Extracts the host part of a URL string without resolving it. */
    fun isLoopbackUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val afterScheme = url.substringAfter("://", url)
        val authority = afterScheme.substringBefore('/').substringAfterLast('@')
        val host = if (authority.startsWith("[")) {
            authority.substringBefore(']') + "]"
        } else {
            authority.substringBefore(':')
        }
        return isLoopback(host)
    }

    const val EMULATOR_HOST = "10.0.2.2"
}
