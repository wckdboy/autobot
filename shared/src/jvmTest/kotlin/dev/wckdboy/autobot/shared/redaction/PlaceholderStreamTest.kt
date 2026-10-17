package dev.wckdboy.autobot.shared.redaction

import dev.wckdboy.autobot.agent.core.llm.StreamChunk
import dev.wckdboy.autobot.agent.core.session.TokenUsage
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceholderStreamTest {

    private fun vault() = RedactionVault().apply {
        redact("my sister Hannah Schmidt, hannah@posteo.de, IBAN DE89 3704 0044 0532 0130 00")
    }

    private val answer = "Done: I wrote to [EMAIL_1] for [PERSON_1] and paid [IBAN_1]. Ref [PERSON_9], list[0] and 100%."

    @Test
    fun everySplitPointRestoresTheSameText() {
        val vault = vault()
        val expected = vault.restore(answer)
        assertEquals("Done: I wrote to hannah@posteo.de for Hannah Schmidt and paid DE89 3704 0044 0532 0130 00. Ref [PERSON_9], list[0] and 100%.", expected)
        for (cut in 0..answer.length) {
            val stream = PlaceholderStream(vault)
            val out = stream.push(answer.substring(0, cut)) + stream.push(answer.substring(cut)) + stream.flush()
            assertEquals("cut at $cut", expected, out)
        }
    }

    @Test
    fun characterByCharacterNeverShowsAHalfPlaceholder() {
        val vault = vault()
        val stream = PlaceholderStream(vault)
        val shown = StringBuilder()
        for (c in answer) {
            shown.append(stream.push(c.toString()))
            // What is on screen never ends in a fragment of a known placeholder.
            assertFalse(shown.toString(), Regex("""\[(?:EMAIL|PERSON|IBAN)(?:_\d*)?$""").containsMatchIn(shown) && shown.endsWith("_1"))
            assertFalse(shown.toString(), "[EMAIL_1]" in shown || "[PERSON_1]" in shown)
        }
        shown.append(stream.flush())
        assertEquals(vault.restore(answer), shown.toString())
    }

    @Test
    fun randomChunkingsRestoreTheSameText() {
        val vault = vault()
        val text = answer.repeat(3) + " %5BEMAIL_1%5D [EMAIL_1]"
        val expected = vault.restore(text)
        val random = java.util.Random(42)
        repeat(300) {
            val stream = PlaceholderStream(vault)
            val out = StringBuilder()
            var i = 0
            while (i < text.length) {
                val n = 1 + random.nextInt(7)
                out.append(stream.push(text.substring(i, minOf(text.length, i + n))))
                i += n
            }
            out.append(stream.flush())
            assertEquals(expected, out.toString())
        }
    }

    @Test
    fun anUnfinishedPlaceholderAtTheEndIsShownAsIs() {
        val vault = vault()
        val stream = PlaceholderStream(vault)
        assertEquals("cut off at ", stream.push("cut off at [PERS"))
        assertEquals("[PERS", stream.flush())
    }

    @Test
    fun ordinaryBracketsAreNotHeldBack() {
        val vault = vault()
        val stream = PlaceholderStream(vault)
        assertEquals("see [1] and arr[i]", stream.push("see [1] and arr[i]"))
        assertEquals("a [link](x)", stream.push("a [link](x)"))
        // "[E" could still become "[EMAIL_…": held for one chunk.
        assertEquals("x ", stream.push("x [E"))
        assertEquals("[Even more]", stream.push("ven more]"))
    }

    @Test
    fun partialDetection() {
        fun p(s: String) = PlaceholderStream.partial(s, 0)
        assertTrue(p("["))
        assertTrue(p("[P"))
        assertTrue(p("[PERSON"))
        assertTrue(p("[PERSON_"))
        assertTrue(p("[PERSON_12"))
        assertTrue(p("%"))
        assertTrue(p("%5"))
        assertTrue(p("%5B"))
        assertTrue(p("%5BEMAIL_1"))
        assertTrue(p("%5BEMAIL_1%5"))
        assertFalse(p("[PERSON_1]"))
        assertFalse(p("[PERSONS"))
        assertFalse(p("[X"))
        assertFalse(p("[1"))
        assertFalse(p("%20"))
        assertFalse(p("[PERSON_]"))
    }

    @Test
    fun jsonModeEscapes() {
        val vault = RedactionVault()
        vault.addTerms(listOf(PiiKind.PRIVATE to "C:\\Users\\hannah"))
        vault.redact("path C:\\Users\\hannah here")
        val args = """{"path":"[PRIVATE_1]\\notes.txt"}"""
        for (cut in 0..args.length) {
            val stream = PlaceholderStream(vault, json = true)
            val out = stream.push(args.substring(0, cut)) + stream.push(args.substring(cut)) + stream.flush()
            assertEquals("C:\\Users\\hannah\\notes.txt", Json.parseToJsonElement(out).jsonObject["path"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun restoringFlowHandlesTextReasoningAndToolCalls() = runBlocking {
        val vault = vault()
        val chunks = listOf(
            StreamChunk.ReasoningDelta("User wants [PER"),
            StreamChunk.ReasoningDelta("SON_1] mailed."),
            StreamChunk.TextDelta("Mailing [EM"),
            StreamChunk.ToolCallDelta(0, "call_1", "send_mail", """{"to":"[EMAIL"""),
            StreamChunk.ToolCallDelta(1, "call_2", "write", """{"content":"Dear [PERSON_"""),
            StreamChunk.TextDelta("AIL_1] now."),
            StreamChunk.ToolCallDelta(0, null, null, """_1]","subject":"Hi [PERSON_1"""),
            StreamChunk.ToolCallDelta(1, null, null, """1]"}"""),
            StreamChunk.Usage(TokenUsage(10, 5)),
            StreamChunk.ToolCallDelta(0, null, null, """]"}"""),
            StreamChunk.TextDelta(" Bye [PERSON_1"),
            StreamChunk.Finish("tool_calls"),
        )
        val out = flowOf(*chunks.toTypedArray()).restoring(vault).toList()
        assertEquals("User wants Hannah Schmidt mailed.", out.filterIsInstance<StreamChunk.ReasoningDelta>().joinToString("") { it.text })
        // The held-back tail goes out before the finish chunk; nothing follows it.
        assertEquals("Mailing hannah@posteo.de now. Bye [PERSON_1", out.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text })
        assertTrue(out.last() is StreamChunk.Finish)
        val calls = out.filterIsInstance<StreamChunk.ToolCallDelta>().groupBy { it.index }
        val first = Json.parseToJsonElement(calls[0]!!.joinToString("") { it.argumentsDelta }).jsonObject
        assertEquals("hannah@posteo.de", first["to"]!!.jsonPrimitive.content)
        assertEquals("Hi Hannah Schmidt", first["subject"]!!.jsonPrimitive.content)
        assertEquals("Dear Hannah Schmidt", Json.parseToJsonElement(calls[1]!!.joinToString("") { it.argumentsDelta }).jsonObject["content"]!!.jsonPrimitive.content)
        // Ids and names still arrive with each call's first fragment.
        assertEquals("call_1", calls[0]!!.first().id)
        assertEquals("write", calls[1]!!.first().name)
        assertEquals(1, out.count { it is StreamChunk.Usage })
    }

    @Test
    fun aStreamWithoutFinishFlushesAtTheEnd() = runBlocking {
        val vault = vault()
        val out = flowOf(StreamChunk.TextDelta("to [EMAIL_"), StreamChunk.TextDelta("1]")).restoring(vault).toList()
        assertEquals("to hannah@posteo.de", out.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text })
    }
}
