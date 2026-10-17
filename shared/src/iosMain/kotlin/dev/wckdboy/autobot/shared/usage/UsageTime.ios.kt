package dev.wckdboy.autobot.shared.usage

import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone

actual fun systemZone(): ZoneRules {
    val zone = NSTimeZone.localTimeZone
    return ZoneRules { utcMs -> zone.secondsFromGMTForDate(NSDate.dateWithTimeIntervalSince1970(utcMs / 1000.0)) * 1000L }
}
