package dev.wckdboy.autobot.shared

import kotlinx.coroutines.CoroutineDispatcher
import okio.FileSystem

/** Dispatcher for blocking file and network I/O. */
expect val IoDispatcher: CoroutineDispatcher

/** The device's real file system. */
expect val SystemFileSystem: FileSystem

/** Today's local date as `YYYY-MM-DD`. */
expect fun todayIso(): String
