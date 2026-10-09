package dev.wckdboy.autobot.feature.chat

import dev.wckdboy.autobot.core.designsystem.component.PrivacyStatus
import dev.wckdboy.autobot.core.network.NetworkMode

internal fun NetworkMode.toPrivacyStatus(): PrivacyStatus = when (this) {
    NetworkMode.Offline -> PrivacyStatus.OFFLINE
    NetworkMode.Direct -> PrivacyStatus.DIRECT
    is NetworkMode.Tor -> PrivacyStatus.TOR
    is NetworkMode.Socks5 -> PrivacyStatus.PROXY
}
