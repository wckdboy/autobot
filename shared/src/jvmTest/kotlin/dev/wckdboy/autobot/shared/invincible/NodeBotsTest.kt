package dev.wckdboy.autobot.shared.invincible

import dev.wckdboy.autobot.shared.Cancellable
import dev.wckdboy.autobot.shared.HttpRequestSpec
import dev.wckdboy.autobot.shared.HttpSink
import dev.wckdboy.autobot.shared.HttpTransport
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bots mode on a node: the bots list, switching a bot, sessions and approvals by bot, the 404 fallback. */
class NodeBotsTest {
    /** Answers each `METHOD path` (query included) with its status and body; anything else is 404. */
    private class Node(private val routes: Map<String, Pair<Int, String>>) : HttpTransport {
        val sent = mutableListOf<HttpRequestSpec>()

        override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable {
            sent += request
            val key = "${request.method} ${request.url.removePrefix("http://invincible/v1/agent")}"
            val (status, body) = routes[key] ?: (404 to """{"error":{"message":"not found","type":"not_found"}}""")
            if (status in 200..299) {
                sink.onResponse(status, emptyMap(), null)
                body.lines().forEach(sink::onLine)
                sink.onComplete()
            } else {
                sink.onResponse(status, emptyMap(), body)
            }
            return Cancellable {}
        }
    }

    private val list = """{"bots":[
        {"name":"support","persona":"Answers customer email.\nSecond line of SOUL.md","model":"claude-sonnet","approvals":"edits","enabled":true,"status":"running",
         "channels":[{"kind":"email","account":"help@studio.example","status":"connected"}],"sessions":31,"last_active":1000,"queue":0,"future":{"x":1}},
        {"name":"ops","persona":"Keeps the servers up.","model":"qwen3:8b","approvals":"allowlist","enabled":true,"status":"running",
         "channels":[{"kind":"telegram","account":"@ops_bot","status":"connected"},{"kind":"slack","account":"#ops","status":"down","error":"token revoked"},{"kind":"telegram","account":"@ops2","status":"connecting"}],
         "sessions":14,"last_active":1500,"queue":2},
        {"name":"default","model":"qwen3.5-4b"},
        {"persona":"no name: left out"},
        "not an object"
    ]}"""

    @Test
    fun parsesTheListToleratingWhatItDoesNotKnow() {
        val bots = NodeBots.parse(list)
        assertEquals(listOf("support", "ops", "default"), bots.map { it.name })
        val support = bots[0]
        // The persona is the first line only.
        assertEquals("Answers customer email.", support.persona)
        assertEquals("edits", support.approvals)
        assertEquals(listOf(BotChannel("email", "help@studio.example", "connected")), support.channels)
        assertEquals(31, support.sessions)
        assertEquals(1000L, support.lastActive)
        // Missing fields have their defaults: enabled, running, asks, never active.
        val default = bots[2]
        assertEquals(NodeBot("default", model = "qwen3.5-4b"), default)
        assertTrue(default.enabled)
        assertNull(default.lastActive)
        assertTrue(NodeBots.parse("not json").isEmpty())
        assertTrue(NodeBots.parse("""{"bots":null}""").isEmpty())
    }

    @Test
    fun wordsForARow() {
        val now = 1_800L
        val ops = NodeBots.parse(list)[1]
        assertEquals(listOf("telegram", "slack"), NodeBots.kinds(ops))
        assertEquals("slack down: token revoked ✗", NodeBots.problem(ops))
        assertEquals("2 queued · active 5m ago", NodeBots.meta(ops, now))
        assertEquals("running", NodeBots.status(ops))
        val failed = ops.copy(status = "error", error = "model not found", channels = emptyList())
        assertEquals("error: model not found ✗", NodeBots.problem(failed))
        assertNull(NodeBots.problem(NodeBot("default")))
        assertEquals("not active yet", NodeBots.meta(NodeBot("default"), now))
        assertEquals("off", NodeBots.status(ops.copy(enabled = false)))
        assertEquals("down: token revoked ✗", NodeBots.channelState(ops.channels[1]))
        assertEquals("connecting", NodeBots.channelState(ops.channels[2]))
        assertEquals("ops, running, Keeps the servers up., on telegram and slack, slack down: token revoked, 2 queued · active 5m ago.", NodeBots.spoken(ops, now))
        assertEquals("3h ago", NodeBots.ago(now - 3 * 3_600 - 5, now))
        assertEquals("just now", NodeBots.ago(now + 10, now))
    }

    @Test
    fun theRepositoryListsTheDefaultBotFirst() = runBlocking {
        val node = Node(mapOf("GET /bots" to (200 to list)))
        val repo = NodeBotsRepository(NodeAgent(node))
        val state = repo.refresh()
        assertEquals(listOf("default", "ops", "support"), state.bots.map { it.name })
        assertEquals(true, state.supported)
        assertTrue(state.showList)
        assertTrue(state.several)
        assertFalse(state.loading)
        assertEquals(state, repo.state.value)
        assertEquals("http://invincible/v1/agent/bots", node.sent.single().url)
    }

