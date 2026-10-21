package dev.wckdboy.autobot.shared.a2ui

import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.core.network.NetworkMode
import dev.wckdboy.autobot.shared.session.SessionItem
import dev.wckdboy.autobot.shared.session.ToolState
import dev.wckdboy.autobot.shared.session.projectSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BASIC = A2uiCatalog.BASIC_CATALOG_ID

/** Sent forms lock, keep their input across a restart, and unlock when the agent changes them again. */
class A2uiSubmittedTest {

    private val form = """
        [{"version":"v1.0","createSurface":{"surfaceId":"order","catalogId":"$BASIC","components":[
          {"id":"root","component":"Column","children":["name","pin","go","site"]},
          {"id":"name","component":"TextField","label":"Name","value":{"@path":"/name"}},
          {"id":"pin","component":"TextField","label":"PIN","variant":"obscured","value":{"@path":"/pin"}},
          {"id":"go","component":"Button","child":"go_t","action":{"event":{"name":"placeOrder","context":{"name":{"@path":"/name"}}}}},
          {"id":"go_t","component":"Text","text":"Order"},
          {"id":"site","component":"Button","child":"site_t","action":{"functionCall":{"@call":"openUrl","args":{"url":"https://example.com"}}}},
          {"id":"site_t","component":"Text","text":"Site"}],
          "dataModel":{"name":"","pin":""}}}]
    """.trimIndent()

    private val other = """[{"version":"v1.0","createSurface":{"surfaceId":"other","catalogId":"$BASIC","components":[{"id":"root","component":"Text","text":"hi"}]}}]"""

    private fun tool(key: String, stream: String) = SessionItem.Tool(
        key = key, callId = key, name = A2uiChat.TOOL, summary = "", state = ToolState.OK,
        output = "ok", code = null, galleryIds = emptyList(), a2ui = stream,
    )

    private fun A2uiHost.node(surface: String, id: String) = render(surface)!!.find(id)!!

    /** Types into the form and taps Order: the turn the chat would log, as a projected item. */
    private fun send(transcript: A2uiTranscript, name: String, key: String = "u1"): Pair<A2uiActionResult.SendEvent, SessionItem.User> {
        val host = transcript.host
        assertTrue(host.setString(host.node("order", "name"), "value", name))
        assertTrue(host.setString(host.node("order", "pin"), "value", "1234"))
        val event = host.dispatch(host.node("order", "go")) as A2uiActionResult.SendEvent
        return event to project(A2uiChat.actionMessage(event), key)
    }

    private fun project(message: SessionEvent.UserMessage, key: String): SessionItem.User {
        val seq = key.drop(1).toLongOrNull() ?: 1L
        val item = projectSession(listOf(LogEntry(seq, 0L, message)), emptySet(), emptySet()) { _, _ -> "" }.items.single() as SessionItem.User
        return item.copy(key = key)
    }

    @Test
    fun anActionRecordsTheSurfaceAndItsInputWithTheTurn() {
        val transcript = A2uiTranscript()
        transcript.sync(listOf(tool("t1", form)))
        val (event, user) = send(transcript, "Ada")

        // The turn's meta is app data: the model reads only the action text.
        val message = A2uiChat.actionMessage(event)
        val meta = message.meta!![A2uiChat.META]!!.jsonObject
        assertEquals("order", meta["surfaceId"]!!.jsonPrimitive.content)
        assertEquals("placeOrder", meta["action"]!!.jsonPrimitive.content)
        assertFalse(message.text.contains("1234"))

        // The projected user turn carries it; the obscured field is left out of the snapshot.
        val sub = user.a2ui!!
        assertEquals(A2uiSubmission("order", "placeOrder", "Place order", sub.dataModel), sub)
        assertEquals("Place order", user.text)
        val model = Json.parseToJsonElement(sub.dataModel!!).jsonObject
        assertEquals("Ada", model["name"]!!.jsonPrimitive.content)
        assertNull(model["pin"])
    }

