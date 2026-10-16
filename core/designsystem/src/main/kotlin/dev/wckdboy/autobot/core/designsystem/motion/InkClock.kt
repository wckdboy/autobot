package dev.wckdboy.autobot.core.designsystem.motion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.wckdboy.autobot.core.designsystem.theme.Motion
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/**
 * The device's motion budget: [reduced] when the user turned animations off (animator or
 * transition scale 0), [lowPower] in Battery Saver (frames drop to 15 fps).
 */
@Immutable
data class InkMotion(val reduced: Boolean = false, val lowPower: Boolean = false) {
    val frameIntervalMillis: Int get() = InkMath.frameIntervalMillis(lowPower)
}

/** Provided by `AutobotTheme`; defaults to full motion (e.g. in isolated previews). */
val LocalInkMotion = staticCompositionLocalOf { InkMotion() }

/** Reads (and follows) the system's animation scales and Battery Saver. */
@Composable
fun rememberInkMotion(): InkMotion {
    if (LocalInspectionMode.current) return InkMotion()
    val context = LocalContext.current.applicationContext
    var reduced by remember { mutableStateOf(animationsOff(context)) }
    var lowPower by remember { mutableStateOf(powerSave(context)) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = animationsOff(context)
            }
        }
        val resolver = context.contentResolver
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE), false, observer)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                lowPower = powerSave(context)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            resolver.unregisterContentObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    return remember(reduced, lowPower) { InkMotion(reduced, lowPower) }
}

private fun animationsOff(context: Context): Boolean {
    val resolver = context.contentResolver
    return Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f ||
        Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) == 0f
}

private fun powerSave(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true

/**
 * One frame loop for a motion tier, shared by every ink animation on screen (spec v3 §4: at most
 * one ticking clock per tier). It ticks at most every [intervalMillis] while at least one
 * consumer holds it, the host is RESUMED and motion is allowed, and stops otherwise. Its time
 * only advances while it ticks.
 */
@Stable
class InkTicker internal constructor() {
    internal val seconds = mutableFloatStateOf(0f)
    private val users = mutableIntStateOf(0)

    /** Ticker time without subscribing the caller. */
    internal val now: Float get() = Snapshot.withoutReadObservation { seconds.floatValue }

    internal fun acquire() {
        users.intValue++
    }

    internal fun release() {
        users.intValue--
    }

    /** Runs the loop while held; call from one effect per ticker. */
    internal suspend fun run(intervalMillis: Int, lifecycle: Lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow { users.intValue > 0 }.collectLatest { held ->
                if (held) tick(intervalMillis)
            }
        }
    }

    private suspend fun tick(interval: Int) {
        val start = seconds.floatValue
        var base = -1L
        var last = -1L
        InkStats.loops++
        try {
            // The main handler, not the frame dispatcher, for the sleeps between frames.
            withContext(Dispatchers.Main) {
                while (true) {
                    val ticked = withFrameNanos { now ->
                        if (base < 0) base = now
                        if (InkMath.isFrameDue(last, now, interval)) {
                            last = now
                            InkStats.ticks++
                            seconds.floatValue = start + (now - base) / 1e9f
                            true
                        } else {
                            false
                        }
                    }
                    // Sleep through most of the interval instead of waking on every vsync.
                    if (ticked) delay(InkMath.idleMillis(interval))
                }
            }
        } finally {
            InkStats.loops--
        }
    }
}

/** The two shared tiers: [active] work (30 fps, 15 in Battery Saver) and [ambient] paper (≤10 fps). */
@Stable
class InkTickers internal constructor() {
    val active = InkTicker()
    val ambient = InkTicker()
}

/** Provided by `AutobotTheme` (one per window); null means "make your own" (isolated previews/tests). */
val LocalInkTickers = staticCompositionLocalOf<InkTickers?> { null }

/** Creates the window's tickers and runs their loops (lifecycle-aware, off with reduced motion). */
@Composable
fun rememberInkTickers(): InkTickers {
    val tickers = remember { InkTickers() }
    val motion = LocalInkMotion.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val enabled = !motion.reduced && !LocalInspectionMode.current
    val interval = motion.frameIntervalMillis
    LaunchedEffect(tickers, enabled, interval, lifecycle) {
        if (enabled) tickers.active.run(interval, lifecycle)
    }
    LaunchedEffect(tickers, enabled, interval, lifecycle) {
        if (enabled) tickers.ambient.run(max(interval, Motion.ambientFrameIntervalMillis), lifecycle)
    }
    return tickers
}

/**
 * One consumer's view of a shared [InkTicker]: seconds of animation time that advance only
 * while this consumer runs and hold still while it is stopped (so patterns never jump when it
 * resumes). Read [time] only in draw lambdas (Canvas, drawBehind, drawWithCache, graphicsLayer):
 * a tick then redraws without recomposing.
 */
@Stable
class InkClock internal constructor(private val ticker: InkTicker) {
    private val running = mutableStateOf(false)
    private var held = 0f
    private var base = 0f

    val time: Float get() = if (running.value) held + ticker.seconds.floatValue - base else held

    internal fun start() {
        base = ticker.now
        running.value = true
    }

    internal fun stop() {
        held += ticker.now - base
        running.value = false
    }
}

/**
 * A clock on the shared ticker of its tier: the active tier (`frameIntervalMillis`, 15 fps in
 * Battery Saver), or the ambient tier when [minIntervalMillis] is `ambientFrameIntervalMillis` or
 * more. It advances only while [running], the screen is RESUMED and motion is allowed; every
 * visible clock of a tier advances on the same frames.
 */
@Composable
fun rememberInkClock(running: Boolean, minIntervalMillis: Int = 0): InkClock {
    val tickers = LocalInkTickers.current ?: rememberInkTickers()
    val ticker = if (minIntervalMillis >= Motion.ambientFrameIntervalMillis) tickers.ambient else tickers.active
    val clock = remember(ticker) { InkClock(ticker) }
    val active = running && !LocalInkMotion.current.reduced && !LocalInspectionMode.current
    DisposableEffect(clock, active) {
        if (!active) return@DisposableEffect onDispose {}
        ticker.acquire()
        clock.start()
        onDispose {
            clock.stop()
            ticker.release()
        }
    }
    return clock
}
