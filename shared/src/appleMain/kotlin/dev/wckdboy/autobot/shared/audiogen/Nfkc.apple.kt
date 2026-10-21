package dev.wckdboy.autobot.shared.audiogen

import platform.Foundation.NSString
import platform.Foundation.precomposedStringWithCompatibilityMapping

@Suppress("CAST_NEVER_SUCCEEDS")
internal actual fun nfkc(text: String): String = (text as NSString).precomposedStringWithCompatibilityMapping