    @Test
    fun anOlderNodeWithoutBotsKeepsTodaysScreens() = runBlocking {
        val repo = NodeBotsRepository(NodeAgent(Node(emptyMap())))
        val state = repo.refresh()
        assertEquals(false, state.supported)
        assertFalse(state.showList)
        assertFalse(state.several)
        assertNull(state.problem)
        assertNull(NodeAgent(Node(emptyMap())).bots())
    }

    @Test
    fun oneBotIsASingleRowNotSeveral() = runBlocking {
        val state = NodeBotsRepository(NodeAgent(Node(mapOf("GET /bots" to (200 to """{"bots":[{"name":"default"}]}"""))))).refresh()
        assertTrue(state.showList)
        assertFalse(state.several)
    }

    @Test
    fun aNodeThatFailsIsAProblemNotAnOlderNode() = runBlocking {
        val repo = NodeBotsRepository(NodeAgent(Node(mapOf("GET /bots" to (503 to """{"error":{"message":"x","type":"agent_unavailable"}}""")))))
        val state = repo.refresh()
        assertNull(state.supported)
        assertTrue(state.problem!!.contains("autobot serve"))
    }

    @Test
    fun switchingABotOffShowsAtOnceAndPostsIt() = runBlocking {
        val node = Node(mapOf("GET /bots" to (200 to list), "POST /bots/ops/disable" to (200 to """{"ok":true}""")))
        val repo = NodeBotsRepository(NodeAgent(node))
        repo.refresh()
        assertNull(repo.setEnabled("ops", false))
        val ops = repo.state.value.bot("ops")!!
        assertFalse(ops.enabled)
        assertEquals("stopped", ops.status)
        assertEquals("POST", node.sent.last().method)
        assertEquals("http://invincible/v1/agent/bots/ops/disable", node.sent.last().url)
    }

    @Test
    fun aSwitchTheNodeRefusesIsPutBack() = runBlocking {
        val node = Node(mapOf("GET /bots" to (200 to list), "POST /bots/ops/disable" to (500 to """{"error":{"message":"bot is busy","type":"internal"}}""")))
        val repo = NodeBotsRepository(NodeAgent(node))
        repo.refresh()
        assertEquals("bot is busy", repo.setEnabled("ops", false))
        assertTrue(repo.state.value.bot("ops")!!.enabled)
        assertEquals("bot is busy", repo.state.value.problem)
        assertTrue(repo.setEnabled("nobody", true)!!.contains("nobody"))
    }

    @Test
    fun enablingPostsToEnable() = runBlocking {
        val node = Node(mapOf("POST /bots/support%20desk/enable" to (200 to """{"ok":true}""")))
        NodeAgent(node).setBotEnabled("support desk", true)
        assertEquals("http://invincible/v1/agent/bots/support%20desk/enable", node.sent.single().url)
    }

    @Test
    fun sessionsApprovalsAndActionsSayWhichBot() = runBlocking {
        val node = Node(
            mapOf(
                "GET /sessions?bot=ops" to (200 to """{"sessions":[{"id":"tg-1","modified":5,"bot":"ops"}]}"""),
                "GET /sessions" to (200 to """{"sessions":[{"id":"app-x","modified":4},{"id":"tg-1","modified":5,"bot":"ops"}]}"""),
                "GET /approvals" to (200 to """{"approvals":[{"id":"a1","session":"tg-1","text":"run: ls","action":{"kind":"command","command":"ls"},"asked_at":3,"bot":"ops"}]}"""),
                "GET /actions?limit=100" to (200 to """{"actions":[{"at":1,"session":"tg-1","kind":"command","action":"run: ls","ran":true,"by":"policy","bot":"ops"}]}"""),
                "GET " to (200 to """{"name":"Autobot","model":"auto","workdir":"/w","approvals":"ask","private":true,"bots":["default","ops"]}"""),
            ),
        )
        val agent = NodeAgent(node)
        assertEquals(listOf(AgentSession("tg-1", 5, "ops")), agent.sessions("ops"))
        assertEquals(listOf(AgentSession("app-x", 4, null), AgentSession("tg-1", 5, "ops")), agent.sessions())
        assertEquals("ops", agent.approvals()!!.single().bot)
        assertEquals("ops", agent.actions().single().bot)
        assertEquals(listOf("default", "ops"), agent.info().bots)
        assertEquals("default", NodeBots.of(null))
    }

    @Test
    fun aTurnForANewSessionNamesItsBot() = runBlocking {
        val node = Node(mapOf("POST /sessions/s1/turns" to (200 to """data: {"type":"answer","text":"hi","receipts":[]}""")))
        NodeAgent(node).turn("s1", "hello", bot = "ops").toList()
        assertEquals("""{"text":"hello","bot":"ops"}""", node.sent.single().body)
        NodeAgent(node).turn("s1", "hello").toList()
        assertEquals("""{"text":"hello"}""", node.sent.last().body)
    }