    @Test
    fun aSentSurfaceIsReadOnly() {
        val transcript = A2uiTranscript()
        transcript.sync(listOf(tool("t1", form)))
        val (_, user) = send(transcript, "Ada")
        transcript.sync(listOf(tool("t1", form), user))

        val host = transcript.host
        assertTrue(transcript.isSubmitted("order"))
        assertFalse(transcript.isPending("order"))
        assertEquals(setOf("order"), transcript.submittedSurfaces)
        assertEquals("Place order", transcript.submission("order")!!.label)
        assertTrue(host.isReadOnly("order"))
        // Inputs refuse write-back; the send button is refused; a link still opens.
        assertFalse(host.setString(host.node("order", "name"), "value", "Grace"))
        assertEquals("Ada", host.node("order", "name").string("value"))
        assertTrue(host.dispatch(host.node("order", "go")) is A2uiActionResult.Refused)
        assertTrue(host.dispatch(host.node("order", "site")) is A2uiActionResult.OpenUrl)
    }

    @Test
    fun theInputComesBackAfterARestart() {
        val live = A2uiTranscript()
        live.sync(listOf(tool("t1", form)))
        val (_, user) = send(live, "Ada Lovelace")
        val history = listOf(tool("t1", form), user)

        // A new screen (the app restarted): the form is replayed with what the user sent, locked.
        val restarted = A2uiTranscript()
        restarted.sync(history)
        assertEquals("Ada Lovelace", restarted.host.node("order", "name").string("value"))
        assertEquals("", restarted.host.node("order", "pin").string("value").orEmpty())
        assertTrue(restarted.isSubmitted("order"))
        assertTrue(restarted.host.isReadOnly("order"))
    }

    @Test
    fun aLaterUpdateOfTheSameSurfaceUnlocksIt() {
        val transcript = A2uiTranscript()
        transcript.sync(listOf(tool("t1", form)))
        val (_, user) = send(transcript, "Ada")
        val update = """[{"version":"v1.0","updateDataModel":{"surfaceId":"order","path":"/name","value":"Ada L."}}]"""
        transcript.sync(listOf(tool("t1", form), user, tool("t2", update)))

        assertFalse(transcript.isSubmitted("order"))
        assertNull(transcript.submission("order"))
        assertFalse(transcript.host.isReadOnly("order"))
        // The agent's update applies after the restored input, and the form takes input again.
        assertEquals("Ada L.", transcript.host.node("order", "name").string("value"))
        assertTrue(transcript.host.setString(transcript.host.node("order", "name"), "value", "Grace"))

        // Replayed from scratch, the order of events decides the same way.
        val restarted = A2uiTranscript()
        restarted.sync(listOf(tool("t1", form), user, tool("t2", update)))
        assertFalse(restarted.isSubmitted("order"))
        assertEquals("Ada L.", restarted.host.node("order", "name").string("value"))
    }

    @Test
    fun updateComponentsUnlocksTooButAnotherSurfaceDoesNot() {
        val transcript = A2uiTranscript()
        transcript.sync(listOf(tool("t1", form)))
        val (_, user) = send(transcript, "Ada")
        transcript.sync(listOf(tool("t1", form), user, tool("t2", other)))
        assertTrue(transcript.isSubmitted("order"))

        val components = """[{"version":"v1.0","updateComponents":{"surfaceId":"order","components":[{"id":"go_t","component":"Text","text":"Order again"}]}}]"""
        transcript.sync(listOf(tool("t1", form), user, tool("t2", other), tool("t3", components)))
        assertFalse(transcript.isSubmitted("order"))
        assertFalse(transcript.host.isReadOnly("order"))
    }

    @Test
    fun aSecondSendLocksAgain() {
        val transcript = A2uiTranscript()
        transcript.sync(listOf(tool("t1", form)))
        val (_, first) = send(transcript, "Ada")
        val update = """[{"version":"v1.0","updateDataModel":{"surfaceId":"order","path":"/name","value":"Ada?"}}]"""
        transcript.sync(listOf(tool("t1", form), first, tool("t2", update)))
        val (_, second) = send(transcript, "Grace", key = "u3")
        transcript.sync(listOf(tool("t1", form), first, tool("t2", update), second))
        assertTrue(transcript.isSubmitted("order"))

        val restarted = A2uiTranscript()
        restarted.sync(listOf(tool("t1", form), first, tool("t2", update), second))
        assertTrue(restarted.isSubmitted("order"))
        assertEquals("Grace", restarted.host.node("order", "name").string("value"))
    }

