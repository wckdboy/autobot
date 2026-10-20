package dev.wckdboy.autobot.shared.invincible

import dev.wckdboy.autobot.shared.Cancellable
import dev.wckdboy.autobot.shared.HttpRequestSpec
import dev.wckdboy.autobot.shared.HttpSink
import dev.wckdboy.autobot.shared.HttpTransport
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invincible 0.0.14's agent API for a bot's page and its chats: personalities, the profile, usage
 * and insights, switching and reloading bots with the node's answer, photos in turns, `kept`
 * events after the answer, and retry.
 */
class NodeBotDetailsTest {
    /** Answers each `METHOD path` (query included) with its status and body (one line each); anything else is 404. */
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

    private val personalities = """{"active":"concise","soul":"You keep the servers up.\n","bot":"ops","personalities":[
        {"name":"concise","description":"Short answers. ","builtin":true},
        {"name":"reviewer","description":"Names the risks.","builtin":false},
        {"description":"no name: left out"}]}"""

    private val insights = """{"days":7,"turns":46,"seconds":1912.4,"calls":131,"tokens":{"input":402000,"output":38400,"cached":120000},
        "cost":1.2449,"unpriced":["deepseek-chat"],"local_share":{"calls":0.75,"tokens":0.6},
        "tools":[{"name":"terminal","count":58},{"name":"read_file","count":29},{"name":"remember","count":0}],
        "projects":[{"name":"infra","count":12}],
        "models":[{"model":"qwen3:8b","calls":98,"tokens":300000,"cloud":false},{"model":"claude-sonnet","calls":33,"tokens":140400,"cloud":true}],
        "failures":{"errors":1,"stopped":0,"tool_errors":3,"refused":2},"bot":"ops"}"""

    @Test
    fun personalitiesParseAndSet() = runBlocking {
        val p = NodeBotDetails.parsePersonalities(personalities)
        assertEquals("concise", p.active)
        assertEquals("You keep the servers up.\n", p.soul)
        assertEquals(listOf(NodePersonality("concise", "Short answers."), NodePersonality("reviewer", "Names the risks.", builtin = false)), p.list)
        assertEquals("concise", NodeBotDetails.personality(p))
        assertEquals("Short answers.", NodeBotDetails.personalityLine(p))
        // None in use: "none", and no line.
        val none = NodeBotDetails.parsePersonalities("""{"active":null,"soul":null,"personalities":[]}""")
        assertNull(none.active)
        assertEquals("none", NodeBotDetails.personality(none))
        assertNull(NodeBotDetails.personalityLine(none))
        assertNull(NodeBotDetails.parsePersonalities("""{"active":"none"}""").active)

        val node = Node(
            mapOf(
                "GET /personalities?bot=ops" to (200 to personalities),
                "POST /personality?bot=ops" to (200 to """{"active":"reviewer"}"""),
                "POST /personality" to (200 to """{"active":null}"""),
            ),
        )
        val agent = NodeAgent(node)
        assertEquals("concise", agent.personalities("ops").active)
        assertEquals("reviewer", agent.setPersonality("reviewer", "ops"))
        assertEquals("""{"name":"reviewer"}""", node.sent.last().body)
        assertEquals("application/json", node.sent.last().headers["Content-Type"])
        // None: the node is sent "none".
        assertNull(agent.setPersonality(null))
        assertEquals("""{"name":"none"}""", node.sent.last().body)
    }

    @Test
    fun anUnknownPersonalityIsTheNodesWords() = runBlocking {
        val node = Node(mapOf("POST /personality?bot=ops" to (400 to """{"error":{"message":"no personality called pirate","type":"invalid_request_error"}}""")))
        val e = runCatching { NodeAgent(node).setPersonality("pirate", "ops") }.exceptionOrNull() as NodeAgentException
        assertEquals(400, e.status)
        assertEquals("no personality called pirate", e.message)
    }

    @Test
    fun theProfileIsItsEntries() {
        val p = NodeBotDetails.parseProfile("""{"entries":["Prefers short answers."," Works in Kotlin. ",""],"text":"# How you work\n- Prefers short answers.\n- Works in Kotlin.\n"}""")
        assertEquals(listOf("Prefers short answers.", "Works in Kotlin."), p.entries)
        // Only the text: its `- ` lines.
        assertEquals(listOf("a", "b"), NodeBotDetails.parseProfile("""{"text":"# x\n- a\n-  b\nnot an entry"}""").entries)
        assertTrue(NodeBotDetails.parseProfile("nope").entries.isEmpty())
        assertEquals("~/.autobot/USER.md", NodeBotDetails.profilePath(null))
        assertEquals("~/.autobot/USER.md", NodeBotDetails.profilePath("default"))
        assertEquals("\$EDITOR ~/.autobot/bots/ops/USER.md", NodeBotDetails.profileEditCommand("ops"))
    }