    @Test
    fun aNewSessionIsThisDevicesAndTheBots() {
        val id = NodeBots.newSession("My Phone", "ops/2", now = 1_700_000_000)
        assertTrue(id, id.startsWith("app-MyPhone-ops2-"))
        assertTrue(id, id.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun theSampleHasThreeBotsOneWithAChannelDown() {
        val bots = NodeBots.ordered(NodeBots.sample(10_000))
        assertEquals(listOf("default", "ops", "support"), bots.map { it.name })
        assertEquals("slack down: token revoked ✗", NodeBots.problem(bots[1]))
        assertEquals(listOf("email"), NodeBots.kinds(bots[2]))
    }

    // The digest, per bot.

    private fun action(bot: String?, ran: Boolean = true, at: Long = 200) = AgentActionEntry(at, "s", "command", "run: ls", ran, "policy", bot)

    @Test
    fun theDigestGroupsLinesPerBotWhenThereAreSeveral() {
        val actions = listOf(action(null), action("ops"), action("ops", ran = false), action("support"), action("ops", at = 50))
        val base = NodeDigests.compute(NodeActivity(emptyList(), actions, emptyList(), emptyList()), emptyList(), since = 100)
            .copy(approvals = listOf(PendingApproval("a1", "tg-1", "run: x", AgentAction.Command("x"), askedAt = 150, bot = "ops")))
        val d = NodeDigests.byBot(base, listOf("support", "default", "ops"), actions)
        assertEquals(listOf("default", "ops", "support"), d.bots.map { it.name })
        assertEquals(NodeDigestBot("ops", actions = 2, refused = 1, approvals = 1), d.bots[1])
        assertEquals(listOf("default: 1 action", "ops: 2 actions (1 refused), 1 waiting", "support: 1 action"), NodeDigestWords.bots(d))
        val lines = NodeDigestWords.lines(d).map { it.text }
        // The per-bot lines take the place of the one actions line.
        assertTrue(lines.toString(), lines.none { it.startsWith("4 actions") })
        assertTrue(lines.contains("ops: 2 actions (1 refused), 1 waiting"))
    }

    @Test
    fun oneBotKeepsTheCardAsItWas() {
        val actions = listOf(action(null), action(null))
        val base = NodeDigests.compute(NodeActivity(emptyList(), actions, emptyList(), emptyList()), emptyList(), since = 100)
        val d = NodeDigests.byBot(base, listOf("default"), actions)
        assertTrue(d.bots.isEmpty())
        assertTrue(NodeDigestWords.bots(d).isEmpty())
        assertTrue(NodeDigestWords.lines(d).any { it.text == "2 actions, all allowed by the rules" })
    }

    @Test
    fun manyBusyBotsAreShortened() {
        val names = listOf("a", "b", "c", "d", "e")
        val actions = names.map { action(it) }
        val d = NodeDigests.byBot(NodeDigest(since = 100), names, actions)
        assertEquals(listOf("a: 1 action", "b: 1 action", "c: 1 action", "and 2 more bots"), NodeDigestWords.bots(d))
    }

    @Test
    fun theDigestAsksTheNodeForItsBots() = runBlocking {
        val node = Node(
            mapOf(
                "GET /actions?limit=500&since=100" to (200 to """{"actions":[{"at":150,"session":"tg","kind":"command","action":"run: ls","ran":true,"by":"policy","bot":"ops"}]}"""),
                "GET /approvals" to (200 to """{"approvals":[]}"""),
                "GET /bots" to (200 to """{"bots":[{"name":"default"},{"name":"ops"}]}"""),
            ),
        )
        // The audit log is not under /v1/agent: answer it too.
        val transport = object : HttpTransport {
            override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable =
                if (request.url.startsWith("http://invincible/v1/audit")) {
                    sink.onResponse(200, emptyMap(), null)
                    sink.onLine("""{"entries":[]}""")
                    sink.onComplete()
                    Cancellable {}
                } else {
                    node.send(request, sink)
                }
        }
        val d = NodeDigests.fetch(NodeAgent(transport), JvmNodeCrypto, "studio", null, "node-1", since = 100, recipes = emptyList())
        assertEquals(listOf("default", "ops"), d.bots.map { it.name })
        assertEquals(listOf("ops: 1 action"), NodeDigestWords.bots(d))
    }

    @Test
    fun theSampleDigestWithBotsSaysWhichBot() {
        val d = NodeDigests.sampleWithBots(100_000)
        assertEquals(listOf("default: 1 action", "ops: 2 actions, 2 waiting", "support: 1 action"), NodeDigestWords.bots(d))
    }
}
