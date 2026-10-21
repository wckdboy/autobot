package dev.wckdboy.autobot

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.ReportDrawn
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation3.runtime.rememberNavBackStack
import dagger.hilt.android.AndroidEntryPoint
import dev.wckdboy.autobot.core.data.db.DatabaseHolder
import dev.wckdboy.autobot.core.data.db.DatabaseState
import dev.wckdboy.autobot.core.data.debug.DemoMode

import dev.wckdboy.autobot.core.data.settings.AppSettings
import dev.wckdboy.autobot.core.data.settings.SettingsRepository
import dev.wckdboy.autobot.core.data.settings.ThemeMode
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.designsystem.theme.SystemBarsBase
import dev.wckdboy.autobot.core.security.AppLockManager
import dev.wckdboy.autobot.core.security.LockState
import dev.wckdboy.autobot.core.security.PinResult
import dev.wckdboy.autobot.entry.EntryViewModel
import dev.wckdboy.autobot.feature.home.WelcomeScreen
import dev.wckdboy.autobot.navigation.AutobotNavDisplay
import dev.wckdboy.autobot.shared.panic.PanicCopy
import dev.wckdboy.autobot.navigation.Models
import dev.wckdboy.autobot.navigation.Providers
import dev.wckdboy.autobot.navigation.Remote
import dev.wckdboy.autobot.navigation.Run
import dev.wckdboy.autobot.ui.DatabaseErrorScreen
import dev.wckdboy.autobot.ui.LockScreen
import dev.wckdboy.autobot.ui.PinLockScreen
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single activity. Extends [FragmentActivity] because `BiometricPrompt` needs a fragment host.
 *
 * `FLAG_SECURE` blocks screenshots, screen recording and the recents thumbnail, and is set
 * before any content is attached.
 *
 * System entry points (share sheet, "Ask Autobot" in the text selection menu, the Quick Settings
 * tile, launcher shortcuts, the widget) arrive as intents; [EntryViewModel] holds them until the
 * app is unlocked and set up, so a locked app always shows its lock screen first.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var appLockManager: AppLockManager

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var database: DatabaseHolder

    /** Debug builds' sample data (`--ez demo true`); a no-op in release builds. */
    @Inject
    lateinit var demoSeed: DemoSeed

    private val entry: EntryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Debug builds only: `adb shell am start -n … --ez allow_screenshots true` lets UI work be
        // captured. Release builds always set FLAG_SECURE.
        val allowScreenshots = BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_ALLOW_SCREENSHOTS, false)
        if (!allowScreenshots) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            setRecentsScreenshotEnabled(false)
        }

        // Debug builds only: demo data, a start screen, a theme, past the welcome (see [DebugLaunch]).
        val debugLaunch = DebugLaunch.parse(BuildConfig.DEBUG, { intent.getBooleanExtra(it, false) }, { intent.getStringExtra(it) })
        DemoMode.on = debugLaunch.demo
        DemoMode.digestFailed = debugLaunch.digestFailed

        // Not on recreation (rotation): the request was already taken or is still pending.
        if (savedInstanceState == null) entry.receive(intent)

        // The first frame is blank while settings load and the database opens; the activity reports
        // itself fully drawn (logcat "Fully drawn", Macrobenchmark's time to full display) when a
        // real screen shows: the startup time a person sees.
        setContent {
            val loaded by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
            val settings = loaded ?: AppSettings()
            val lockState by appLockManager.state.collectAsStateWithLifecycle()
            val lockReady by appLockManager.ready.collectAsStateWithLifecycle()
            val quietStart by appLockManager.quietStart.collectAsStateWithLifecycle()
            val databaseState by database.state.collectAsStateWithLifecycle()
            // Open (decrypt) the database up front: if it cannot be opened, say so instead of crashing.
            LaunchedEffect(Unit) { withContext(Dispatchers.IO) { database.check() } }
            var demoReady by remember { mutableStateOf(!debugLaunch.demo) }
            if (debugLaunch.demo && databaseState == DatabaseState.Open) {
                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) { runCatching { demoSeed.seedIfEmpty() } }
                    demoReady = true
                }
            }
            val darkTheme = when (debugLaunch.theme ?: settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            // Bars stay transparent (edge to edge); their icons follow the theme, or a screen's own surface.
            SystemBarsBase(lightSurface = !darkTheme)
            // The next launch screen follows the app's theme: the purple a on ink or on paper.
            if (loaded != null) {
                LaunchedEffect(darkTheme) {
                    runCatching { splashScreen.setSplashScreenTheme(if (darkTheme) R.style.Theme_Autobot else R.style.Theme_Autobot_Paper) }
                }
            }

            AutobotTheme(darkTheme = darkTheme, amoled = settings.amoled, dynamicColor = settings.dynamicColor) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    // Hoisted above the lock gate so navigation state survives lock/unlock.
                    val backStack = rememberNavBackStack(*(debugLaunch.destinations() ?: listOf(Run)).toTypedArray())
                    val scope = rememberCoroutineScope()
                    var lockMessage by rememberSaveable { mutableStateOf<String?>(null) }
                    var resetting by remember { mutableStateOf(false) }
                    val onboarded: () -> Unit = { scope.launch { runCatching { settingsRepository.setOnboarded() } } }
                    if (!lockReady) {
                        // Settings not loaded yet: render nothing rather than risk showing content.
                    } else if (lockState is LockState.Locked) {
                        ReportDrawn()
                        val biometricPrompt = {
                            lockMessage = null
                            appLockManager.authenticate(
                                activity = this@MainActivity,
                                title = getString(R.string.unlock_title),
                                subtitle = getString(R.string.unlock_subtitle),
                                onError = { lockMessage = it.toString() },
                            )
                        }
                        if (appLockManager.usesPin || quietStart) {
                            var status by remember { mutableStateOf(appLockManager.pinStatus()) }
                            var busy by remember { mutableStateOf(false) }
                            PinLockScreen(
                                status = status,
                                message = lockMessage,
                                busy = busy || quietStart,
                                biometrics = !quietStart && appLockManager.biometricsOffered(),
                                onSubmit = { pin ->
                                    busy = true
                                    lockMessage = null
                                    scope.launch {
                                        // The duress PIN never comes back from here: the app restarts emptied.
                                        when (val result = appLockManager.submitPin(pin)) {
                                            PinResult.Unlocked -> Unit
                                            is PinResult.Wrong -> {
                                                status = result.status
                                                lockMessage = if (result.refused) null else PanicCopy.WRONG_PIN
                                            }
                                        }
                                        busy = false
                                    }
                                },
                                onBiometrics = biometricPrompt,
                            )
                            if (quietStart) {
                                // After the duress PIN: the lock screen prints away as after any unlock.
                                LaunchedEffect(Unit) {
                                    delay(QUIET_START_MILLIS)
                                    appLockManager.finishQuietStart()
                                }
                            }
                        } else {
                            LockScreen(message = lockMessage, onUnlock = biometricPrompt)
                        }
                    } else if (databaseState is DatabaseState.Failed) {
                        ReportDrawn()
                        DatabaseErrorScreen(
                            reason = (databaseState as DatabaseState.Failed).reason,
                            resetting = resetting,
                            onReset = {
                                resetting = true
                                scope.launch {
                                    withContext(Dispatchers.IO) { runCatching { database.reset() } }
                                    resetting = false
                                }
                            },
                        )
                    } else if (databaseState != DatabaseState.Open || !demoReady) {
                        // Opening the database (a moment): screens must not query it before it is known to work.
                    } else if (loaded?.onboarded == false && !debugLaunch.skipWelcome) {
                        ReportDrawn()
                        WelcomeScreen(
                            onSetUp = {
                                backStack.clear()
                                backStack.add(Models)
                                onboarded()
                            },
                            // Straight to the sheet that does it; back leads to Remote.
                            onPairPc = {
                                backStack.clear()
                                backStack.add(Remote)
                                backStack.add(Providers(Providers.PAIR))
                                onboarded()
                            },
                            onAddApiKey = {
                                backStack.clear()
                                backStack.add(Remote)
                                backStack.add(Providers(Providers.ADD))
                                onboarded()
                            },

                        )
                    } else if (loaded != null) {
                        // Only while unlocked, set up and resumed (the lock re-arms on start, before
                        // this runs); taking a request does not cancel it.
                        LaunchedEffect(entry) {
                            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                                entry.pending.filterNotNull().collect {
                                    entry.open()?.let { keys ->
                                        backStack.clear()
                                        backStack.addAll(keys)
                                    }
                                }
                            }
                        }
                        AutobotNavDisplay(backStack)
                        ReportDrawn()
                    }
                }
            }
        }
    }

    /** Pickers (photos, files) come back with a result: the lock must not tear the waiting screen down. */
    @Deprecated("Deprecated in Java")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        appLockManager.expectReturn()
        @Suppress("DEPRECATION")
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        entry.receive(intent)
    }
}

/** How long the lock screen stays up after a duress restart before it prints away (a PIN check's time). */
private const val QUIET_START_MILLIS = 250L
private const val EXTRA_ALLOW_SCREENSHOTS = "allow_screenshots"
