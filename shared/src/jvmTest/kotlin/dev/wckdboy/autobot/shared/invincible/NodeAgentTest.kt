package dev.wckdboy.autobot.shared.invincible

import dev.wckdboy.autobot.shared.Cancellable
import dev.wckdboy.autobot.shared.HttpRequestSpec
import dev.wckdboy.autobot.shared.HttpSink
import dev.wckdboy.autobot.shared.HttpTransport
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [NodeAgent] against a scripted transport: spec/agent.md's requests, events and errors. */
class NodeAgentTest {
    private class Scripted(private val status: Int, private val lines: List<String>, private val errorBody: String? = null) : HttpTransport {
        val sent = mutableListOf<HttpRequestSpec>()

        override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable {
            sent += request
            sink.onResponse(status, emptyMap(), errorBody)
            if (status in 200..299) {
                lines.forEach(sink::onLine)
                sink.onComplete()
            }
            return Cancellable {}
        }
    }

    @Test
    fun aTurnStreamsItsEvents() = runBlocking {
        val transport = Scripted(
            200,
            listOf(
                """data: {"type":"receipt","id":"r1","model":"qwen3.5-4b","tier":"node"}""", "",
                """data: {"type":"approval","id":"a1","text":"run: ls","action":{"kind":"command","command":"ls"}}""", "",
                """data: {"type":"approval","id":"a2","text":"write 5 bytes","action":{"kind":"write","path":"/w/x","bytes":5,"inside":true}}""", "",
                """data: {"type":"approval","id":"a3","text":"?","action":{"kind":"teleport"}}""", "",
                """data: {"type":"future-event"}""", "",
                """data: {"type":"tool","text":"run: ls"}""", "",
                """data: {"type":"approval","id":"a4","text":"[coder] fetch https://docs.rs","action":{"kind":"fetch","url":"https://docs.rs"},"agent":"coder"}""", "",
                """data: {"type":"approval","id":"a5","text":"use gh/get_issue {}","action":{"kind":"mcp","tool":"gh/get_issue","arguments":"{}"}}""", "",
                """data: {"type":"member","agent":"coder","text":"fetched it"}""", "",
                """data: {"type":"answer","text":"done","receipts":["r1"]}""", "",
            ),
        )
        val events = NodeAgent(transport).turn("phone-1", "list files").toList()
        assertEquals(
            listOf(
                AgentEvent.Receipt("r1", "qwen3.5-4b", "node"),
                AgentEvent.Approval("a1", "run: ls", AgentAction.Command("ls")),
                AgentEvent.Approval("a2", "write 5 bytes", AgentAction.Write("/w/x", 5, true)),
                AgentEvent.Approval("a3", "?", AgentAction.Other("teleport")),
                AgentEvent.Tool("run: ls"),
                AgentEvent.Approval("a4", "[coder] fetch https://docs.rs", AgentAction.Fetch("https://docs.rs"), "coder"),
                AgentEvent.Approval("a5", "use gh/get_issue {}", AgentAction.Mcp("gh/get_issue", "{}")),
                AgentEvent.Member("coder", "fetched it"),
                AgentEvent.Answer("done", listOf("r1")),
            ),
            events,
        )
        val request = transport.sent.single()
        assertEquals("POST", request.method)
        assertEquals("http://invincible/v1/agent/sessions/phone-1/turns", request.url)
        assertEquals("""{"text":"list files"}""", request.body)
        assertEquals("text/event-stream", request.headers["Accept"])
    }

    @Test
    fun aStreamThatEndsEarlySaysTheTurnGoesOn() = runBlocking {
        val events = NodeAgent(Scripted(200, listOf("""data: {"type":"tool","text":"run: ls"}"""))).turn("s", "x").toList()
        assertTrue(events.last().toString(), (events.last() as AgentEvent.Failed).text.contains("goes on"))
    }

    @Test
    fun errorsSayWhatToDo() = runBlocking {
        val stopped = Scripted(503, emptyList(), """{"error":{"message":"x","type":"agent_unavailable"}}""")
        val failed = NodeAgent(stopped).turn("s", "x").toList().single() as AgentEvent.Failed
        assertTrue(failed.text, failed.text.contains("autobot serve"))
        val busy = Scripted(409, emptyList(), """{"error":{"message":"x","type":"session_busy"}}""")
        val e = runCatching { NodeAgent(busy).reset("s") }.exceptionOrNull() as NodeAgentException
        assertEquals("session_busy", e.type)
        assertFalse(NodeAgent(Scripted(404, emptyList(), "{}")).answer("gone", ApprovalAnswer.ONCE))
    }

    @Test
    fun historyAndSessionsParse() = runBlocking {
        val history = Scripted(
            200,
            listOf(
                """{"id":"s","messages":[{"role":"user","content":"hi"},""",
                """{"role":"assistant","content":"","tool_calls":[{"id":"c","function":{"name":"terminal","arguments":"{}"}}]},""",
                """{"role":"tool","tool_call_id":"c","content":"ok"},{"role":"assistant","content":" done "}]}""",
            ),
        )
        assertEquals(
            listOf(AgentLine("user", "hi"), AgentLine("tool", "terminal"), AgentLine("assistant", "done")),
            NodeAgent(history).history("s"),
        )
        val sessions = Scripted(200, listOf("""{"sessions":[{"id":"a","modified":5},{"modified":1}]}"""))
        assertEquals(listOf(AgentSession("a", 5)), NodeAgent(sessions).sessions())
        assertEquals("app-Pixel9", NodeAgent.sessionFor("Pixel 9 ✓"))
        assertEquals("app-device", NodeAgent.sessionFor("✓"))
    }

    @Test
    fun schedulesParse() = runBlocking {
        val transport = Scripted(
            200,
            listOf(
                """{"schedules":[{"id":"t1","when":"@daily","task":"check backups","telegram":true,"next":1700000000,"last":{"at":1690000000,"ok":false,"result":"refused"}},""",
                """{"id":"t2","when":"every 30m","task":"x","telegram":false,"next":null,"last":null}]}""",
            ),
        )
        assertEquals(
            listOf(
                AgentSchedule("t1", "@daily", "check backups", true, 1700000000, 1690000000, false, "refused"),
                AgentSchedule("t2", "every 30m", "x", false, null, null, null, null),
            ),
            NodeAgent(transport).schedules(),
        )
        assertEquals("http://invincible/v1/agent/schedules", transport.sent.single().url)
        val add = Scripted(200, listOf("""{"id":"t3"}"""))
        assertEquals("t3", NodeAgent(add).addSchedule("@hourly", "ping", telegram = false))
        assertEquals("""{"when":"@hourly","task":"ping","telegram":false}""", add.sent.single().body)
        val bad = Scripted(400, emptyList(), """{"error":{"message":"minute: 99 is outside 0-59","type":"invalid_request_error"}}""")
        val e = runCatching { NodeAgent(bad).addSchedule("99 * * * *", "x") }.exceptionOrNull()
        assertEquals("minute: 99 is outside 0-59", e?.message)
    }
}
