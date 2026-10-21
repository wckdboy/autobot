package dev.wckdboy.autobot.feature.chat.conversation

import android.os.SystemClock
import android.util.Log

/**
 * Time to first token shown: from the tap on Send to the first frame that draws reply text,
 * logged once per turn as `AutobotPerf: ttft <ms> ms` (`adb logcat -s AutobotPerf`). No content,
 * only the time. A follow-up queued behind a running turn is not timed (the running answer would
 * take its mark).
 */
internal object ChatTiming {
    @Volatile private var sentAt = 0L

    fun sent() {
        sentAt = SystemClock.uptimeMillis()
    }

    /** A frame showing [text] was drawn. */
    fun shown(text: String) {
        val at = sentAt
        if (at == 0L || text.isEmpty()) return
        sentAt = 0L
        Log.i("AutobotPerf", "ttft ${SystemClock.uptimeMillis() - at} ms")
    }
}
