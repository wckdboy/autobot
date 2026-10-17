package dev.wckdboy.autobot.shared.voice

import dev.wckdboy.autobot.shared.session.NoticeKind
import dev.wckdboy.autobot.shared.session.SessionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTextTest {

    @Test
    fun stripsMarkdownForSpeech() {
        val md = """
            # Plan
            Here is **the** _answer_ with `code` and a [link](https://example.com).
            ```kotlin
            val x = 1
            ```
            - first item
            - [x] done
            > quoted
            | a | b |
            |---|---|
            | 1 | 2 |
            ---
            See https://example.com/page for snake_case_names.
        """.trimIndent()
        assertEquals(
            listOf(
                "Plan.",
                "Here is the answer with code and a link.",
                "code block.",
                "first item.",
                "done.",
                "quoted",
                "a, b.",
                "1, 2.",
                "See link for snake_case_names.",
            ),
            VoiceText.speakable(md).lines(),
        )
    }

    @Test
    fun keepsAnUnterminatedCodeFenceOutOfSpeech() {
        assertEquals("Try this:\ncode block.", VoiceText.speakable("Try this:\n```\nrm -rf /"))
    }

    @Test
    fun splitsSentencesButNotAbbreviationsOrDecimals() {
        val text = "Dr. Smith paid 3.50 dollars, e.g. for coffee. Then he left! Did he? Yes.\nNew paragraph without a stop"
        assertEquals(
            // Short sentences after the first join the one before them.
            listOf("Dr. Smith paid 3.50 dollars, e.g. for coffee.", "Then he left! Did he? Yes.", "New paragraph without a stop"),
            VoiceText.chunks(text),
        )
    }

    @Test
    fun firstChunkStaysShortSoSpeechStartsFast() {
        val chunks = VoiceText.chunks("Sure. Here is a longer explanation of the thing you asked about. And more.")
        assertEquals("Sure.", chunks.first())
        assertEquals("Here is a longer explanation of the thing you asked about. And more.", chunks[1])
    }

    @Test
    fun splitsOverlongSentencesAtCommasThenSpaces() {
        val long = (1..40).joinToString(", ") { "item number $it" } + "."
        val chunks = VoiceText.chunks(long, maxChars = 100)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 100 })
        assertEquals(long.replace(" ", ""), chunks.joinToString("").replace(" ", ""))
        assertTrue(chunks.dropLast(1).all { it.endsWith(",") })

        val words = (1..80).joinToString(" ") { "word" }
        assertTrue(VoiceText.chunks(words, maxChars = 50).all { it.length <= 50 })
    }

    @Test
    fun handlesCjkSentenceEnds() {
        assertEquals(listOf("你好。", "今天天气很好！"), VoiceText.chunks("你好。今天天气很好！"))
    }

    @Test
    fun transcriptDropsWhisperNoiseMarkers() {
        assertEquals("", VoiceText.transcript("[BLANK_AUDIO]"))
        assertEquals("", VoiceText.transcript(" (music)  [ Silence ] "))
        assertEquals("", VoiceText.transcript("..."))
        assertEquals("turn on the lights", VoiceText.transcript("[MUSIC] turn on the lights"))
    }
}

class EndOfSpeechTest {

    /** Feeds 100 ms buffers; returns the first non-LISTENING verdict and when it came. */
    private fun run(detector: EndOfSpeech, levels: List<Float>): Pair<SpeechVerdict, Long>? {
        levels.forEachIndexed { i, level ->
            val at = 1_000L + i * 100L
            val v = detector.onLevel(level, at)
            if (v != SpeechVerdict.LISTENING) return v to (at - 1_000L)
        }
        return null
    }

    @Test
    fun endsAfterSilenceFollowingSpeech() {
        val levels = List(5) { 0.01f } + List(10) { 0.3f } + List(20) { 0.01f }
        val (verdict, at) = run(EndOfSpeech(), levels)!!
        assertEquals(SpeechVerdict.ENDED, verdict)
        // Last loud buffer at 1.4 s; 1.2 s of silence later.
        assertEquals(2_600L, at)
    }

    @Test
    fun shortPausesBetweenWordsDoNotEndIt() {
        val words = List(4) { List(4) { 0.3f } + List(6) { 0.01f } }.flatten()
        assertNull(run(EndOfSpeech(), List(3) { 0.01f } + words))
    }

    @Test
    fun aClickIsNotSpeech() {
        val levels = List(3) { 0.01f } + listOf(0.8f) + List(100) { 0.01f }
        val (verdict, at) = run(EndOfSpeech(noSpeechMs = 5_000), levels)!!
        assertEquals(SpeechVerdict.NO_SPEECH, verdict)
        assertEquals(5_000L, at)
    }

    @Test
    fun noisyRoomRaisesTheGate() {
        val detector = EndOfSpeech()
        // Fan noise at 0.04 (below the base gate) during calibration: gate becomes 0.12.
        val levels = List(4) { 0.04f } + List(30) { 0.09f }
        assertEquals(SpeechVerdict.NO_SPEECH, run(detector, levels + List(60) { 0.09f })!!.first)
        assertEquals(0.12f, detector.gate, 0.001f)
    }

