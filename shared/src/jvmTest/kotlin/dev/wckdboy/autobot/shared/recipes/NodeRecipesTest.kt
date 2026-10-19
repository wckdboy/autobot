package dev.wckdboy.autobot.shared.recipes

import dev.wckdboy.autobot.agent.core.session.ReceiptHost
import dev.wckdboy.autobot.agent.core.session.ReceiptNode
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.shared.Cancellable
import dev.wckdboy.autobot.shared.HttpRequestSpec
import dev.wckdboy.autobot.shared.HttpSink
import dev.wckdboy.autobot.shared.HttpTransport
import dev.wckdboy.autobot.shared.invincible.NodeAgent
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recipes handed to a node: the schedule it gets, and runs collected back into chats. */
class NodeRecipesTest {
    private val oslo = TimeZone.of("Europe/Oslo")
    // 2026-10-19 (CEST, UTC+2) and 2026-12-01 (CET, UTC+1).
    private val summer = 1792400000000L
    private val winter = 1796083200000L

    private fun recipe(schedule: RecipeSchedule = RecipeSchedule.Daily(7 * 60), tools: List<String> = emptyList()) = Recipe(
        id = "abc-123", name = "Disk check", prompt = "check the disk", schedule = schedule, tools = tools, enabled = true, runsOn = "node-1",
    )

    @Test
    fun schedulesGoInUtcAtThePhonesTime() {
        assertEquals("CRON_TZ=UTC 0 5 * * *", NodeRecipes.cron(RecipeSchedule.Daily(7 * 60), oslo, summer))
        assertEquals("CRON_TZ=UTC 0 6 * * *", NodeRecipes.cron(RecipeSchedule.Daily(7 * 60), oslo, winter))
        // 00:30 Monday to Friday in Oslo is 22:30 Sunday to Thursday in UTC.
        assertEquals("CRON_TZ=UTC 30 22 * * 0,1,2,3,4", NodeRecipes.cron(RecipeSchedule.Weekdays(30), oslo, summer))
        // Sunday (ISO 7) at 09:15 → cron day 0.
        assertEquals("CRON_TZ=UTC 15 7 * * 0", NodeRecipes.cron(RecipeSchedule.Weekly(7, 9 * 60 + 15), oslo, summer))
        assertEquals("every 6h", NodeRecipes.cron(RecipeSchedule.EveryHours(6), oslo, summer))
        // Behind UTC: 23:00 in New York (UTC-4) is 03:00 the next day in UTC.
        assertEquals("CRON_TZ=UTC 0 3 * * 2", NodeRecipes.cron(RecipeSchedule.Weekly(1, 23 * 60), TimeZone.of("America/New_York"), summer))
    }

