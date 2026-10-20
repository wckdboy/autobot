package dev.wckdboy.autobot.shared.invincible

import dev.wckdboy.autobot.agent.core.session.ReceiptHost
import dev.wckdboy.autobot.agent.core.session.ReceiptNode
import dev.wckdboy.autobot.shared.Cancellable
import dev.wckdboy.autobot.shared.HttpRequestSpec
import dev.wckdboy.autobot.shared.HttpSink
import dev.wckdboy.autobot.shared.HttpTransport
import dev.wckdboy.autobot.shared.recipes.Recipe
import dev.wckdboy.autobot.shared.recipes.RecipeCodec
import dev.wckdboy.autobot.shared.recipes.RecipeRunRecord
import dev.wckdboy.autobot.shared.recipes.RecipeRunStatus
import dev.wckdboy.autobot.shared.recipes.RecipeSchedule
import dev.wckdboy.autobot.shared.recipes.RecipeState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "While you were away": what a node did since the owner last looked, counted and worded. */
class NodeDigestTest {
    private fun request(
        at: Long,
        tier: String = "local",
        action: String = "run",
        verified: Boolean = true,
        caller: String = "local",
        tokens: Pair<Long, Long> = 0L to 0L,
        egress: List<ReceiptHost> = emptyList(),
    ) = NodeRequest(at, caller, null, action, "requested", ReceiptNode("studio", "r$at", verified, model = "m", tier = tier, egress = egress), tokens.first, tokens.second)

    private fun action(at: Long, ran: Boolean, by: String) = AgentActionEntry(at, "s", "command", "run: ls", ran, by)

    private fun task(id: String, status: String, updated: Long) = BoardTask(id, "p1", "writer", "Goal $id", status, emptyList(), null, "Which?".takeIf { status == "blocked" }, updated)

    private fun activity(
        requests: List<NodeRequest> = emptyList(),
        actions: List<AgentActionEntry> = emptyList(),
        board: List<BoardTask> = emptyList(),
        problem: String? = null,
    ) = NodeActivity(requests, actions, emptyList(), emptyList(), problem, board)

    @Test
    fun countsOnlyWhatHappenedAfterTheLastLook() {
        val d = NodeDigests.compute(
            activity(
                requests = listOf(
                    request(50, tokens = 100L to 10L),
                    request(100),
                    request(101, tokens = 1_000L to 200L),
                    request(102, tier = "cloud", tokens = 500L to 50L, egress = listOf(ReceiptHost("api.anthropic.com", "tor", 1))),
                    request(103, action = "block"),
                ),
                actions = listOf(
                    action(90, ran = true, by = "app"),
                    action(110, ran = true, by = "policy"),
                    action(111, ran = true, by = "grant"),
                    action(112, ran = true, by = "telegram"),
                    action(113, ran = false, by = "app"),
                ),
            ),
            runs = emptyList(),
            since = 100,
        )
        assertEquals(3, d.requests)
        assertEquals(1, d.onNode)
        assertEquals(1, d.inCloud)
        assertEquals(1, d.notRun)
        assertEquals(1_500L, d.tokensIn)
        assertEquals(250L, d.tokensOut)
        assertEquals(1, d.leftTheNode)
        assertEquals(0, d.unverified)
        assertEquals(4, d.actions)
        assertEquals(2, d.autoAllowed)
        assertEquals(1, d.approved)
        assertEquals(1, d.refused)
        assertTrue(d.hasNews)
    }

    @Test
    fun thisDevicesOwnRequestsAreNotNews() {
        val d = NodeDigests.compute(
            activity(listOf(request(200, caller = "device:My-Phone"), request(201, caller = "device:Tablet"))),
            emptyList(),
            since = 100,
            exceptDevice = "My-Phone",
        )
        assertEquals(1, d.requests)
    }

    @Test
    fun nothingNewIsNoCard() {
        val d = NodeDigests.compute(activity(listOf(request(10)), listOf(action(10, true, "policy")), listOf(task("b1", "done", 10))), emptyList(), since = 100)
        assertFalse(d.hasNews)
        assertTrue(NodeDigestWords.lines(d).isEmpty())
        assertNull(NodeDigestWords.needsYou(d))
    }

