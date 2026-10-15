package dev.wckdboy.autobot.feature.chat.conversation

import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.ImageBitmap
import dev.wckdboy.autobot.agent.core.approval.PermissionPreset
import dev.wckdboy.autobot.agent.core.session.ApprovalOutcome
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.shared.session.SessionItem
import dev.wckdboy.autobot.shared.session.ToolState
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * Counts how often the chat screen and its transcript rows compose while a reply streams (ten
 * frames a second) and while voice mode's level meter moves: the frames must reach the live
 * message and the meter only, not the whole screen and every row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w380dp-h1600dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ChatScreenRecompositionTest {
    private val counts = HashMap<String, Int>()

    @After
    fun tearDown() {
        chatCompositionProbe = null
    }

    private fun count(what: String) = counts[what] ?: 0

    /**
     * Lets [ms] pass on the main looper. Apply notifications are sent by hand: Compose's global
     * snapshot watcher is bound to the first test's looper, which Robolectric replaces per test.
     */
    private fun idle(ms: Long = 16) {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    private val history: List<SessionItem> = (0 until 6).flatMap { i ->
        listOf(
            SessionItem.User("u$i", "Question number $i about the files"),
            SessionItem.Tool("t$i", "c$i", "read", "notes-$i.md", ToolState.OK, "contents $i", null, emptyList(), durationMs = 400),
            SessionItem.Assistant("a$i", "Answer $i with **some** markdown and a list:\n- one\n- two", null, false, 42, "m"),
        )
    }

    /**
     * One test: Compose's frame clock stays bound to the first test's main looper, which
     * Robolectric replaces per test, so a second test in this JVM would never recompose.
     *
     * Before the fix (live frames and levels inside the screen state) the same scenarios gave:
     * streaming 30 frames → screen 30, rows 481, live 30; 20 readings → screen 20, rows 320, meter 20.
     * After: streaming → screen 1, row slots 17 (once), row bodies 0, live 30; readings → meter 20 only.
     */
    @Test
    fun streamingAndMicLevelsRecomposeOnlyWhatShows() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        chatCompositionProbe = { counts[it] = count(it) + 1 }

        // A reply streams: ten frames a second.
        val state = mutableStateOf(ChatUiState(loaded = true, items = history, running = true))
        val live = mutableStateOf<LiveUi?>(null)
        activity.setContent { AutobotTheme { ChatScreen(state.value, FakeActions, {}, {}, {}, {}, live = { live.value }) } }
        idle(500)
        counts.clear()
        val frames = 30
        for (i in 1..frames) {
            live.value = LiveUi("Streaming reply ".repeat(i), "", emptyList(), 20f + i)
            idle(100)
        }
        val streaming = HashMap(counts)
        println("STREAMING $frames frames: $streaming")

        // Something unrelated to the rows changes (dictation starts): rows skip.
        counts.clear()
        state.value = state.value.copy(dictation = Dictation.RECORDING)
        idle(100)
        val unrelated = HashMap(counts)
        println("UNRELATED change: $unrelated")

        // Voice mode listens: ten microphone readings a second.
        val voice = mutableStateOf(ChatUiState(loaded = true, items = history, voice = VoiceUi(VoicePhase.LISTENING)))
        val levels = mutableStateOf<List<Float>>(emptyList())
        activity.setContent { AutobotTheme { ChatScreen(voice.value, FakeActions, {}, {}, {}, {}, micLevels = { levels.value }) } }
        idle(500)
        counts.clear()
        val readings = 20
        for (i in 1..readings) {
            levels.value = levels.value + (i % 7) / 7f
            idle(100)
        }
        val listening = HashMap(counts)
        println("LEVELS $readings readings: $listening")

        assertTrue("live message follows every frame: $streaming", (streaming["live"] ?: 0) >= frames)
        // The live item appears once (screen + item list), then frames reach it alone.
        assertTrue("screen recomposed per frame: $streaming", (streaming["screen"] ?: 0) <= 2)
        assertTrue("rows recomposed per frame: $streaming", (streaming["rowBody"] ?: 0) == 0)
        // Row slots re-run at most once (when the live item appears), not per frame.
        assertTrue("row slots re-ran per frame: $streaming", (streaming["row"] ?: 0) <= history.size)
        assertEquals("rows recomposed for an unrelated change: $unrelated", 0, unrelated["rowBody"] ?: 0)
        assertTrue("meter follows every reading: $listening", (listening["meter"] ?: 0) >= readings)
        assertEquals("screen recomposed per reading: $listening", 0, listening["screen"] ?: 0)
        assertEquals("rows recomposed per reading: $listening", 0, listening["row"] ?: 0)
    }

    private object FakeActions : ChatActions {
        override val draft = ""
        override fun setDraft(text: String) = Unit
        override fun send(text: String) = Unit
        override fun stop() = Unit
        override fun attach(uri: Uri) = Unit
        override fun removeAttachment(id: String) = Unit
        override suspend fun attachmentThumbnail(id: String): ImageBitmap? = null
        override suspend fun thumbnail(galleryId: String): ImageBitmap? = null
        override fun toggleDictation() = Unit
        override fun consumeDictation() = Unit
        override fun speak(key: String, text: String) = Unit
        override fun setVoiceMode(on: Boolean) = Unit
        override fun setHandsFree(on: Boolean) = Unit
        override fun voiceTap() = Unit
        override fun voiceDenied() = Unit
        override fun approve(callId: String, outcome: ApprovalOutcome) = Unit
        override fun answer(callId: String, answers: List<String>?) = Unit
        override fun selectProvider(providerId: String) = Unit
        override fun selectModel(model: String, confirmed: Boolean) = Unit
        override fun refreshModels() = Unit
        override fun setToolsEnabled(enabled: Boolean) = Unit
        override fun setPermission(preset: PermissionPreset) = Unit
        override fun compact() = Unit
        override fun rename(title: String) = Unit
        override fun setPinned(pinned: Boolean) = Unit
        override fun setArchived(archived: Boolean) = Unit
        override fun delete() = Unit
        override fun undoStep(callId: String) = Unit
        override fun undoTurn(turn: Int) = Unit
        override fun redo(revertId: String) = Unit
        override fun dismissNotice() = Unit
    }
}
