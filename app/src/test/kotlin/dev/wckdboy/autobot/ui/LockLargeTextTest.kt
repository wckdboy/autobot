package dev.wckdboy.autobot.ui

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createComposeRule
import dev.wckdboy.autobot.core.designsystem.largetext.LargeTextAudit
import dev.wckdboy.autobot.core.designsystem.largetext.LargeTextAudit.Themed
import dev.wckdboy.autobot.shared.panic.LockStatus
import dev.wckdboy.autobot.shared.panic.PanicCopy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The lock screens (the system-credential lock, the PIN lock with its wipe warning, the
 * database error) at the default and the largest system font, on a narrow and a common phone,
 * and on a tablet-wide window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LockLargeTextTest {
    private val compose = createComposeRule()

    // The app's manifest does not declare the compose test host (ui-test-manifest merges only
    // into libraries' test manifests): register it before the compose rule launches it.
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(
        object : ExternalResource() {
            override fun before() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
            }
        },
    ).around(compose)

    private val warned = LockStatus(failures = 8, remaining = 2, warn = true, waitMillis = 0)

    private fun auditLock(width: Int, scale: Float) = LargeTextAudit.render(compose, "lock", width, scale, heightDp = 800) {
        Themed { LockScreen(onUnlock = {}, message = "Authentication failed") }
    }

    private fun auditPin(width: Int, scale: Float) = LargeTextAudit.render(compose, "pin-lock", width, scale, heightDp = 800) {
        Themed { PinLockScreen(warned, PanicCopy.WRONG_PIN, busy = false, biometrics = true, onSubmit = {}, onBiometrics = {}) }
    }

    private fun auditDatabase(width: Int, scale: Float) = LargeTextAudit.render(compose, "database-error", width, scale, heightDp = 800) {
        Themed { DatabaseErrorScreen("Its key is no longer in this phone's keystore", resetting = false, onReset = {}) }
    }

    @Test fun lockDefaultTextFits() = assertEquals(emptyList<LargeTextAudit.Finding>(), auditLock(411, 1f))

    @Test fun lockLargestTextFitsOnANarrowPhone() = assertEquals(emptyList<LargeTextAudit.Finding>(), auditLock(360, 2f))

    @Test fun pinDefaultTextFits() = assertEquals(emptyList<LargeTextAudit.Finding>(), auditPin(411, 1f))

    @Test fun pinLargestTextFitsOnANarrowPhone() = assertEquals(emptyList<LargeTextAudit.Finding>(), auditPin(360, 2f))

    @Test fun pinFitsATabletWideWindow() = assertEquals(emptyList<LargeTextAudit.Finding>(), auditPin(900, 1f))

    @Test fun databaseErrorLargestTextFitsOnANarrowPhone() = assertEquals(emptyList<LargeTextAudit.Finding>(), auditDatabase(360, 2f))
}