    @Test
    fun aQuestionWaitingCountsHoweverOldItIs() {
        val d = NodeDigests.compute(activity(board = listOf(task("b1", "blocked", 5), task("b2", "failed", 5), task("b3", "failed", 150))), emptyList(), since = 100)
        assertTrue(d.hasNews)
        assertEquals(listOf("b1"), d.waiting.map { it.id })
        assertEquals(listOf("b3"), d.failedTasks.map { it.id })
        assertEquals("1 question waiting", NodeDigestWords.needsYou(d))
        assertEquals(1, d.failures)

        // Seen already and nothing else new: no card. A new question brings it back.
        val seen = NodeDigests.compute(activity(board = listOf(task("b1", "blocked", 5))), emptyList(), since = 100)
        assertFalse(seen.hasNews)
        assertEquals(1, seen.waiting.size)
        assertTrue(NodeDigests.compute(activity(board = listOf(task("b1", "blocked", 120))), emptyList(), since = 100).hasNews)
    }

    @Test
    fun aReceiptThatFailedToVerifyComesFirstAsAnError() {
        val d = NodeDigests.compute(activity(listOf(request(200), request(201, verified = false), request(202, verified = false))), emptyList(), since = 100)
        val lines = NodeDigestWords.lines(d)
        assertEquals(NodeDigestLine("2 receipts failed to verify ✗", NodeDigestLine.Tone.ERROR), lines.first())
        assertEquals("3 requests, all on your node", lines[1].text)
        assertTrue(lines.none { it.tone == NodeDigestLine.Tone.OK })

        val one = NodeDigests.compute(activity(listOf(request(200, verified = false))), emptyList(), since = 100)
        assertEquals("1 receipt failed to verify ✗", NodeDigestWords.lines(one).first().text)
    }

    @Test
    fun allVerifiedIsAQuietLastLine() {
        val d = NodeDigests.compute(activity(listOf(request(200), request(201))), emptyList(), since = 100)
        assertEquals(NodeDigestLine("All 2 receipts verified ✓", NodeDigestLine.Tone.OK), NodeDigestWords.lines(d).last())
        val one = NodeDigests.compute(activity(listOf(request(200))), emptyList(), since = 100)
        assertEquals("Its receipt verified ✓", NodeDigestWords.lines(one).last().text)
    }

    @Test
    fun wordsForRequests() {
        fun words(vararg r: NodeRequest) = NodeDigestWords.requests(NodeDigests.compute(activity(r.toList()), emptyList(), since = 0))
        assertEquals("1 request, all on your node", words(request(1)))
        assertEquals("2 requests, all in the cloud · 1.2k tokens", words(request(1, "cloud", tokens = 1_000L to 0L), request(2, "cloud", tokens = 150L to 50L)))
        assertEquals("3 requests: 2 on your node, 1 in the cloud", words(request(1), request(2), request(3, "cloud")))
        assertEquals("3 requests, all on your node, 1 not run", words(request(1), request(2), request(3, action = "ask")))
        assertEquals("1 request, none ran", words(request(1, action = "block")))
        assertNull(words())
    }

    @Test
    fun wordsForActions() {
        fun words(vararg a: AgentActionEntry) = NodeDigestWords.actions(NodeDigests.compute(activity(actions = a.toList()), emptyList(), since = 0))
        assertEquals("3 actions, all allowed by the rules", words(action(1, true, "policy"), action(2, true, "grant"), action(3, true, "unattended")))
        assertEquals("2 actions: 1 by the rules, 1 approved by you", words(action(1, true, "policy"), action(2, true, "app")))
        assertEquals("2 actions: 1 approved by you, 1 refused", words(action(1, true, "cli"), action(2, false, "policy")))
        assertEquals("1 action: 1 refused", words(action(1, false, "unattended")))
        assertNull(words())
    }

    @Test
    fun wordsForRunsAndFailures() {
        val runs = listOf(
            NodeDigestRun("Morning brief", 200, true, "chat-1"),
            NodeDigestRun("Backups", 190, true),
            NodeDigestRun("Inbox triage", 180, false, "chat-2"),
            NodeDigestRun("Old one", 50, true),
        )
        val d = NodeDigests.compute(activity(board = listOf(task("b1", "failed", 150))), runs, since = 100)
        assertEquals(listOf("Morning brief", "Backups", "Inbox triage"), d.runs.map { it.name })
        assertEquals("Morning brief and Backups ran", NodeDigestWords.ran(d))
        assertEquals("Inbox triage failed · 1 board task failed", NodeDigestWords.failed(d))
        assertEquals(NodeDigestLine("Inbox triage failed · 1 board task failed ✗", NodeDigestLine.Tone.ERROR), NodeDigestWords.lines(d).last())
        assertEquals("chat-1", d.newestChat)

        val many = NodeDigests.compute(activity(), (1..5).map { NodeDigestRun("R$it", 200L + it, true) }, since = 100)
        assertEquals("R5, R4 and 3 more ran", NodeDigestWords.ran(many))
    }