    @Test
    fun theNodeGetsTheRecipesToolGroupsAndHearsWhatItLacks() {
        val r = recipe(tools = RecipeTools.READ_FILES.tools + RecipeTools.WEB.tools)
        assertEquals(listOf("files-read", "web"), NodeRecipes.groups(r))
        // The node has web_fetch (through its gateway); not the phone's vault.
        assertTrue(NodeRecipes.limits(r).isEmpty())
        assertEquals(listOf("Vault is not available on the node"), NodeRecipes.limits(recipe(tools = RecipeTools.VAULT.tools)))
        val body = Json.parseToJsonElement(NodeRecipes.body(r, oslo, summer)).jsonObject
        assertEquals("r-abc-123", body["id"]!!.jsonPrimitive.content)
        assertEquals(listOf("files-read", "web"), body["tools"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("CRON_TZ=UTC 0 5 * * *", body["when"]!!.jsonPrimitive.content)
        assertTrue(NodeRecipes.limits(r.copy(includeRecentChats = true)).any { it.contains("Recent chats") })
    }

    @Test
    fun aPushIsDueWhenTheOffsetMoves() {
        val r = recipe()
        val pushed = r.copy(state = r.state.copy(pushed = NodeRecipes.pushed(r, oslo, summer)))
        assertTrue(!NodeRecipes.needsPush(pushed, pushed, oslo, summer))
        assertTrue(NodeRecipes.needsPush(pushed, pushed, oslo, winter))
        assertTrue(NodeRecipes.needsPush(pushed.copy(prompt = "x"), pushed, oslo, summer))
        assertTrue(!NodeRecipes.needsPush(pushed.copy(runsOn = null), pushed, oslo, summer))
    }

    @Test
    fun aNodeRecipeIsNeverDueOnThePhone() {
        val r = recipe().copy(state = RecipeState(nextRunAt = 1))
        assertTrue(!RecipePlanner.isDue(r, summer))
        assertNull(RecipePlanner.nextWake(listOf(r)))
    }

    /** The node's agent API, scripted: schedules posted, runs to hand out. */
    private class Node : HttpTransport {
        val posted = mutableListOf<String>()
        val ran = mutableListOf<String>()
        var runs = """{"runs":[]}"""

        override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable {
            val path = request.url.removePrefix("http://invincible/v1/agent")
            val body = when {
                request.method == "POST" && path == "/schedules" -> posted.add(request.body!!).let { """{"id":"r-abc-123"}""" }
                request.method == "POST" && path.endsWith("/run") -> ran.add(path).let { """{"started":true}""" }
                path.startsWith("/schedules/r-abc-123/runs") -> runs
                else -> null
            }
            if (body == null) {
                sink.onResponse(404, emptyMap(), "{}")
            } else {
                sink.onResponse(200, emptyMap(), null)
                sink.onLine(body)
                sink.onComplete()
            }
            return Cancellable {}
        }
    }

    private class Host(val node: Node, var stored: Recipe) : NodeRecipeHost {
        val chats = mutableMapOf<String, List<SessionEvent>>()
        val notified = mutableListOf<RecipeRunRecord>()
        override fun now() = 1792400000000L
        override fun zone() = TimeZone.of("Europe/Oslo")
        override suspend fun recipes() = listOf(stored)
        override suspend fun load(id: String) = stored.takeIf { it.id == id }
        override suspend fun save(recipe: Recipe) {
            stored = recipe
        }
        override suspend fun target(providerId: String) = NodeRecipeTarget(NodeAgent(node), "studio") { id ->
            ReceiptNode("studio", id, verified = id != "bad", model = "qwen3.5-4b", tier = "node",
                egress = if (id == "cloud") listOf(ReceiptHost("api.example.com", "tor", 1, 900, 100)) else emptyList())
        }
        override suspend fun newChat(recipe: Recipe, providerId: String, atMillis: Long) = "chat-${chats.size + 1}".also { chats[it] = emptyList() }
        override suspend fun append(chatId: String, events: List<SessionEvent>) {
            chats[chatId] = chats[chatId].orEmpty() + events
        }
        override fun notifyFinished(recipe: Recipe, record: RecipeRunRecord) {
            notified += record
        }
    }

    @Test
    fun runsAreCollectedIntoChatsWithTheNodesReceipts() = runBlocking {
        val node = Node()
        val host = Host(node, recipe())
        val sync = NodeRecipeSync(host)
        assertNull(sync.saved(host.stored, null))
        assertEquals(1, node.posted.size)
        assertEquals(NodeRecipes.pushed(host.stored, host.zone(), host.now()), host.stored.state.pushed)
        // Nothing changed: the next pass sends nothing again.
        assertEquals(0, sync.sync())
        assertEquals(1, node.posted.size)

        node.runs = """{"runs":[
            {"at":1792400100,"finished":1792400160,"ok":true,"result":"Disk is 41% full.","receipts":["r1","cloud"],"trigger":"schedule"},
            {"at":1792486500,"finished":1792486510,"ok":false,"result":"the gateway is down","receipts":[],"trigger":"manual"}]}"""
        assertEquals(2, sync.sync())
        assertEquals(listOf("chat-1", "chat-2"), host.chats.keys.toList())
        val first = host.chats["chat-1"]!!
        assertEquals("check the disk", (first[1] as SessionEvent.UserMessage).text)
        assertEquals("Disk is 41% full.", (first[2] as SessionEvent.AssistantMessage).text)
        assertEquals("qwen3.5-4b", (first[2] as SessionEvent.AssistantMessage).model)
        assertEquals(listOf("r1", "cloud"), (first[3] as SessionEvent.TurnReceipt).nodes!!.map { it.requestId })
        val (done, failed) = host.notified
        assertEquals(RecipeRunStatus.DONE, done.status)
        assertEquals(1, done.hosts)
        assertEquals(900L, done.bytesSent)
        assertEquals(RecipeRunStatus.FAILED, failed.status)
        assertEquals(RecipeTrigger.MANUAL, failed.trigger)
        assertEquals(1792486500000L, host.stored.state.lastRun!!.startedAt)

        // Run now goes to the node, after sending it the recipe as it is.
        assertNull(sync.runNow(host.stored))
        assertEquals(listOf("/schedules/r-abc-123/run"), node.ran)
        assertEquals(2, node.posted.size)
    }
}
