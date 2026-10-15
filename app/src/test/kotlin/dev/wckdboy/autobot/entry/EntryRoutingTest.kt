package dev.wckdboy.autobot.entry

import dev.wckdboy.autobot.feature.chat.conversation.ChatHandoff
import dev.wckdboy.autobot.feature.chat.conversation.ChatStart
import dev.wckdboy.autobot.navigation.Chat
import dev.wckdboy.autobot.navigation.Imagine
import dev.wckdboy.autobot.navigation.Models
import dev.wckdboy.autobot.navigation.Run
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EntryRoutingTest {

    @Test
    fun sharedTextOpensANewChatWithADraft() {
        val plan = EntryRouting.plan(EntryRequest.NewChat(draft = "hello", images = listOf("content://a")), canChat = true)
        assertEquals(EntryPlan.NewChat(ChatStart(draft = "hello", images = listOf("content://a"))), plan)
        assertEquals(listOf(Run, Chat("c1")), EntryRouting.backStack(plan, "c1"))
    }

    @Test
    fun voiceOpensANewChatInVoiceMode() {
        val plan = EntryRouting.plan(EntryRequest.Voice, canChat = true)
        assertEquals(EntryPlan.NewChat(ChatStart(voice = true)), plan)
    }

    @Test
    fun imagineOpensTheStudioWithThePrompt() {
        val plan = EntryRouting.plan(EntryRequest.Imagine("a fox"), canChat = false)
        assertEquals(listOf(Run, Imagine(prompt = "a fox")), EntryRouting.backStack(plan, null))
    }

    @Test
    fun withoutAModelChatsGoToModels() {
        assertEquals(EntryPlan.Setup, EntryRouting.plan(EntryRequest.Voice, canChat = false))
        assertEquals(EntryPlan.Setup, EntryRouting.plan(EntryRequest.NewChat(draft = "x"), canChat = false))
        assertEquals(listOf(Models), EntryRouting.backStack(EntryPlan.Setup, null))
    }

    @Test
    fun handoffIsTakenOnce() {
        val handoff = ChatHandoff()
        handoff.put("c1", ChatStart(voice = true))
        assertEquals(ChatStart(voice = true), handoff.take("c1"))
        assertNull(handoff.take("c1"))
        assertNull(handoff.take("c2"))
    }

    @Test
    fun sharedWorkflowOpensTheWorkflowsRoom() {
        val plan = EntryRouting.plan(EntryRequest.ImportWorkflow(json = "{}"), canChat = false)
        assertEquals(EntryPlan.Workflow(dev.wckdboy.autobot.feature.studio.WorkflowShare.Text("{}")), plan)
        assertEquals(listOf(Run, dev.wckdboy.autobot.navigation.Studio(room = "workflows")), EntryRouting.backStack(plan, null))
    }
}
