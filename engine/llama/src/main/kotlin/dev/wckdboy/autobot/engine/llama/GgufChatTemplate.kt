package dev.wckdboy.autobot.engine.llama

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Chat templates of GGUF files, read from their headers without loading the model (cached by
 * path, size and modification time). They tell whether a model reasons and can switch it off.
 * Blocking: call off the main thread.
 */
object GgufChatTemplate {
    private class Entry(val stamp: Pair<Long, Long>, val template: String?)

    private val cache = ConcurrentHashMap<String, Entry>()

    fun read(path: String): String? {
        val file = File(path)
        val stamp = file.length() to file.lastModified()
        cache[path]?.takeIf { it.stamp == stamp }?.let { return it.template }
        return GgufCheck.chatTemplate(path).also { cache[path] = Entry(stamp, it) }
    }
}
