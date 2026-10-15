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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

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
 * Seconds of animation time for procedural ink. Read [time] only in draw lambdas (Canvas,
 * drawBehind, graphicsLayer) so a tick redraws without recomposing.
 */
@Stable
class InkClock internal constructor() {
    internal val seconds = mutableFloatStateOf(0f)
    val time: Float get() = seconds.floatValue
}

/**
 * A frame clock that ticks only while [running], the screen is RESUMED and motion is allowed,
 * at most every `frameIntervalMillis` (`lowPowerFrameIntervalMillis` in Battery Saver). Time
 * holds still while stopped and resumes where it left off, so patterns never jump.
 */
@Composable
fun rememberInkClock(running: Boolean): InkClock {
    val clock = remember { InkClock() }
    val motion = LocalInkMotion.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val active = running && !motion.reduced && !LocalInspectionMode.current
    val interval = motion.frameIntervalMillis
    LaunchedEffect(active, interval, lifecycle) {
        if (!active) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val start = clock.seconds.floatValue
            var base = -1L
            var last = -1L
            while (true) {
                val ticked = withFrameNanos { now ->
                    if (base < 0) base = now
                    if (InkMath.isFrameDue(last, now, interval)) {
                        last = now
                        clock.seconds.floatValue = start + (now - base) / 1e9f
                        true
                    } else {
                        false
                    }
                }
                // Sleep through most of the interval instead of waking on every vsync.
                if (ticked) delay(InkMath.idleMillis(interval))
            }
        }
    }
    return clock
}
