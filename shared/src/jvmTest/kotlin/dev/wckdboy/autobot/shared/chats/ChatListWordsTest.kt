package dev.wckdboy.autobot.shared.chats

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class ChatListWordsTest {
    @Test
    fun emptyListsSayWhatIsEmptyInOneLine() {
        assertEquals("No chats match that search.", ChatListWords.empty(searching = true, filter = ChatFilter.Archived))
        assertEquals("No archived chats.", ChatListWords.empty(false, ChatFilter.Archived))
        assertEquals("Nothing pinned.", ChatListWords.empty(false, ChatFilter.Pinned))
        assertEquals("This folder is empty.", ChatListWords.empty(false, ChatFilter.Folder("f")))
        assertEquals("No incognito chats.", ChatListWords.empty(false, ChatFilter.Incognito))
        assertEquals("No chats yet.", ChatListWords.empty(false, ChatFilter.All))
        for (filter in listOf(ChatFilter.All, ChatFilter.Pinned, ChatFilter.Archived, ChatFilter.Folder("f"))) {
            val text = ChatListWords.empty(false, filter)
            assertEquals(text, 1, text.count { it == '.' })
        }
    }

    @Test
    fun theTitleLineOnlyCountsWhatRuns() {
        assertNull(ChatListWords.live(0))
        assertEquals("2 live", ChatListWords.live(2))
        assertEquals("3 selected", ChatListWords.selected(3))
    }
}
