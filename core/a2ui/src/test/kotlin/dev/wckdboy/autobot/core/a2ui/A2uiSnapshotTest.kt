package dev.wckdboy.autobot.core.a2ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.shared.a2ui.A2uiHost
import java.io.File
import java.time.Duration
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * Renders the spec's contact form and the every-component gallery, dark and light, to
 * `build/a2ui-snapshots/`, for looking at (and for the PR).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class A2uiSnapshotTest {
    private val out = File("build/a2ui-snapshots").apply { mkdirs() }

    @Test
    fun rendersTheContactFormAndEveryComponent() {
        val surfaces = listOf("contact-form" to (A2uiFixtures.contactForm to "contact_form_1"), "gallery" to (A2uiFixtures.gallery to "all"))
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        for ((name, surface) in surfaces) {
            for (dark in listOf(true, false)) {
                val host = A2uiHost().apply { receive(surface.first) }
                activity.setContent {
                    AutobotTheme(darkTheme = dark) {
                        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                            A2uiSurfaceView(host, surface.second, onAction = {})
                        }
                    }
                }
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
                val view = activity.window.decorView
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                File(out, "a2ui-$name-${if (dark) "dark" else "light"}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                assertTrue(bitmap.width > 0)
            }
        }
    }
}