    @Test
    fun anAgentThatIsNotRunningIsAWarning() {
        val d = NodeDigests.compute(activity(listOf(request(200)), problem = "The Autobot Agent is not running on the node."), emptyList(), since = 100)
        assertTrue(NodeDigestWords.lines(d).any { it.tone == NodeDigestLine.Tone.WARNING && it.text.startsWith("The agent was not running") })
    }

    @Test
    fun spokenReadsTheWholeCardWithoutGlyphs() {
        val d = NodeDigests.sample(now = 100_000)
        assertEquals(
            "While you were away on studio-mac. 12 requests, all on your node · 18.2k tokens. 3 actions: 2 by the rules, 1 approved by you. " +
                "Morning brief ran. All 12 receipts verified. 2 approvals, 1 question waiting.",
            NodeDigestWords.spoken(d, "studio-mac"),
        )
        assertTrue(NodeDigestWords.lines(NodeDigests.sample(100_000, failedReceipt = true)).first().tone == NodeDigestLine.Tone.ERROR)
    }

    @Test
    fun tokens() {
        assertEquals("812", NodeDigestWords.tokens(812))
        assertEquals("1k", NodeDigestWords.tokens(1_000))
        assertEquals("18.2k", NodeDigestWords.tokens(18_230))
        assertEquals("999k", NodeDigestWords.tokens(999_000))
        assertEquals("1.4M", NodeDigestWords.tokens(1_420_000))
    }

    @Test
    fun theFirstLookCoversADay() {
        assertEquals(1_000_000L - 86_400, NodeDigests.since(null, 1_000_000))
        assertEquals(999_000L, NodeDigests.since(999_000, 1_000_000))
        // A clock that went back never makes the window start in the future.
        assertEquals(1_000_000L, NodeDigests.since(2_000_000, 1_000_000))
        assertEquals("node_digest_seen.p1", NodeDigests.seenKey("p1"))
    }

    private fun recipe(id: String, runsOn: String?, last: RecipeRunRecord?) =
        Recipe(id, "Recipe $id", "do it", RecipeSchedule.Daily(8 * 60), runsOn = runsOn, state = RecipeState(lastRun = last))

    @Test
    fun recipeRunsComeWithTheirChats() {
        val collected = RecipeRunRecord(200_000, 210_000, RecipeRunStatus.DONE, sessionId = "chat-a")
        val failed = RecipeRunRecord(300_000, 310_000, RecipeRunStatus.FAILED, sessionId = "chat-b")
        val recipes = listOf(
            NodeDigests.recipe(recipe("a", "node-1", collected)),
            NodeDigests.recipe(recipe("b", "node-1", failed)),
            NodeDigests.recipe(recipe("c", "node-2", collected)),
            NodeDigests.recipe(recipe("d", "node-1", null)),
        )
        val schedules = listOf(
            // A newer run of a: not collected yet.
            AgentSchedule("r-a", "0 8 * * *", "do it", false, null, 400, true, "ok", name = "Recipe a"),
            // b's run the app already has.
            AgentSchedule("r-b", "0 8 * * *", "do it", false, null, 300, false, "x"),
            // d never collected.
            AgentSchedule("r-d", "@daily", "do it", false, null, 350, true, "ok"),
            // The owner's own task, and one that never ran.
            AgentSchedule("t1", "@daily", "check disk space\nand warn me", false, null, 380, true, "ok"),
            AgentSchedule("t2", "@daily", "never", false, null, null, null, null),
        )
        val runs = NodeDigests.runs("node-1", recipes, schedules)
        assertEquals(
            setOf(
                NodeDigestRun("Recipe a", 200, true, "chat-a"),
                NodeDigestRun("Recipe b", 300, false, "chat-b"),
                NodeDigestRun("Recipe a", 400, true, null),
                NodeDigestRun("Recipe d", 350, true, null),
                NodeDigestRun("check disk space", 380, true, null),
            ),
            runs.toSet(),
        )
        assertEquals(5, runs.size)
        // From the stored JSON, as iOS hands it over.
        assertEquals(recipes[0], NodeDigests.recipeJson(RecipeCodec.encode(recipe("a", "node-1", collected))))
        assertNull(NodeDigests.recipeJson("not json"))
    }

