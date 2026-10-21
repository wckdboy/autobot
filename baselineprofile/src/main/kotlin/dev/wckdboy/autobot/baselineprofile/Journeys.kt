package dev.wckdboy.autobot.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/** The app under test: `ai.wckd.autobot`, or `-e targetAppId …` for another flavor. */
val targetPackage: String
    get() = InstrumentationRegistry.getArguments().getString("targetAppId") ?: "ai.wckd.autobot"

private const val WAIT_MS = 5_000L

private fun label(text: String): Pattern = Pattern.compile("(?i)\\s*${Pattern.quote(text)}\\s*")

/**
 * Cold start to the first screen that shows content: the welcome screen on a fresh install,
 * otherwise Run. The app lock stays out of the way on a device without a screen lock (the lock
 * needs a credential to exist); with one, the lock screen is what gets profiled.
 */
fun MacrobenchmarkScope.startAndWait() {
    pressHome()
    startActivityAndWait()
    device.wait(Until.hasObject(By.pkg(targetPackage).depth(0)), WAIT_MS)
}

/** Past the welcome screen (fresh install) to the bottom bar. */
fun MacrobenchmarkScope.skipWelcome() {
    device.findObject(By.text(label("Set up this phone")))?.let {
        it.click()
        device.waitForIdle()
    }
}

/** Walks the four tabs and the screens most sessions touch, scrolling each list once. */
fun MacrobenchmarkScope.visitTabs() {
    for (tab in listOf("models", "remote", "settings", "run")) {
        val target = device.wait(Until.findObject(By.text(label(tab))), WAIT_MS) ?: continue
        target.click()
        device.waitForIdle()
        scrollOnce()
    }
}

/**
 * The screens behind the tabs that most sessions open: all chats, a new chat with the composer
 * (typing in it), and a Settings page. On a wide window they open beside their list.
 */
fun MacrobenchmarkScope.visitScreens() {
    tap("run")
    if (tap("All")) {
        scrollOnce()
        if (tap("New")) {
            // A new chat focuses its composer itself, which recomposes the field: each try finds it again.
            val field = By.clazz("android.widget.EditText")
            if (device.wait(Until.hasObject(field), WAIT_MS)) {
                repeat(3) { if (runCatching { device.findObject(field)?.text = "Hello" }.isSuccess) return@repeat }
                device.waitForIdle()
                device.pressBack() // the keyboard
            }
            device.pressBack()
            device.waitForIdle()
        }
        device.pressBack()
        device.waitForIdle()
    }
    tap("settings")
    if (tap("Privacy Center")) {
        scrollOnce()
        device.pressBack()
        device.waitForIdle()
    }
    tap("run")
}

/** Taps the element labelled [text]; false when it does not show. */
private fun MacrobenchmarkScope.tap(text: String): Boolean {
    val target = device.wait(Until.findObject(By.text(label(text))), WAIT_MS) ?: return false
    target.click()
    device.waitForIdle()
    return true
}

private fun MacrobenchmarkScope.scrollOnce() {
    val w = device.displayWidth
    val h = device.displayHeight
    device.swipe(w / 2, h * 3 / 4, w / 2, h / 4, 20)
    device.waitForIdle()
    device.swipe(w / 2, h / 4, w / 2, h * 3 / 4, 20)
    device.waitForIdle()
}
