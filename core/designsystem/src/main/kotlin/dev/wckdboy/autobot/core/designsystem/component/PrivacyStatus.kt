package dev.wckdboy.autobot.core.designsystem.component

/** Network privacy state shown to the user. */
enum class PrivacyStatus(val label: String, val description: String) {
    OFFLINE("Offline", "No network access"),
    DIRECT("Direct", "Direct internet connection"),
    TOR("Tor", "Routed through Tor"),
    PROXY("SOCKS5", "Routed through a SOCKS5 proxy"),
}
