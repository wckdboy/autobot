package dev.wckdboy.autobot.core.designsystem.motion

/**
 * Counters for the performance tests (main thread only; plain fields, no cost worth measuring):
 * how many ink frame loops are running and how many frames they produced.
 */
internal object InkStats {
    /** Frame loops currently ticking. */
    var loops = 0

    /** Frames produced by all loops since the last [reset]. */
    var ticks = 0

    fun reset() {
        ticks = 0
    }
}