    @Test
    fun aDeletedAndRecreatedSurfaceStartsEditable() {
        val transcript = A2uiTranscript()
        transcript.sync(listOf(tool("t1", form)))
        val (_, user) = send(transcript, "Ada")
        val recreate = """[{"version":"v1.0","deleteSurface":{"surfaceId":"order"}}]"""
        transcript.sync(listOf(tool("t1", form), user, tool("t2", recreate), tool("t3", form)))
        assertFalse(transcript.isSubmitted("order"))
        assertEquals(listOf("order"), transcript.surfaces("t3"))
        assertEquals("", transcript.host.node("order", "name").string("value"))
    }

    @Test
    fun aTapLocksAtOnceUntilTheTurnIsLogged() {
        val transcript = A2uiTranscript()
        transcript.sync(listOf(tool("t1", form)))
        val (event, user) = send(transcript, "Ada")

        transcript.markSubmitted(event)
        assertTrue(transcript.isSubmitted("order"))
        assertTrue(transcript.isPending("order"))
        assertTrue(transcript.host.isReadOnly("order"))

        // The logged turn takes over.
        transcript.sync(listOf(tool("t1", form), user))
        assertFalse(transcript.isPending("order"))
        assertTrue(transcript.isSubmitted("order"))
        // Undoing the tap does not unlock a logged send.
        transcript.unmarkSubmitted("order")
        transcript.clearPending()
        assertTrue(transcript.host.isReadOnly("order"))
    }

    @Test
    fun aTapThatWasNotSentUnlocksAgain() {
        val transcript = A2uiTranscript()
        transcript.sync(listOf(tool("t1", form)))
        val (event, _) = send(transcript, "Ada")
        transcript.markSubmitted(event)
        transcript.clearPending()
        assertFalse(transcript.isSubmitted("order"))
        assertFalse(transcript.host.isReadOnly("order"))
        assertEquals("Ada", transcript.host.node("order", "name").string("value"))

        transcript.markSubmitted(event)
        transcript.unmarkSubmitted("order")
        assertFalse(transcript.isSubmitted("order"))
    }

    @Test
    fun aRewrittenHistoryForgetsTheLocks() {
        val transcript = A2uiTranscript()
        transcript.sync(listOf(tool("t1", form)))
        val (_, user) = send(transcript, "Ada")
        transcript.sync(listOf(tool("t1", form), user))
        transcript.sync(listOf(tool("t1", form)))
        assertFalse(transcript.isSubmitted("order"))
        assertFalse(transcript.host.isReadOnly("order"))
        assertEquals("", transcript.host.node("order", "name").string("value"))
    }

    @Test
    fun aTurnFromAGoneSurfaceLocksNothing() {
        val transcript = A2uiTranscript()
        val stray = SessionItem.User("u1", "Place order", a2ui = A2uiSubmission("gone", "x", "Place order", "{}"))
        transcript.sync(listOf(tool("t1", form), stray))
        assertFalse(transcript.isSubmitted("gone"))
        assertFalse(transcript.isSubmitted("order"))
        // Plain turns carry nothing.
        assertNull(A2uiChat.submission(null, "hi"))
    }

    @Test
    fun imagesSayWhereALoadGoes() {
        assertEquals("Tap to load · goes through Tor", A2uiChat.imageHint(NetworkMode.Tor()))
        assertEquals("Tap to load · goes through your connection", A2uiChat.imageHint(NetworkMode.Direct))
        assertEquals("Tap to load · goes through your SOCKS5 proxy", A2uiChat.imageHint(NetworkMode.Socks5("h", 1)))
        assertEquals("Offline · images can't load", A2uiChat.imageHint(NetworkMode.Offline))
    }
}
