package dev.wckdboy.autobot.shared.voice

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Hands-free barge-in: decides from the microphone's peak levels (0…1) while an answer is read
 * aloud whether the user started talking over it, without firing on our own voice coming back
 * through the speaker. The apps capture with echo cancellation on (voice-processing I/O on iOS,
 * the voice-communication source and AEC on Android); this is the energy gate behind it.
 *
 * States: [State.IDLE] (nothing playing) → [start] → [State.SETTLING] (the first [settleMs] of
 * playback, learning how loud the echo left by the canceller is) → [State.ARMED] → [State.TRIGGERED]
 * once the level stays above the gate for [minSpeechMs]. The gate is [threshold], raised to
 * [echoRatio] × the echo level (capped at [maxGate]); the echo level follows the quieter readings
 * and decays, so a louder or quieter answer moves it. Clicks and short bumps do not count. When
 * playback resumes after a pause, [resume] settles again.
 * One instance per answer; feed every level with a monotonic timestamp.
 */
class BargeIn(
    private val threshold: Float = 0.12f,
    private val minSpeechMs: Long = 300,
    private val settleMs: Long = 500,
    private val echoRatio: Float = 2.5f,
    private val maxGate: Float = 0.6f,
) {
    /** The default tuning (Swift sees no default arguments). */
    constructor() : this(0.12f)

    enum class State { IDLE, SETTLING, ARMED, TRIGGERED }

    var state: State = State.IDLE
        private set

    private var startedAt = 0L
    private var echo = 0f
    private var runStart = -1L
    private var lastLoud = -1L
    private var lastAt = -1L

    /** Current gate. */
    val gate: Float get() = min(maxGate, max(threshold, echo * echoRatio))

    /** Playback started at [atMs]: learn the echo, then listen for the user. */
    fun start(atMs: Long) {
        if (state != State.IDLE) return
        state = State.SETTLING
        startedAt = atMs
        lastAt = atMs
        runStart = -1
    }

    /**
     * Playback resumes at [atMs] after a pause (the answer was still being written and the
     * speaker went quiet): learn the echo again before listening. During the pause the echo level
     * decays to the room's, and the canceller lets the first moments of the resumed voice through
     * louder; without this, that onset alone could pass for the user talking.
     */
    fun resume(atMs: Long) {
        if (state != State.ARMED) return
        state = State.SETTLING
        startedAt = atMs
        runStart = -1
        lastLoud = -1
    }

    /** Playback is over (or was stopped): back to idle, ready for the next answer. */
    fun reset() {
        state = State.IDLE
        echo = 0f
        runStart = -1
        lastLoud = -1
    }

    /** One level reading; true exactly once, when the user is talking over the answer. */
    fun onLevel(peak: Float, atMs: Long): Boolean {
        val level = if (peak.isFinite()) peak.coerceIn(0f, 1f) else 0f
        val dt = if (lastAt < 0) 0L else (atMs - lastAt).coerceAtLeast(0)
        lastAt = atMs
        when (state) {
            State.IDLE, State.TRIGGERED -> return false
            State.SETTLING -> {
                echo = max(echo, level)
                if (atMs - startedAt >= settleMs) state = State.ARMED
                return false
            }
            State.ARMED -> {
                if (level >= gate) {
                    // A loud reading after a long gap without readings (capture stalled) starts a
                    // new stretch: two bumps far apart are not 300 ms of speech.
                    if (runStart < 0 || dt > STALL_MS) runStart = atMs
                    lastLoud = atMs
                    if (atMs - runStart >= minSpeechMs) {
                        state = State.TRIGGERED
                        return true
                    }
                } else {
                    // Echo level: decays (halves per second) toward the quieter readings.
                    echo = max(level, echo * 0.5f.pow(dt / 1000f))
                    if (runStart >= 0 && atMs - lastLoud > GAP_MS) runStart = -1
                }
                return false
            }
        }
    }

    private companion object {
        /** Quiet shorter than this inside a loud stretch still counts as one stretch. */
        const val GAP_MS = 150L

        /** No reading for this long: what came before is not part of the same stretch. */
        const val STALL_MS = 400L
    }
}