    /** Answers each path with its body; [agentStatus] for the agent's paths when it is not 200. */
    private class Node(private val bodies: Map<String, String>, private val agentStatus: Int = 200) : HttpTransport {
        val asked = mutableListOf<String>()

        override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable {
            asked += request.url.removePrefix("http://invincible")
            val path = request.url.removePrefix("http://invincible").substringBefore('?')
            val body = bodies[path]
            when {
                path.startsWith("/v1/agent") && agentStatus != 200 ->
                    sink.onResponse(agentStatus, emptyMap(), """{"error":{"message":"x","type":"agent_unavailable"}}""")
                body == null -> sink.onResponse(404, emptyMap(), """{"error":{"message":"not found","type":"not_found"}}""")
                else -> {
                    sink.onResponse(200, emptyMap(), null)
                    sink.onLine(body)
                    sink.onComplete()
                }
            }
            return Cancellable {}
        }
    }

    private val audit = """{"entries":[
        {"at":150,"caller":"local","client":"autobot-agent","api":"chat","prev":"00","receipt":{"receipt":{"version":0,"request_id":"q1","node":"k",
          "decision":{"action":"run","model":"qwen","tier":"local"},"tokens":{"input":1200,"output":410},"egress":[]},"signature":"x"}},
        {"at":160,"caller":"device:My-Phone","api":"chat","prev":"ab","receipt":{"receipt":{"version":0,"request_id":"q2","node":"k",
          "decision":{"action":"run","model":"claude","tier":"cloud"},"egress":[{"host":"api.anthropic.com","route":"tor"}]},"signature":"x"}}
    ]}"""

    @Test
    fun fetchesSinceTheLastLookWithTokens() = runBlocking {
        val node = Node(
            mapOf(
                "/v1/audit" to audit,
                "/v1/agent/actions" to """{"actions":[{"at":155,"session":"cron-t1","kind":"command","action":"run: df -h","ran":true,"by":"policy"}]}""",
                "/v1/agent/board" to """{"tasks":[{"id":"b1","plan":"p1","agent":"writer","goal":"Write","status":"blocked","depends_on":[],"question":"Markdown?","updated":5}]}""",
                "/v1/agent/schedules" to """{"schedules":[{"id":"t1","when":"@daily","task":"check disk","telegram":false,"next":null,"last":{"at":170,"ok":true,"result":"ok"}}]}""",
                "/v1/agent/approvals" to """{"approvals":[
                    {"id":"a2","session":"cron-t1","text":"run: rm x","action":{"kind":"command","command":"rm x"},"asked_at":160,"expires_at":760},
                    {"id":"a1","session":"cron-t1","text":"write","action":{"kind":"write","path":"/etc/x","bytes":3,"inside":false},"agent":"coder","asked_at":150}]}""",
            ),
        )
        val d = NodeDigests.fetch(NodeAgent(node), JvmNodeCrypto, "studio", null, "node-1", since = 100, recipes = emptyList(), exceptDevice = "My-Phone")
        assertTrue(node.asked.contains("/v1/audit?limit=500&since=100"))
        assertTrue(node.asked.contains("/v1/agent/actions?limit=500&since=100"))
        // The phone's own request is left out; the agent's has its tokens; nothing verifies without a pinned key.
        assertEquals(1, d.requests)
        assertEquals(1610L, d.tokens)
        assertEquals(1, d.unverified)
        assertEquals(1, d.autoAllowed)
        assertEquals(listOf("b1"), d.waiting.map { it.id })
        assertEquals(listOf(NodeDigestRun("check disk", 170, true)), d.runs)
        assertNull(d.agentProblem)
        // Approvals, oldest first, with what they ask.
        assertEquals(listOf("a1", "a2"), d.approvals!!.map { it.id })
        assertEquals(AgentAction.Write("/etc/x", 3, inside = false), d.approvals!![0].action)
        assertEquals("coder", d.approvals!![0].agent)
        assertEquals(760L, d.approvals!![1].expiresAt)
        assertEquals("cron-t1", d.approvalSession)
        assertEquals("2 approvals, 1 question waiting", NodeDigestWords.needsYou(d))
        assertFalse(d.opensBoard)
    }

