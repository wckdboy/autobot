package dev.wckdboy.autobot.core.models

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether any of the app's activities is started. Installed from `Application.onCreate` (it has
 * no dependencies, so that costs nothing), before the first activity starts: a tracker registered
 * later would count 0 while the app is on screen.
 */
@Singleton
class AppVisibility @Inject constructor() {
    @Volatile
    private var started = 0

    @Volatile
    private var installed = false

    private val onVisible = CopyOnWriteArrayList<() -> Unit>()

    /** Whether an activity of the app is started (on screen or about to be). */
    val visible: Boolean get() = started > 0

    /** Starts tracking; call once from `Application.onCreate`. */
    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(callbacks)
    }

    /** Runs [listener] (on the main thread) each time the app becomes visible. */
    fun whenVisible(listener: () -> Unit) {
        onVisible += listener
    }

    internal fun activityStarted() {
        if (started++ == 0) onVisible.forEach { it() }
    }

    internal fun activityStopped() {
        started = (started - 1).coerceAtLeast(0)
    }

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) = activityStarted()
        override fun onActivityStopped(activity: Activity) = activityStopped()
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
