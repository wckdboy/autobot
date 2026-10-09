package dev.wckdboy.autobot.core.network

/**
 * Global egress policy of the app.
 *
 * [Offline] is the default: nothing leaves the device (loopback endpoints such as a local Ollama
 * server may be allowed, see [NetworkPolicy.allowLoopbackWhenOffline]).
 */
sealed interface NetworkMode {

    /** All non-loopback traffic is blocked by [KillSwitchInterceptor]. */
    data object Offline : NetworkMode

    /** Direct connections without a proxy. */
    data object Direct : NetworkMode

    /** Route through a Tor SOCKS port. Defaults to Orbot's `127.0.0.1:9050`. */
    data class Tor(val host: String = DEFAULT_TOR_HOST, val port: Int = DEFAULT_TOR_PORT) : NetworkMode

    /** Route through an arbitrary SOCKS5 proxy. */
    data class Socks5(val host: String, val port: Int) : NetworkMode

    companion object {
        const val DEFAULT_TOR_HOST = "127.0.0.1"
        const val DEFAULT_TOR_PORT = 9050

        /** Serializes a mode to a stable string for persistence (e.g. `tor:127.0.0.1:9050`). */
        fun encode(mode: NetworkMode): String = when (mode) {
            Offline -> "offline"
            Direct -> "direct"
            is Tor -> "tor:${mode.host}:${mode.port}"
            is Socks5 -> "socks5:${mode.host}:${mode.port}"
        }

        /** Parses [encode]'s output. Anything unknown or malformed falls back to [Offline]. */
        fun decode(value: String?): NetworkMode {
            if (value.isNullOrBlank()) return Offline
            val kind = value.substringBefore(':')
            val rest = value.substringAfter(':', missingDelimiterValue = "")
            fun endpoint(): Pair<String, Int>? {
                val host = rest.substringBeforeLast(':', missingDelimiterValue = "")
                val port = rest.substringAfterLast(':', missingDelimiterValue = "").toIntOrNull()
                return if (host.isNotBlank() && port != null && port in 1..65535) host to port else null
            }
            return when (kind) {
                "offline" -> Offline
                "direct" -> Direct
                "tor" -> endpoint()?.let { Tor(it.first, it.second) } ?: Offline
                "socks5" -> endpoint()?.let { Socks5(it.first, it.second) } ?: Offline
                else -> Offline
            }
        }
    }
}

/**
 * Per-client routing override (mirrors a provider's routing choice).
 *
 * Resolution against the global [NetworkMode] is done by [RouteResolver].
 */
enum class RouteOverride { INHERIT, DIRECT, TOR, SOCKS5 }

/** Thrown for any request the kill switch refuses. It is an [java.io.IOException] so OkHttp callers handle it naturally. */
class NetworkBlockedException(message: String) : java.io.IOException(message)
