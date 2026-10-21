package dev.wckdboy.autobot.feature.chat.conversation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import dev.wckdboy.autobot.core.a2ui.A2uiChatState
import dev.wckdboy.autobot.core.designsystem.motion.InkMotion
import dev.wckdboy.autobot.core.designsystem.motion.LocalInkMotion
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.shared.a2ui.A2uiCatalog
import dev.wckdboy.autobot.shared.a2ui.A2uiChat
import dev.wckdboy.autobot.shared.a2ui.A2uiSubmission
import dev.wckdboy.autobot.shared.session.SessionItem
import dev.wckdboy.autobot.shared.session.ToolState
import java.io.File
import java.lang.reflect.Proxy
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
 * The chat screen with generated UI, dark and light, to `build/a2ui-snapshots/` (for looking at,
 * and for the PR): a booking form the user sent (locked, "Sent"), the agent's answer and a second
 * form still open. The same conversation as iOS's demo chat (`-AutobotOpen a2ui-chat`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h1500dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ChatA2uiSnapshotTest {
    private val out = File("build/a2ui-snapshots").apply { mkdirs() }
    private val catalog = A2uiCatalog.BASIC_CATALOG_ID

    private val booking = """[{"version":"v1.0","createSurface":{"surfaceId":"booking","catalogId":"$catalog","components":[{"id":"root","component":"Card","child":"col"},{"id":"col","component":"Column","children":["title","name","when","size","note","book"]},{"id":"title","component":"Text","text":"**Table at Lupo** · Friday"},{"id":"name","component":"TextField","label":"Name","value":{"@path":"/b/name"}},{"id":"when","component":"ChoicePicker","label":"Time","options":[{"label":"19:00","value":"19:00"},{"label":"19:30","value":"19:30"},{"label":"20:00","value":"20:00"}],"value":{"@path":"/b/time"},"variant":"mutuallyExclusive","displayStyle":"chips"},{"id":"size","component":"Slider","label":"Guests","value":{"@path":"/b/guests"},"min":1,"max":8},{"id":"note","component":"CheckBox","label":"Window table if possible","value":{"@path":"/b/window"}},{"id":"book","component":"Button","variant":"primary","child":"book_t","action":{"event":{"name":"bookTable","context":{"name":{"@path":"/b/name"},"time":{"@path":"/b/time"},"guests":{"@path":"/b/guests"}}}}},{"id":"book_t","component":"Text","text":"Book"}],"dataModel":{"b":{"name":"","time":[],"guests":2,"window":false}}}}]"""

    private val dessert = """[{"version":"v1.0","createSurface":{"surfaceId":"dessert","catalogId":"$catalog","components":[{"id":"root","component":"Card","child":"col"},{"id":"col","component":"Column","children":["q","pick","when","go"]},{"id":"q","component":"Text","text":"**Pre-order dessert?** Lupo's tiramisu sells out by nine."},{"id":"pick","component":"ChoicePicker","label":"Desserts","options":[{"label":"Tiramisu","value":"tiramisu"},{"label":"Panna cotta","value":"panna"},{"label":"Affogato","value":"affogato"}],"value":{"@path":"/d"},"variant":"multipleSelection","displayStyle":"checkbox"},{"id":"when","component":"TextField","label":"Note for the kitchen","value":{"@path":"/note"}},{"id":"go","component":"Button","variant":"primary","child":"go_t","action":{"event":{"name":"preorderDessert","context":{"picked":{"@path":"/d"},"note":{"@path":"/note"}}}}},{"id":"go_t","component":"Text","text":"Add to booking"}],"dataModel":{"d":["tiramisu"],"note":""}}}]"""

    private val items: List<SessionItem> = listOf(
        SessionItem.User("u1", "Book a table for dinner on Friday, somewhere near the office."),
        SessionItem.Tool("t1", "c1", A2uiChat.TOOL, "surface booking", ToolState.OK, "Shown", null, emptyList(), turn = 1, a2ui = booking),
        SessionItem.User(
            "u2", "Book table",
            a2ui = A2uiSubmission("booking", "bookTable", "Book table", """{"b":{"name":"Mara","time":["19:30"],"guests":4,"window":true}}"""),
        ),
        SessionItem.Assistant("a2", "Booked: **Lupo**, Friday 19:30, four guests, under Mara. A window table is noted as a wish.", null, false, null, "deepseek-chat"),
        SessionItem.Tool("t2", "c2", A2uiChat.TOOL, "surface dessert", ToolState.OK, "Shown", null, emptyList(), turn = 2, a2ui = dessert),
    )

    @Test
    fun rendersAChatWithAGeneratedForm() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        for (dark in listOf(false, true)) {
            val a2ui = A2uiChatState { items }
            val actions = Proxy.newProxyInstance(ChatActions::class.java.classLoader, arrayOf(ChatActions::class.java)) { _, m, _ ->
                when (m.name) {
                    "getDraft" -> ""
                    "getA2ui" -> a2ui
                    else -> if (m.returnType == Boolean::class.javaPrimitiveType) false else null
                }
            } as ChatActions
            activity.setContent {
                AutobotTheme(darkTheme = dark) {
                    CompositionLocalProvider(LocalInkMotion provides InkMotion(reduced = true)) {
                        ChatScreen(ChatUiState(loaded = true, items = items), actions, onBack = {}, onOpenPrivacyCenter = {}, onOpenProviders = {}, onOpenGallery = {})
                    }
                }
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
            val view = activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(out, "chat-a2ui-${if (dark) "dark" else "light"}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(bitmap.width > 0)
        }
    }
}
