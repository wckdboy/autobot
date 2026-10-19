package dev.wckdboy.autobot.shared.invincible

import dev.wckdboy.autobot.shared.Cancellable
import dev.wckdboy.autobot.shared.HttpRequestSpec
import dev.wckdboy.autobot.shared.HttpSink
import dev.wckdboy.autobot.shared.HttpTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the node and its agent did (Invincible's spec/audit.md), read the way the app shows it. */
class NodeActivityTest {
    private val signed = Json.parseToJsonElement(javaClass.getResource("/invincible/receipts.json")!!.readText()).jsonObject["cases"]!!.jsonArray
        .map { it.jsonObject }
        .filter { it["input"]!!.jsonObject["kind"]!!.jsonPrimitive.content == "sign" }

    private fun case(name: String) = signed.first { it["name"]!!.jsonPrimitive.content.startsWith(name) }
    private fun key(case: JsonObject) = case["expect"]!!.jsonObject["public_key"]!!.jsonPrimitive.content
    private fun envelope(case: JsonObject) =
        """{"receipt":${case["input"]!!.jsonObject["receipt"]},"signature":"${case["expect"]!!.jsonObject["signature"]!!.jsonPrimitive.content}"}"""

    /** Answers each path with its body, or [status] with an error. */
    private class Node(private val bodies: Map<String, String>, private val agentStatus: Int = 200) : HttpTransport {
        val asked = mutableListOf<String>()

        override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable {
            asked += "${request.method} ${request.url}"
            val path = request.url.removePrefix("http://invincible").substringBefore('?')
            if (path.startsWith("/v1/agent") && agentStatus != 200) {
                sink.onResponse(agentStatus, emptyMap(), """{"error":{"message":"x","type":"agent_unavailable"}}""")
                return Cancellable {}
            }
            val body = bodies[path]
            if (body == null) {
                sink.onResponse(404, emptyMap(), """{"error":{"message":"no such fact","type":"not_found"}}""")
            } else {
                sink.onResponse(200, emptyMap(), null)
                sink.onLine(body)
                sink.onComplete()
            }
            return Cancellable {}
        }
    }

    private fun audit(local: JsonObject, cloud: JsonObject) = """{"entries":[
        {"at":100,"caller":"local","client":"autobot-agent","api":"chat","prev":"00","receipt":${envelope(local)}},
        {"at":200,"caller":"device:My-Phone","api":"chat","prev":"ab","receipt":${envelope(cloud)}}
    ],"summary":{},"kept":true}"""

    @Test
    fun readsRequestsActionsMemoryAndSkills() = runBlocking {
        val local = case("a local run")
        val cloud = case("a cloud run")
        val node = Node(
            mapOf(
                "/v1/audit" to audit(local, cloud),
                "/v1/agent/actions" to """{"actions":[
                    {"at":90,"session":"cron-t1","kind":"command","action":"run: rm -rf /","ran":false,"by":"unattended"},
                    {"at":95,"session":"app-phone","kind":"command","action":"run: ls","ran":true,"by":"app"}]}""",
                "/v1/agent/memory" to """{"facts":[{"id":1,"at":10,"text":"Backups run at 02:00."},{"id":2,"at":20,"text":"Main project: Nightfall."}]}""",
                "/v1/agent/skills" to """{"skills":[{"name":"deploy","description":"Deploy the site"}]}""",
            ),
        )
        // Both vectors are signed by the same seed, so one pinned key verifies both.
        assertEquals(key(local), key(cloud))
        val activity = NodeAgent(node).activity(JvmNodeCrypto, "studio", key(local))
        assertEquals(listOf(200L, 100L), activity.requests.map { it.at })
        val (fromPhone, fromAgent) = activity.requests
        assertEquals("My-Phone", fromPhone.device)
        assertNull(fromAgent.device)
        assertEquals("autobot-agent", fromAgent.client)
        assertEquals("run", fromAgent.action)
        assertTrue(activity.requests.all { it.receipt.verified })
        assertEquals(0, activity.unverified)
        assertEquals(1, activity.leftTheNode)
        assertEquals(listOf("app", "unattended"), activity.actions.map { it.by })
        assertFalse(activity.actions[1].ran)
        assertEquals(listOf(2L, 1L), activity.facts.map { it.id })
        assertEquals(listOf(AgentSkill("deploy", "Deploy the site")), activity.skills)
        assertNull(activity.agentProblem)
        assertTrue(node.asked.contains("GET http://invincible/v1/audit?limit=100"))

        // Signed by another key: shown, never as verified.
        val other = NodeAgent(node).requests(JvmNodeCrypto, "studio", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        assertEquals(2, other.count { !it.receipt.verified })
    }

    @Test
    fun withoutTheAgentTheNodesRequestsStillShow() = runBlocking {
        val local = case("a local run")
        val node = Node(mapOf("/v1/audit" to audit(local, case("a cloud run"))), agentStatus = 503)
        val activity = NodeAgent(node).activity(JvmNodeCrypto, "studio", key(local))
        assertEquals(2, activity.requests.size)
        assertTrue(activity.actions.isEmpty())
        assertTrue(activity.agentProblem!!, activity.agentProblem!!.contains("autobot serve"))
    }

    @Test
    fun theBoardShowsPlansAndTakesAnswers() = runBlocking {
        val board = """{"tasks":[
            {"id":"b1","plan":"p1","agent":"researcher","goal":"Find it","status":"done","depends_on":[],"result":"Found","updated":1},
            {"id":"b2","plan":"p1","agent":"writer","goal":"Write it","status":"blocked","depends_on":["b1"],"question":"Markdown?","updated":2},
            {"id":"b3","plan":"p2","agent":"writer","goal":"Later","status":"todo","depends_on":[],"updated":3}]}"""
        val node = Node(mapOf("/v1/audit" to """{"entries":[]}""", "/v1/agent/actions" to """{"actions":[]}""",
            "/v1/agent/memory" to """{"facts":[]}""", "/v1/agent/skills" to """{"skills":[]}""",
            "/v1/agent/board" to board, "/v1/agent/board/b2/answer" to """{"ok":true}"""))
        val activity = NodeAgent(node).activity(JvmNodeCrypto, "studio", null)
        // Newest plan first, tasks in order within a plan.
        assertEquals(listOf("b3", "b1", "b2"), activity.board.map { it.id })
        assertEquals(listOf("b2"), activity.waiting.map { it.id })
        assertEquals("? b2 writer: Write it (after b1)", NodeActivityWords.task(activity.board[2]))
        assertTrue(NodeAgent(node).answerBoard("b2", "Yes, Markdown"))
        assertTrue(node.asked.contains("POST http://invincible/v1/agent/board/b2/answer"))
        assertFalse(NodeAgent(node).answerBoard("b9", "x"))
    }

    @Test
    fun forgettingAFactThatIsGoneIsFalse() = runBlocking {
        val node = Node(mapOf("/v1/agent/memory/1" to """{"ok":true}"""))
        assertTrue(NodeAgent(node).forget(1))
        assertFalse(NodeAgent(node).forget(2))
        assertEquals("DELETE http://invincible/v1/agent/memory/1", node.asked.first())
    }
}
