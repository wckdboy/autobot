package dev.wckdboy.autobot.core.data.debug

/**
 * Sample data for screens that read something live (the node digest), set at launch by debug
 * builds' `demo` intent extra (the app's `DebugLaunch`). Release builds never set it, so it stays
 * off there.
 */
object DemoMode {
    /** Show sample data instead of asking real sources. */
    @Volatile
    var on: Boolean = false

    /** The node digest sample includes a receipt that failed (`demo_digest failed`). */
    @Volatile
    var digestFailed: Boolean = false
}
