package dev.wckdboy.autobot.entry

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EntryParserTest {

    @Test
    fun sharedTextBecomesADraft() {
        val request = EntryParser.parse(Intent.ACTION_SEND, "text/plain", text = "  what does this mean?  ")
        assertEquals(EntryRequest.NewChat(draft = "what does this mean?"), request)
    }

    @Test
    fun sharedPageKeepsTitleAndUrl() {
        val request = EntryParser.parse(Intent.ACTION_SEND, "text/plain", text = "https://example.org/a", subject = "A page")
        assertEquals(EntryRequest.NewChat(draft = "A page\nhttps://example.org/a"), request)
        // A subject already in the text is not repeated.
        val same = EntryParser.parse(Intent.ACTION_SEND, "text/plain", text = "A page https://example.org/a", subject = "A page")
        assertEquals(EntryRequest.NewChat(draft = "A page https://example.org/a"), same)
    }

    @Test
    fun sharedImagesAreAttachedContentOnly() {
        val request = EntryParser.parse(
            Intent.ACTION_SEND_MULTIPLE,
            "image/*",
            streams = listOf("content://photos/1", "file:///data/data/ai.wckd.autobot/files/secret", "content://photos/1", "content://photos/2"),
        )
        assertEquals(EntryRequest.NewChat(images = listOf("content://photos/1", "content://photos/2")), request)
    }

    @Test
    fun atMostFourImages() {
        val streams = (1..6).map { "content://photos/$it" }
        val request = EntryParser.parse(Intent.ACTION_SEND_MULTIPLE, "image/jpeg", streams = streams) as EntryRequest.NewChat
        assertEquals(streams.take(EntryParser.MAX_IMAGES), request.images)
    }

    @Test
    fun streamsOfOtherTypesAreIgnored() {
        assertNull(EntryParser.parse(Intent.ACTION_SEND, "application/pdf", streams = listOf("content://docs/1")))
        assertNull(EntryParser.parse(Intent.ACTION_SEND, "text/plain", text = "   "))
    }

    @Test
    fun selectedTextBecomesADraft() {
        assertEquals(EntryRequest.NewChat(draft = "Ask about this"), EntryParser.parse(Intent.ACTION_PROCESS_TEXT, "text/plain", processText = "Ask about this"))
        assertNull(EntryParser.parse(Intent.ACTION_PROCESS_TEXT, "text/plain", processText = ""))
    }

    @Test
    fun longTextIsCut() {
        val request = EntryParser.parse(Intent.ACTION_PROCESS_TEXT, processText = "x".repeat(EntryParser.MAX_TEXT + 50)) as EntryRequest.NewChat
        assertEquals(EntryParser.MAX_TEXT, request.draft?.length)
    }

    @Test
    fun explicitActions() {
        assertEquals(EntryRequest.NewChat(), EntryParser.parse(EntryActions.NEW_CHAT))
        assertEquals(EntryRequest.Voice, EntryParser.parse(EntryActions.VOICE))
        assertEquals(EntryRequest.Imagine("a red fox"), EntryParser.parse(EntryActions.IMAGINE, prompt = " a red fox "))
        assertEquals(EntryRequest.Imagine(null), EntryParser.parse(EntryActions.IMAGINE))
    }

    @Test
    fun launcherAndUnknownIntentsAreIgnored() {
        assertNull(EntryParser.parse(Intent.ACTION_MAIN))
        assertNull(EntryParser.parse(null))
        assertNull(EntryParser.parse("com.example.SOMETHING", "text/plain", text = "hi"))
    }
}
