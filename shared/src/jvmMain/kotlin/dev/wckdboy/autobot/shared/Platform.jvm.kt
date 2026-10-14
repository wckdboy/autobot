package dev.wckdboy.autobot.shared

import java.time.LocalDate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okio.FileSystem

actual val IoDispatcher: CoroutineDispatcher get() = Dispatchers.IO

actual val SystemFileSystem: FileSystem get() = FileSystem.SYSTEM

actual fun todayIso(): String = LocalDate.now().toString()
