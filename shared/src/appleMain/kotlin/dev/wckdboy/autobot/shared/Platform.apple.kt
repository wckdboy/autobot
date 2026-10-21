package dev.wckdboy.autobot.shared

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import okio.FileSystem
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale

actual val IoDispatcher: CoroutineDispatcher get() = Dispatchers.IO

actual val SystemFileSystem: FileSystem get() = FileSystem.SYSTEM

actual fun todayIso(): String = NSDateFormatter().run {
    locale = NSLocale(localeIdentifier = "en_US_POSIX")
    dateFormat = "yyyy-MM-dd"
    stringFromDate(NSDate())
}