    @Test
    fun usageAndInsightsParse() = runBlocking {
        val node = Node(
            mapOf(
                "GET /usage?bot=ops" to (200 to """{"today":{"turns":12,"calls":25,"cloud_calls":18,"input":88000,"output":12500,"cached":0,"cloud_tokens":80000,"seconds":61.5,"cost":0.2149,"unpriced":[]},"bot":"ops"}"""),
                "GET /usage?session=tg-1&bot=ops" to (200 to """{"today":{"turns":1},"session":{"turns":4,"calls":9},"bot":"ops"}"""),
                "GET /insights?days=7&bot=ops" to (200 to insights),
            ),
        )
        val agent = NodeAgent(node)
        val usage = agent.usage("ops")
        assertEquals(UsageTotals(12, 25, 18, 88_000, 12_500, 0, 80_000, 61.5, 0.2149), usage.today)
        assertEquals("12 turns · 25 model calls · 100.5k tokens · $0.21", NodeBotDetails.today(usage.today))
        assertEquals("No turns today.", NodeBotDetails.today(UsageTotals()))
        assertEquals(4, agent.usage("ops", session = "tg-1").session!!.turns)

        val i = agent.insights("ops")
        assertEquals(46, i.turns)
        assertEquals(440_400L, i.tokens)
        assertEquals(0.75, i.localCalls, 0.0)
        assertEquals(listOf(ToolCount("terminal", 58), ToolCount("read_file", 29), ToolCount("remember", 0)), i.tools)
        assertEquals(InsightFailures(1, 0, 3, 2), i.failures)
        assertEquals("46 turns · 131 model calls · 440.4k tokens · 31m 52s", NodeBotDetails.summary(i))
        assertEquals("local 75 % · cloud 25 %", NodeBotDetails.share(i))
        assertEquals("$1.24 for cloud calls · no price for deepseek-chat", NodeBotDetails.costLine(i))
        assertEquals("1 failed · 3 tool errors · 2 refused", NodeBotDetails.failures(i.failures))
        // Bars against the busiest; a tool that never ran is left out.
        assertEquals(listOf(InsightBar("terminal", "58", 1.0), InsightBar("read_file", "29", 0.5)), NodeBotDetails.toolBars(i))
        assertEquals(listOf("98 · local", "33 · cloud"), NodeBotDetails.modelBars(i).map { it.value })
        // All local: no cost to speak of.
        val local = NodeBotDetails.parseInsights("""{"days":7,"turns":2,"calls":3,"tokens":900,"local_share":{"calls":1.0,"tokens":1.0},"models":[{"model":"q","calls":3,"tokens":900,"cloud":false}]}""")
        assertEquals(900L, local.tokens)
        assertEquals("all local", NodeBotDetails.share(local))
        assertEquals("$0", NodeBotDetails.costLine(local))
        assertNull(NodeBotDetails.failures(local.failures))
        assertEquals("No turns in the last 7 days.", NodeBotDetails.summary(NodeInsights()))
    }

    @Test
    fun words() {
        assertEquals("950", NodeBotDetails.tokens(950))
        assertEquals("1.2k", NodeBotDetails.tokens(1_234))
        assertEquals("3.40M", NodeBotDetails.tokens(3_400_000))
        assertNull(NodeBotDetails.cost(0.0))
        assertEquals("<$0.01", NodeBotDetails.cost(0.004))
        assertEquals("$12.30", NodeBotDetails.cost(12.3))
        assertEquals("9.4s", NodeBotDetails.duration(9.4))
        assertEquals("2h 4m", NodeBotDetails.duration(7_440.0))
        assertEquals("kept · profile: prefers short answers", NodeBotDetails.kept(" profile: prefers short answers "))
    }

