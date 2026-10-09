package dev.wckdboy.autobot

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.rememberNavBackStack
import dagger.hilt.android.AndroidEntryPoint
import dev.wckdboy.autobot.core.data.settings.AppSettings
import dev.wckdboy.autobot.core.data.settings.SettingsRepository
import dev.wckdboy.autobot.core.data.settings.ThemeMode
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.security.AppLockManager
import dev.wckdboy.autobot.core.security.LockState
import dev.wckdboy.autobot.navigation.AutobotNavDisplay
import dev.wckdboy.autobot.navigation.ChatList
import dev.wckdboy.autobot.ui.LockScreen
import javax.inject.Inject

/**
 * Single activity. Extends [FragmentActivity] because `BiometricPrompt` needs a fragment host.
 *
 * `FLAG_SECURE` blocks screenshots, screen recording and the recents thumbnail, and is set
 * before any content is attached.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var appLockManager: AppLockManager

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setRecentsScreenshotEnabled(false)

        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            val lockState by appLockManager.state.collectAsStateWithLifecycle()
            val lockReady by appLockManager.ready.collectAsStateWithLifecycle()
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            LaunchedEffect(darkTheme) {
                val style = if (darkTheme) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }

            AutobotTheme(darkTheme = darkTheme, amoled = settings.amoled, dynamicColor = settings.dynamicColor) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    // Hoisted above the lock gate so navigation state survives lock/unlock.
                    val backStack = rememberNavBackStack(ChatList)
                    var lockMessage by rememberSaveable { mutableStateOf<String?>(null) }
                    if (!lockReady) {
                        // Settings not loaded yet: render nothing rather than risk showing content.
                    } else if (lockState is LockState.Locked) {
                        LockScreen(
                            message = lockMessage,
                            onUnlock = {
                                lockMessage = null
                                appLockManager.authenticate(
                                    activity = this@MainActivity,
                                    title = getString(R.string.unlock_title),
                                    subtitle = getString(R.string.unlock_subtitle),
                                    onError = { lockMessage = it.toString() },
                                )
                            },
                        )
                    } else {
                        AutobotNavDisplay(backStack)
                    }
                }
            }
        }
    }
}
