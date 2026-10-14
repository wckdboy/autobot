package dev.wckdboy.autobot.core.network

import java.net.InetSocketAddress
import java.net.Proxy

/**
 * [java.net.Proxy] for a shared [Route]. Proxies are created with unresolved addresses so
 * OkHttp/Java hand the *hostname* to the SOCKS server (remote DNS).
 */
fun Route.toProxy(): Proxy? = when (this) {
    Route.Blocked -> null
    Route.Direct -> Proxy.NO_PROXY
    is Route.Socks -> Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(host, port))
}

/** Proxy for a non-loopback host under [mode]. `null` means blocked (offline). */
fun RouteResolver.proxyFor(mode: NetworkMode): Proxy? = route(mode).toProxy()

/** Proxy to use for a request to [host] under [mode]. Loopback is always direct. */
fun RouteResolver.proxyForHost(mode: NetworkMode, host: String): Proxy? = routeForHost(mode, host).toProxy()