    @Test
    fun theDetailAsksForEachPartAndAnOlderNodeHasNone() = runBlocking {
        val node = Node(
            mapOf(
                "GET /personalities?bot=ops" to (200 to personalities),
                "GET /profile?bot=ops" to (200 to """{"entries":["Wants restarts confirmed."],"text":"- Wants restarts confirmed."}"""),
                "GET /usage?bot=ops" to (200 to """{"today":{"turns":1}}"""),
                "GET /insights?days=7&bot=ops" to (200 to insights),
            ),
        )
        val detail = NodeBotDetails.fetch(NodeAgent(node), "ops")
        assertEquals("concise", detail.personalities!!.active)
        assertEquals(listOf("Wants restarts confirmed."), detail.profile!!.entries)
        assertEquals(1, detail.usage!!.today.turns)
        assertEquals(46, detail.insights!!.turns)
        assertNull(detail.problem)
        // A node from before 0.0.14: every part 404, nothing to show, nothing wrong.
        val old = NodeBotDetails.fetch(NodeAgent(Node(emptyMap())), "ops")
        assertEquals(NodeBotDetail(), old)
        // A node that fails: why.
        val broken = NodeBotDetails.fetch(NodeAgent(Node(mapOf("GET /profile?bot=ops" to (500 to """{"error":{"message":"disk full","type":"internal_error"}}""")))), "ops")
        assertEquals("disk full", broken.problem)
    }

    @Test
    fun aSwitchSaysWhatTheNodeDid() = runBlocking {
        val list = """{"bots":[{"name":"ops","status":"stopped","enabled":false},{"name":"support","status":"running"}]}"""
        val node = Node(
            mapOf(
                "GET /bots" to (200 to list),
                "POST /bots/ops/enable" to (200 to """{"ok":true,"enabled":true,"restart":false,"status":"error","error":"model not found"}"""),
                "POST /bots/support/disable" to (200 to """{"ok":true,"enabled":false,"restart":false,"status":"stopped"}"""),
            ),
        )
        val repo = NodeBotsRepository(NodeAgent(node))
        repo.refresh()
        assertNull(repo.setEnabled("ops", true))
        val ops = repo.state.value.bot("ops")!!
        assertTrue(ops.enabled)
        assertEquals("error", ops.status)
        assertEquals("model not found", ops.error)
        assertEquals("ops could not start: model not found", repo.state.value.notice)
        assertEquals("{}", node.sent.last { it.method == "POST" }.body)
        repo.setEnabled("support", false)
        assertEquals("support is stopped.", repo.state.value.notice)
        assertEquals("stopped", repo.state.value.bot("support")!!.status)
        // An agent that cannot switch while it runs.
        assertEquals("Saved. The agent on the node applies it when it restarts.", NodeBotDetails.switched("ops", NodeBotDetails.parseSwitch("""{"ok":true,"enabled":true,"restart":true}""", true)))
        assertEquals("ops is running.", NodeBotDetails.switched("ops", BotSwitch(true, status = "running")))
    }

    @Test
    fun reloadSaysWhatChanged() = runBlocking {
        val node = Node(
            mapOf(
                "GET /bots" to (200 to """{"bots":[{"name":"ops"}]}"""),
                "POST /bots/reload" to (200 to """{"started":["ops"],"stopped":[],"restarted":["support"],"persona":["default"],"failed":[{"name":"mail","error":"no password"}],"running":["ops","support"]}"""),
            ),
        )
        val state = NodeBotsRepository(NodeAgent(node)).reload()
        assertEquals("Started ops · restarted support · new persona for default · mail failed: no password ✗", state.notice)
        assertEquals(listOf("ops"), state.bots.map { it.name })
        assertEquals("Nothing to change.", NodeBotDetails.reloaded(BotsReload()))
        val unconfigured = Node(mapOf("GET /bots" to (200 to """{"bots":[]}"""), "POST /bots/reload" to (409 to """{"error":{"message":"x","type":"not_configurable"}}""")))
        assertEquals("This node has no bot configuration to reload.", NodeBotsRepository(NodeAgent(unconfigured)).reload().notice)
    }

