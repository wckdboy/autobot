package dev.wckdboy.autobot.providers.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatRoutesTest {
    private val providers = listOf(RouteTarget("a", "model-a"), RouteTarget("b", "model-b"))

    @Test
    fun existingProviderKeepsItsRouteAndModel() {
        assertNull(ChatRoutes.repoint("b", "custom-model", providers))
        assertNull(ChatRoutes.repoint("b", null, providers))
    }

    @Test
    fun deletedProviderMovesToTheFirstWithItsDefaultModel() {
        assertEquals(ChatRoute("a", "model-a"), ChatRoutes.repoint("gone", "gone-model", providers))
    }

    @Test
    fun conversationWithoutProviderGetsTheFirst() {
        assertEquals(ChatRoute("a", "model-a"), ChatRoutes.repoint(null, null, providers))
        assertEquals(ChatRoute("a", "model-a"), ChatRoutes.repoint("", "", providers))
    }

    @Test
    fun noProvidersLeavesTheConversationAlone() {
        assertNull(ChatRoutes.repoint("gone", "m", emptyList()))
        assertNull(ChatRoutes.repoint(null, null, emptyList()))
    }
}