    @Test
    fun afterABargeInQuietEndsTheRecording() {
        // "Stop!" was said over the answer (it is in the preroll); then the user is quiet.
        val detector = EndOfSpeech().apply { primeHeard() }
        val (verdict, at) = run(detector, List(100) { 0.01f })!!
        assertEquals(SpeechVerdict.ENDED, verdict)
        assertEquals(1_200L, at)
    }

    @Test
    fun afterABargeInTheUsersVoiceIsNotTheNoiseFloor() {
        // The user keeps talking: their voice in the first moments must not raise the gate over it.
        val detector = EndOfSpeech().apply { primeHeard() }
        val talking = List(4) { 0.15f } + List(20) { if (it % 3 == 2) 0.06f else 0.15f }
        assertNull(run(detector, talking))
        assertEquals(0.05f, detector.gate, 0.001f)
    }
}

class VoiceTurnTest {

    private fun user(k: String) = SessionItem.User(k, "hi")
    private fun answer(k: String, text: String, interrupted: Boolean = false) = SessionItem.Assistant(k, text, null, interrupted, null, null)

    @Test
    fun readsTheLastAnswerOnceTheTurnEnds() {
        val before = listOf(user("e1"), answer("e2", "old"))
        val turn = VoiceTurn(before.map { it.key }.toSet(), startedAtMs = 0)
        // Prompt queued: nothing new yet, agent still idle.
        assertNull(turn.update(before, running = false, nowMs = 100))
        val during = before + user("e3") + answer("e4", "Let me check.")
        assertNull(turn.update(during, running = true, nowMs = 200))
        val done = during + answer("e6", "It is **sunny**.")
        assertEquals(VoiceTurn.Finished("It is **sunny**."), turn.update(done, running = false, nowMs = 300))
    }

    @Test
    fun aTurnThatEndedBeforeRunningWasSeenStillFinishes() {
        val turn = VoiceTurn(emptySet(), startedAtMs = 0)
        assertEquals(VoiceTurn.Finished("ok"), turn.update(listOf(user("e1"), answer("e2", "ok")), running = false, nowMs = 10))
    }

    @Test
    fun theOldTurnEndingIsNotTheVoiceTurn() {
        // Sent while another turn ran: its answer lands after the baseline but before the prompt.
        val turn = VoiceTurn(setOf("e1"), startedAtMs = 0)
        assertNull(turn.update(listOf(user("e1"), answer("e2", "previous")), running = false, nowMs = 10))
    }

    @Test
    fun stoppedOrFailedAnswersAreNotRead() {
        val stopped = VoiceTurn(emptySet(), 0)
        stopped.update(listOf(user("e1")), running = true, nowMs = 1)
        assertEquals(VoiceTurn.Finished(null), stopped.update(listOf(user("e1"), answer("e2", "half", interrupted = true)), running = false, nowMs = 2))

        val failed = VoiceTurn(emptySet(), 0)
        failed.update(listOf(user("e1")), running = true, nowMs = 1)
        val items = listOf(user("e1"), answer("e2", "Let me look."), SessionItem.Notice("e3", "AUTH: bad key", NoticeKind.ERROR))
        assertEquals(VoiceTurn.Finished(null), failed.update(items, running = false, nowMs = 2))
    }

    @Test
    fun givesUpWhenThePromptNeverArrives() {
        val turn = VoiceTurn(emptySet(), startedAtMs = 0)
        assertNull(turn.update(emptyList(), running = false, nowMs = VoiceTurn.TIMEOUT_MS - 1))
        assertNull(turn.update(emptyList(), running = true, nowMs = VoiceTurn.TIMEOUT_MS * 2))
        assertEquals(VoiceTurn.Finished(null), turn.update(emptyList(), running = false, nowMs = VoiceTurn.TIMEOUT_MS))
    }

    @Test
    fun announcesEachApprovalOnce() {
        val turn = VoiceTurn(emptySet(), 0)
        assertTrue(turn.needsAnnouncement(listOf("c1")))
        assertFalse(turn.needsAnnouncement(listOf("c1")))
        assertTrue(turn.needsAnnouncement(listOf("c1", "c2")))
        assertFalse(turn.needsAnnouncement(emptyList()))
    }

    @Test
    fun knowsWhenItsPromptIsIn() {
        val turn = VoiceTurn(setOf("e1", "e2"), 0)
        assertFalse(turn.promptSeen(listOf(user("e1"), answer("e2", "old"))))
        assertTrue(turn.promptSeen(listOf(user("e1"), answer("e2", "old"), user("e3"))))
    }

    @Test
    fun committedStepsAreHandedOutOnceInOrder() {
        val before = listOf(user("old"), answer("old-a", "earlier answer"))
        val turn = VoiceTurn(before.map { it.key }.toSet(), startedAtMs = 0)
        assertEquals(emptyList<String>(), turn.committedSteps(before))
        val step1 = before + user("e1") + answer("e2", "Let me check.")
        assertEquals(listOf("Let me check."), turn.committedSteps(step1))
        assertEquals(emptyList<String>(), turn.committedSteps(step1))
        val step2 = step1 + answer("e3", " ") + answer("e4", "stopped", interrupted = true) + answer("e5", "It is sunny.")
        assertEquals(listOf("It is sunny."), turn.committedSteps(step2))
    }
}
