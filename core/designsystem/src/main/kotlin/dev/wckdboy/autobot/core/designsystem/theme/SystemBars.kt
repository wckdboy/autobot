package dev.wckdboy.autobot.core.designsystem.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import java.util.WeakHashMap

/**
 * The window's system bar icons follow the surface behind them. The app theme sets the base
 * ([SystemBarsBase]); a screen or room with its own surface ([SystemBarsFor], used by
 * [PaperTheme] and [DarkroomTheme]) overrides it while shown. The most recent request wins, so
 * when one surface replaces another (a Studio room, a navigation transition) the order the old
 * one leaves in does not matter: it never restores a stale value over the new surface.
 */
internal class SystemBarRequests(private val apply: (lightIcons: Boolean) -> Unit) {
    private val stack = ArrayList<Any>()
    private val light = HashMap<Any, Boolean>()
    private var base = false

    fun setBase(lightSurface: Boolean) {
        base = lightSurface
        update()
    }

    fun push(token: Any, lightSurface: Boolean) {
        stack.remove(token)
        stack += token
        light[token] = lightSurface
        update()
    }

    fun pop(token: Any) {
        stack.remove(token)
        light.remove(token)
        update()
    }

    /** True when the bars should draw dark icons (a light surface behind them). */
    fun lightSurface(): Boolean = stack.lastOrNull()?.let { light[it] } ?: base

    private fun update() = apply(lightSurface())
}

private val requests = WeakHashMap<Window, SystemBarRequests>()

private fun Window.barRequests(): SystemBarRequests = requests.getOrPut(this) {
    SystemBarRequests { light ->
        val controller = WindowCompat.getInsetsController(this, decorView)
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
    }
}

/** The app theme's surface: what the bars show when no screen asks for anything else. */
@Composable
fun SystemBarsBase(lightSurface: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(view, lightSurface) {
        view.context.findActivity()?.window?.barRequests()?.setBase(lightSurface)
        onDispose { }
    }
}

/** A surface that differs from the app theme (paper, darkroom): its bar icons while shown. */
@Composable
fun SystemBarsFor(lightSurface: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(view, lightSurface) {
        val bars = view.context.findActivity()?.window?.barRequests()
        val token = Any()
        bars?.push(token, lightSurface)
        onDispose { bars?.pop(token) }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