    @Test
    fun anOlderNodeWithoutBoardOrSchedulesStillHasADigest() = runBlocking {
        val node = Node(mapOf("/v1/audit" to audit, "/v1/agent/actions" to """{"actions":[]}"""))
        val d = NodeDigests.fetch(NodeAgent(node), JvmNodeCrypto, "studio", null, "node-1", since = 100, recipes = emptyList())
        assertEquals(2, d.requests)
        assertTrue(d.waiting.isEmpty())
        // No approvals endpoint: unknown, not none.
        assertNull(d.approvals)
        assertNull(d.agentProblem)
    }

    @Test
    fun withoutTheAgentTheRequestsStillCount() = runBlocking {
        val d = NodeDigests.fetch(NodeAgent(Node(mapOf("/v1/audit" to audit), agentStatus = 503)), JvmNodeCrypto, "studio", null, "node-1", 100, emptyList())
        assertEquals(2, d.requests)
        assertEquals(0, d.actions)
        assertTrue(d.agentProblem!!.contains("autobot serve"))
    }

    private fun approval(id: String, session: String, at: Long) = PendingApproval(id, session, "run: ls", AgentAction.Command("ls"), askedAt = at)

    @Test
    fun whatNeedsYouFirstCombinesApprovalsAndQuestions() {
        val q = task("b1", "blocked", 5)
        fun words(approvals: List<PendingApproval>?, questions: List<BoardTask>) =
            NodeDigestWords.needsYou(NodeDigest(100, waiting = questions, approvals = approvals))
        assertEquals("1 approval waiting", words(listOf(approval("a", "s", 1)), emptyList()))
        assertEquals("2 approvals waiting", words(listOf(approval("a", "s", 1), approval("b", "s", 2)), emptyList()))
        assertEquals("1 approval, 1 question waiting", words(listOf(approval("a", "s", 1)), listOf(q)))
        assertEquals("1 question waiting", words(null, listOf(q)))
        assertEquals("1 question waiting", words(emptyList(), listOf(q)))
        assertNull(words(emptyList(), emptyList()))
    }

    @Test
    fun approvalsOpenTheirConversationWhenThereIsOne() {
        assertEquals("s1", NodeDigest(0, approvals = listOf(approval("a", "s1", 1), approval("b", "s1", 2))).approvalSession)
        assertNull(NodeDigest(0, approvals = listOf(approval("a", "s1", 1), approval("b", "s2", 2))).approvalSession)
        assertNull(NodeDigest(0).approvalSession)
        assertTrue(NodeDigest(0, waiting = listOf(task("b1", "blocked", 5))).opensBoard)
        assertFalse(NodeDigest(0, waiting = listOf(task("b1", "blocked", 5)), approvals = listOf(approval("a", "s1", 1))).opensBoard)
    }

    @Test
    fun aNewApprovalIsNewsAnOldOneAloneIsNot() {
        assertTrue(NodeDigest(100, approvals = listOf(approval("a", "s", 150))).hasNews)
        assertFalse(NodeDigest(100, approvals = listOf(approval("a", "s", 50))).hasNews)
    }

    @Test
    fun answeringInsteadSendsTheText() = runBlocking {
        val bodies = mutableListOf<String?>()
        val node = object : HttpTransport {
            override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable {
                bodies += request.body
                if (request.url.endsWith("/gone")) {
                    sink.onResponse(404, emptyMap(), """{"error":{"message":"no","type":"not_found"}}""")
                } else {
                    sink.onResponse(200, emptyMap(), null)
                    sink.onLine("""{"ok":true}""")
                    sink.onComplete()
                }
                return Cancellable {}
            }
        }
        assertTrue(NodeAgent(node).answerInstead("a1", "  Back up to the NAS instead "))
        assertEquals("""{"answer":"instead","text":"Back up to the NAS instead"}""", bodies.last())
        assertFalse(NodeAgent(node).answerInstead("gone", "x"))
        assertTrue(runCatching { NodeAgent(node).answerInstead("a1", " ") }.isFailure)
    }
}
