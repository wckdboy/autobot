package dev.wckdboy.autobot.core.data

import dev.wckdboy.autobot.core.data.model.Conversation
import dev.wckdboy.autobot.core.data.model.Message
import dev.wckdboy.autobot.core.data.model.MessageRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IncognitoSessionTest {

    private val session = IncognitoSession()
    private val id = IncognitoSession.ID_PREFIX + "1"
    private val conversation = Conversation(id, "t", null, null, createdAt = 1, updatedAt = 1)

    private fun message(mid: String, at: Long) =
        Message(mid, id, MessageRole.USER, "hi $mid", createdAt = at)

    @Test
    fun onlyPrefixedIdsAreIncognito() {
        assertTrue(session.isIncognito(id))
        assertFalse(session.isIncognito("1234"))
        assertThrows(IllegalArgumentException::class.java) {
            session.create(conversation.copy(id = "not-incognito"))
        }
    }

    @Test
    fun storesMessagesInOrderAndUpserts() = runTest {
        session.create(conversation)
        session.upsertMessage(message("b", 2))
        session.upsertMessage(message("a", 1))
        session.upsertMessage(message("b", 2).copy(content = "edited"))

        val messages = session.observeMessages(id).first()
        assertEquals(listOf("a", "b"), messages.map { it.id })
        assertEquals("edited", messages[1].content)
    }

    @Test
    fun clearForgetsEverything() = runTest {
        session.create(conversation)
        session.upsertMessage(message("a", 1))
        session.clear()
        assertNull(session.observeConversation(id).first())
        assertEquals(emptyList<Conversation>(), session.conversations.value)
    }
}