    @Test
    fun aTurnSendsItsPhotosAsImageParts() = runBlocking {
        val node = Node(mapOf("POST /sessions/s1/turns" to (200 to """data: {"type":"answer","text":"A cat.","receipts":[]}""")))
        NodeAgent(node).turn("s1", "What is this?", "ops", listOf("QUJD")).toList()
        val body = Json.parseToJsonElement(node.sent.single().body!!).jsonObject
        assertEquals("What is this?", body["text"]!!.jsonPrimitive.content)
        assertEquals("ops", body["bot"]!!.jsonPrimitive.content)
        val part = body["images"]!!.jsonArray.single().jsonObject
        assertEquals("image_url", part["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,QUJD", part["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        // Without photos the body is as before.
        assertEquals("""{"text":"hi"}""", NodeImages.turnBody("hi", null, emptyList()))
    }

    @Test
    fun aModelThatCannotSeeSaysSoPlainly() = runBlocking {
        val node = Node(mapOf("POST /sessions/s1/turns" to (400 to """{"error":{"message":"deepseek-chat cannot see images","type":"invalid_request_error","code":"model_cannot_see_images"}}""")))
        val failed = NodeAgent(node).turn("s1", "What is this?", images = listOf("QUJD")).toList().single() as AgentEvent.Failed
        assertEquals(NodeImages.CANNOT_SEE, failed.code)
        assertEquals(NodeImages.CANNOT_SEE_MESSAGE, failed.text)
    }

    @Test
    fun photosFitTheLongestSide() {
        assertEquals(1568 to 1176, NodeImages.fit(4032, 3024))
        assertEquals(882 to 1568, NodeImages.fit(2268, 4032))
        assertEquals(800 to 600, NodeImages.fit(800, 600))
        assertEquals("1 photo", NodeImages.label(1))
        assertEquals("3 photos", NodeImages.label(3))
    }

    @Test
    fun keptEventsComeAfterTheAnswer() = runBlocking {
        val stream = listOf(
            """data: {"type":"answer","text":"Done.","receipts":[]}""", "",
            """data: {"type":"kept","text":"profile: prefers short answers"}""", "",
            """data: {"type":"kept","text":"  "}""", "",
            """data: {"type":"kept","text":"memory: the NAS is at 10.0.0.4"}""", "",
        ).joinToString("\n")
        val events = NodeAgent(Node(mapOf("POST /sessions/s1/turns" to (200 to stream)))).turn("s1", "x").toList()
        assertEquals(
            listOf(AgentEvent.Answer("Done.", emptyList()), AgentEvent.Kept("profile: prefers short answers"), AgentEvent.Kept("memory: the NAS is at 10.0.0.4")),
            events,
        )
    }

    @Test
    fun retryPostsToRetryAndSaysWhenThereIsNothingToRetry() = runBlocking {
        val node = Node(mapOf("POST /sessions/s1/retry" to (200 to """data: {"type":"answer","text":"Again.","receipts":["r2"]}""")))
        val events = NodeAgent(node).retry("s1").toList()
        assertEquals(listOf(AgentEvent.Answer("Again.", listOf("r2"))), events)
        assertEquals("{}", node.sent.single().body)
        assertEquals("text/event-stream", node.sent.single().headers["Accept"])
        val none = NodeAgent(Node(mapOf("POST /sessions/s2/retry" to (404 to """{"error":{"message":"no turn to retry","type":"not_found"}}""")))).retry("s2").toList().single() as AgentEvent.Failed
        assertEquals("no turn to retry", none.text)
        assertEquals("not_found", none.code)
    }

    @Test
    fun historyNamesAMessagesImages() = runBlocking {
        val node = Node(
            mapOf(
                "GET /sessions/s1" to (200 to """{"id":"s1","messages":[{"role":"user","content":"What is this?","autobot_images":[{"path":"/x/a.jpg","name":"photo.jpg","sha256":"0","bytes":1,"mime":"image/jpeg","width":1568,"height":1176}]},{"role":"assistant","content":"A cat."}]}"""),
            ),
        )
        val history = NodeAgent(node).history("s1")
        assertEquals(listOf(AgentLine("user", "What is this?", listOf("photo.jpg")), AgentLine("assistant", "A cat.")), history)
        assertFalse(history[1].images.isNotEmpty())
    }

    @Test
    fun theSampleCoversEveryBot() {
        for (bot in listOf("default", "ops", "support")) {
            val d = NodeBotDetails.sample(bot)
            assertTrue(bot, d.profile!!.entries.isNotEmpty())
            assertTrue(bot, d.insights!!.turns > 0)
            assertTrue(bot, NodeBotDetails.toolBars(d.insights!!).isNotEmpty())
        }
        assertEquals("$1.24 for cloud calls", NodeBotDetails.costLine(NodeBotDetails.sample("support").insights!!))
    }
}
