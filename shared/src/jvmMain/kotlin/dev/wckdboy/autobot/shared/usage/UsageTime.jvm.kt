package dev.wckdboy.autobot.shared.usage

import java.util.TimeZone

actual fun systemZone(): ZoneRules {
    val zone = TimeZone.getDefault()
    return ZoneRules { utcMs -> zone.getOffset(utcMs).toLong() }
}
