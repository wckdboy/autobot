package dev.wckdboy.autobot.shared.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudStreamTest {

    /** Streams [text] a few characters at a time (like tokens) and collects every piece. */
    private fun stream(text: String, step: Int = 3, reply: String? = text): Pair<List<String>, List<Pair<Int, String>>> {
        val s = ReadAloudStream()
        val all = mutableListOf<String>()
        val at = mutableListOf<Pair<Int, String>>()
        var i = 0
        while (i < text.length) {
            i = minOf(text.length, i + step)
            s.update(text.substring(0, i)).forEach { all += it; at += i to it }
        }
        s.finish(reply).forEach { all += it; at += text.length to it }
        return all to at
    }

    @Test
    fun speaksTheFirstSentenceBeforeTheAnswerEnds() {
        val text = "Sure. The weather in Paris is sunny today, with a light breeze from the west. Take sunglasses."
        val (pieces, at) = stream(text)
        assertEquals("Sure.", pieces.first())
        // Handed out as soon as the next sentence started, long before the end.
        assertTrue(at.first().first < 10)
        assertEquals(VoiceText.speakable(text).replace(" ", ""), pieces.joinToString("").replace(" ", ""))
    }

    @Test
    fun firstPieceLatencyAtTypicalTokenRates() {
        val answer = "The forecast for Paris tomorrow is mostly sunny, with a high of 21 degrees. " +
            "Winds stay light from the west, and there is no rain expected until Thursday evening. " +
            "If you plan to be outside in the afternoon, sunscreen is a good idea. Enjoy your day!"
        // ~4 characters a token, streamed in 4-character steps.
        val (_, at) = stream(answer, step = 4)
        for (tokensPerSecond in listOf(10, 20, 40)) {
            val firstMs = at.first().first / 4 * 1000 / tokensPerSecond
            val wholeMs = answer.length / 4 * 1000 / tokensPerSecond
            println("first audio text ready at $tokensPerSecond tok/s: $firstMs ms after the answer starts (was $wholeMs ms: the whole answer)")
        }
        assertTrue(at.first().first < answer.length / 2)
    }

    @Test
    fun aLongFirstSentenceStartsOnItsFirstClause() {
        val text = "The forecast for Paris tomorrow is mostly sunny, with a high of 21 degrees. Winds stay light."
        val (pieces, at) = stream(text, step = 2)
        assertEquals(listOf("The forecast for Paris tomorrow is mostly sunny,", "with a high of 21 degrees.", "Winds stay light."), pieces)
        assertTrue(at.first().first <= 52)
    }

    @Test
    fun numbersAndAbbreviationsAreNotCut() {
        val text = "It costs 3.14 dollars, e.g. at Dr. Smith's shop in the U.S. Army base. Done here."
        val (pieces, _) = stream(text, step = 1)
        assertEquals(listOf("It costs 3.14 dollars, e.g. at Dr. Smith's shop in the U.S. Army base.", "Done here."), pieces)
    }

    @Test
    fun aDotAtTheEndWaitsForWhatFollows() {
        val s = ReadAloudStream()
        assertEquals(emptyList<String>(), s.update("The answer is 3."))
        assertEquals(emptyList<String>(), s.update("The answer is 3.1"))
        assertEquals(emptyList<String>(), s.update("The answer is 3.14."))
        assertEquals(listOf("The answer is 3.14."), s.update("The answer is 3.14. Next"))
    }

    @Test
    fun codeIsNeverReadEvenWhileItStreams() {
        val text = "Run this:\n\n```bash\nrm -rf build. Then rebuild.\n\nmake all\n```\n\nThat cleans it. Good luck."
        val (pieces, _) = stream(text, step = 2)
        assertTrue(pieces.none { "rm" in it || "make" in it || "rebuild" in it })
        assertEquals(listOf("Run this:", "code block.", "That cleans it.", "Good luck."), pieces.flatMap { VoiceText.sentenceList(it) })
    }

    @Test
    fun markdownListsAndHeadingsArePausedAndStripped() {
        val text = "## Steps\n- **Open** the app\n- Tap `voice`\n\nThat's all."
        val (pieces, _) = stream(text)
        assertEquals(listOf("Steps.", "Open the app.", "Tap voice.", "That's all."), pieces.flatMap { VoiceText.sentenceList(it) })
    }

    @Test
    fun aNewStepStartsAfterTheLastOneIsFlushed() {
        val s = ReadAloudStream()
        assertEquals(emptyList<String>(), s.update("Let me check"))
        // The step ended in a tool call; the next step's text replaces the live frame.
        assertEquals(listOf("Let me check"), s.update("It is"))
        assertEquals(emptyList<String>(), s.update("It is sunny."))
        assertEquals(listOf("It is sunny."), s.finish("It is sunny."))
    }

    @Test
    fun aReplyThatWasNotStreamedIsReadWithoutRepeats() {
        val s = ReadAloudStream()
        assertEquals(listOf("Checking the forecast now."), s.update("Checking the forecast now. Then"))
        val rest = s.finish("Checking the forecast now. It will rain.")
        // "Then" was the streamed step's unfinished tail; the reply's first sentence was already spoken.
        assertEquals(listOf("Then", "It will rain."), rest)
    }

    @Test
    fun nothingStreamedReadsTheWholeReply() {
        val s = ReadAloudStream()
        assertEquals(VoiceText.chunks("Hi there. How can I help you with your plans today?"), s.finish("Hi there. How can I help you with your plans today?"))
    }

    @Test
    fun aStoppedTurnReadsNothingMore() {
        val s = ReadAloudStream()
        s.update("First sentence here. Second")
        assertEquals(emptyList<String>(), s.finish(null))
    }

    @Test
    fun usesThePlatformRenderer() {
        val s = ReadAloudStream(speakable = { it.uppercase() })
        assertEquals(listOf("HELLO."), s.update("hello. world"))
    }

    @Test
    fun paragraphEndIgnoresBlankLinesInFences() {
        assertEquals(null, ReadAloudStream.paragraphEnd("```\na\n\nb\n", 0))
        assertEquals(14, ReadAloudStream.paragraphEnd("```\na\n\nb\n```\n\nc", 0))
        assertEquals(8, ReadAloudStream.paragraphEnd("\nHello\n\nWorld", 0))
    }
}
