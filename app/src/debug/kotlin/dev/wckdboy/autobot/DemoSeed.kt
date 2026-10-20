package dev.wckdboy.autobot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.SessionJson
import dev.wckdboy.autobot.agent.core.session.TodoItem
import dev.wckdboy.autobot.agent.core.session.TodoStatus
import dev.wckdboy.autobot.agent.core.session.TokenUsage
import dev.wckdboy.autobot.agent.core.session.ToolCallRecord
import dev.wckdboy.autobot.agent.core.session.TurnEndReason
import dev.wckdboy.autobot.agent.core.session.UserMessageKind
import dev.wckdboy.autobot.core.data.ConversationRepository
import dev.wckdboy.autobot.core.data.GalleryRepository
import dev.wckdboy.autobot.core.data.ModelRowRepository
import dev.wckdboy.autobot.core.data.ProviderRepository
import dev.wckdboy.autobot.core.data.RecipeRepository
import dev.wckdboy.autobot.core.data.SessionEventRepository
import dev.wckdboy.autobot.core.data.model.Conversation
import dev.wckdboy.autobot.core.data.model.GalleryItem
import dev.wckdboy.autobot.core.data.model.Provider
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.core.data.model.ProviderRouting
import dev.wckdboy.autobot.core.data.model.SessionEventRow
import dev.wckdboy.autobot.core.data.settings.SettingsRepository
import dev.wckdboy.autobot.core.diffusion.BackendKind
import dev.wckdboy.autobot.core.diffusion.DiffusionBackend
import dev.wckdboy.autobot.core.diffusion.DiffusionBackendRepository
import dev.wckdboy.autobot.core.models.Catalog
import dev.wckdboy.autobot.core.models.ModelLibrary
import dev.wckdboy.autobot.core.models.ModelStatus
import dev.wckdboy.autobot.core.network.AuditLog
import dev.wckdboy.autobot.providers.remote.LocalProviderSync
import dev.wckdboy.autobot.providers.remote.ProviderPresets

import dev.wckdboy.autobot.shared.recipes.RecipeCopy
import dev.wckdboy.autobot.shared.recipes.RecipePlanner
import dev.wckdboy.autobot.shared.recipes.RecipeRunRecord
import dev.wckdboy.autobot.shared.recipes.RecipeRunStatus
import dev.wckdboy.autobot.shared.recipes.RecipeTemplates
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Sample data for screenshots and UI work (`--ez demo true`, debug builds only; release builds
 * carry a no-op in its place): the same providers, chats, agent run, gallery images, installed
 * model, image backend, recipe run and audit entries as iOS's `DemoSeed.swift`. Runs once, on an
 * empty install (no chats and no providers).
 */
@Singleton
class DemoSeed @Inject constructor(
    private val settings: SettingsRepository,
    private val providers: ProviderRepository,
    private val conversations: ConversationRepository,
    private val events: SessionEventRepository,
    private val library: ModelLibrary,
    private val modelRows: ModelRowRepository,
    private val backends: DiffusionBackendRepository,
    private val gallery: GalleryRepository,
    private val recipes: RecipeRepository,
    private val auditLog: AuditLog,
) {
    suspend fun seedIfEmpty() {
        // The audit log lives in memory only: every demo launch gets its entries.
        recordAudit()
        if (conversations.storedCount() > 0 || providers.observeProviders().first().isNotEmpty()) return
        val now = System.currentTimeMillis()
        settings.setOnboarded()

        providers.save(Provider(DEMO_DEEPSEEK, ProviderKind.DEEPSEEK, "DeepSeek", ProviderPresets.DEEPSEEK_BASE_URL, "deepseek-flash", presetId = "deepseek"))
        providers.save(Provider(DEMO_OLLAMA, ProviderKind.OLLAMA, "Ollama", ProviderPresets.OLLAMA_BASE_URL, "qwen3:8b", routing = ProviderRouting.DIRECT, presetId = "ollama"))
        // A paired Invincible node, so Remote shows the node's agent row (its orange mark) and its
        // three sample bots (BotsDemo: default, ops with Slack down, support on email).
        providers.save(Provider(DebugLaunch.DEMO_NODE, ProviderKind.INVINCIBLE_NODE, "studio-mac", "http://192.168.1.20:8787/v1", "qwen3:8b", routing = ProviderRouting.DIRECT))

        val plan = Catalog.chatModels.first { it.id == PHONE_MODEL }
        library.add(plan)
        // An empty stand-in file, so the model reads as installed rather than "deleted outside the app".
        plan.manifest.files.forEach { file -> library.fileOf(plan.id, file).apply { parentFile?.mkdirs() }.createNewFile() }
        modelRows.updateProgress(plan.id, plan.totalBytes, ModelStatus.READY.name, null)
        backends.save(DiffusionBackend("demo-forge", BackendKind.SD_API, "Forge · desktop", "https://studio.tail1234.ts.net"))

        seed(DebugLaunch.DEMO_CHAT, "Reverse a list in Swift", DEMO_OLLAMA, "qwen3", now, minutesAgo = 3, tools = false) {
            add(SessionEvent.TurnStart(0))
            add(SessionEvent.UserMessage("How do I reverse a list in Swift?"))
            add(SessionEvent.RequestContext(DEMO_OLLAMA, "qwen3", 32_768))
            add(
                SessionEvent.AssistantMessage(
                    turn = 0, step = 0,
                    text = "Use `reversed()` for a lazy reversed view, or `reverse()` to reverse an array in place.\n\n```swift\nvar xs = [1, 2, 3]\nlet ys = Array(xs.reversed())\nxs.reverse()\n```\n\nBoth run in O(n).",
                    reasoning = "The user wants the idiomatic way. Mention reversed() vs reverse(), show both.",
                    usage = TokenUsage(inputTokens = 412, outputTokens = 96, totalTokens = 508), model = "qwen3",
                ),
            )
            add(SessionEvent.TurnEnd(0, TurnEndReason.COMPLETED))
        }
        val write = """{"file_path":"plan.md","content":"1. Ship the iOS port\n2. Verify the NPU path\n3. Write the README\n"}"""
        val read = """{"file_path":"notes.md"}"""
        val image = """{"prompt":"isometric greenhouse, riso print"}"""
        seed(DebugLaunch.DEMO_AGENT, "Summarize notes.md into a plan", DEMO_DEEPSEEK, "deepseek-chat", now, minutesAgo = 40, tools = true) {
            add(SessionEvent.TurnStart(0))
            add(SessionEvent.UserMessage("Summarize notes.md into a plan"))
            add(SessionEvent.RequestContext(DEMO_DEEPSEEK, "deepseek-chat", 128_000))
            add(SessionEvent.AssistantMessage(0, 0, "", toolCalls = listOf(ToolCallRecord("c1", "read", read)), usage = TokenUsage(inputTokens = 1_840, outputTokens = 22), model = "deepseek-chat"))
            add(SessionEvent.ToolCall("c1", "read", read))
            add(SessionEvent.ToolResult("c1", "read", "# Notes\n- ship iOS port\n- verify NPU path\n- write README"))
            add(
                SessionEvent.TodoWrite(
                    listOf(
                        TodoItem("Group notes by theme", TodoStatus.COMPLETED),
                        TodoItem("Draft the plan", TodoStatus.IN_PROGRESS),
                        TodoItem("Write plan.md", TodoStatus.PENDING),
                    ),
                ),
            )
            add(SessionEvent.AssistantMessage(0, 1, "", toolCalls = listOf(ToolCallRecord("c2", "write", write)), model = "deepseek-chat"))
            add(SessionEvent.ToolCall("c2", "write", write))
            add(SessionEvent.ToolResult("c2", "write", "wrote 412 bytes"))
            add(SessionEvent.AssistantMessage(0, 2, "", toolCalls = listOf(ToolCallRecord("c3", "generate_image", image)), model = "deepseek-chat"))
            add(SessionEvent.ToolCall("c3", "generate_image", image))
            add(
                SessionEvent.ToolResult(
                    "c3", "generate_image", "1 image saved to the gallery",
                    meta = JsonObject(mapOf("gallery" to JsonArray(listOf(JsonPrimitive(DebugLaunch.DEMO_IMAGE))))),
                ),
            )
            add(
                SessionEvent.AssistantMessage(
                    0, 3, "Done. `plan.md` has three milestones: ship the iOS port, verify the NPU path on hardware, then write the README.",
                    usage = TokenUsage(inputTokens = 2_310, outputTokens = 64), model = "deepseek-chat",
                ),
            )
            add(SessionEvent.TurnEnd(0, TurnEndReason.COMPLETED))
        }
        seed("demo-chat-private", "Private question", DEMO_OLLAMA, "qwen3", now, minutesAgo = 180, tools = false) {
            add(SessionEvent.TurnStart(0))
            add(SessionEvent.UserMessage("Private question"))
            add(SessionEvent.AssistantMessage(0, 0, "Sure. Nothing here leaves this phone.", model = "qwen3"))
            add(SessionEvent.TurnEnd(0, TurnEndReason.COMPLETED))
        }
        seedCascade(now)
        seedRecipe(now)
        seedGallery(now)
    }

    private fun recordAudit() {
        auditLog.record(method = "POST", host = "api.deepseek.com", path = "/chat/completions", tag = "DeepSeek", route = "offline", blocked = true, error = "Offline mode: network access is disabled")
        auditLog.record(method = "GET", host = "127.0.0.1", path = "/v1/models", bytesReceived = 1_204, status = 200, tag = "Ollama (local)", route = "loopback", blocked = false)
        auditLog.record(method = "GET", host = "huggingface.co", path = "/api/models", bytesReceived = 18_330, status = 200, tag = "models:huggingface", route = "tor", blocked = false)
    }

    /** Writes a chat's log line by line: typed events, or raw JSON for what the runtime writes itself (receipts). */
    private class Log(private val base: Long) {
        val lines = mutableListOf<Pair<Long, String>>()
        private val time get() = base + lines.size * 700L

        fun add(event: SessionEvent) {
            lines += time to SessionJson.encodeToString(LogEntry.serializer(), LogEntry(lines.size.toLong(), time, event))
        }

        fun raw(event: String) {
            lines += time to """{"seq":${lines.size},"time":$time,"event":$event}"""
        }
    }

    private suspend fun seed(
        id: String,
        title: String,
        provider: String,
        model: String,
        now: Long,
        minutesAgo: Long,
        tools: Boolean,
        build: Log.() -> Unit,
    ) {
        val at = now - minutesAgo * 60_000
        conversations.updateConversation(Conversation(id, title, provider, model, createdAt = at, updatedAt = at))
        val log = Log(at)
        if (!tools) log.add(SessionEvent.AgentSettingsChanged(toolsEnabled = false))
        log.build()
        events.append(log.lines.mapIndexed { seq, (time, json) -> SessionEventRow(id, seq.toLong(), time, json) })
    }

    /**
     * "Phone first" (the local-first cascade) on a plain DeepSeek chat (`open phone-first`): the
     * phone said it doesn't know, the user asked the cloud, then the phone answered a weather
     * question and the offer is still open.
     */
    private suspend fun seedCascade(now: Long) {
        val onPhone = """[{"provider":"local","model":"$PHONE_MODEL","name":"This phone","onDevice":true,"policy":"on device"}]"""
        seed(DebugLaunch.DEMO_PHONE_FIRST, "Who wrote Stiller?", DEMO_DEEPSEEK, "deepseek-chat", now, minutesAgo = 5, tools = false) {
            raw("""{"type":"agent/cascade","policy":"ASK"}""")
            add(SessionEvent.TurnStart(1))
            add(SessionEvent.UserMessage("Who wrote the novel Stiller?"))
            add(SessionEvent.AssistantMessage(1, 1, "I don't know who wrote it. I can't place that title.", model = PHONE_MODEL))
            raw("""{"type":"turn/receipt","turn":1,"models":$onPhone,"network":[],"cascade":{"role":"local","policy":"ASK","verdict":"WEAK","reasons":["dont-know"],"action":"OFFERED","judge":"heuristic","judgeMs":3}}""")
            add(SessionEvent.TurnEnd(1, TurnEndReason.COMPLETED))

            add(SessionEvent.TurnStart(2))
            add(SessionEvent.UserMessage("asked", kind = UserMessageKind.CONTEXT, contextId = "cascade/escalate"))
            add(SessionEvent.RequestContext(DEMO_DEEPSEEK, "deepseek-chat", 128_000))
            add(
                SessionEvent.AssistantMessage(
                    2, 1, "*Stiller* (1954) is a novel by the Swiss writer **Max Frisch**. A man arrested at the border insists he is not the missing sculptor Anatol Stiller.",
                    usage = TokenUsage(inputTokens = 318, outputTokens = 44), model = "deepseek-chat",
                ),
            )
            raw("""{"type":"turn/receipt","turn":2,"models":[{"provider":"$DEMO_DEEPSEEK","model":"deepseek-chat","name":"DeepSeek","policy":"kept 30 days"}],"network":[{"host":"api.deepseek.com","route":"direct","requests":1,"bytesSent":1843,"bytesReceived":2210}],"redacted":{},"cascade":{"role":"cloud","policy":"ASK","verdict":"WEAK","reasons":["dont-know"],"action":"ESCALATED","trigger":"asked"}}""")
            add(SessionEvent.TurnEnd(2, TurnEndReason.COMPLETED))

            add(SessionEvent.TurnStart(3))
            add(SessionEvent.UserMessage("Is it raining in Zurich right now?"))
            add(SessionEvent.AssistantMessage(3, 1, "Zurich in October is often grey, 8–14 °C, with rain about one day in three. Check a live forecast for right now.", model = PHONE_MODEL))
            raw("""{"type":"turn/receipt","turn":3,"models":$onPhone,"network":[],"cascade":{"role":"local","policy":"ASK","verdict":"UNSURE","reasons":["fresh"],"action":"OFFERED","judge":"self-check","judgeMs":412}}""")
            add(SessionEvent.TurnEnd(3, TurnEndReason.COMPLETED))
        }
    }

    /** The morning brief template switched on, with a finished run whose chat sits in the Recipes folder. */
    private suspend fun seedRecipe(now: Long) {
        val at = now - 95 * 60_000
        val zone = TimeZone.currentSystemDefault()
        val title = RecipeCopy.chatTitle("Morning brief from my feeds", at, zone)
        val chat = "demo-chat-brief"
        seed(chat, title, LocalProviderSync.LOCAL_PROVIDER_ID, PHONE_MODEL, now, minutesAgo = 95, tools = true) {
            add(SessionEvent.TurnStart(1))
            add(SessionEvent.UserMessage("Fetch each feed below with web_fetch and write a short morning brief."))
            add(
                SessionEvent.AssistantMessage(
                    1, 1,
                    "- Qualcomm details the Hexagon NPU in the 8 Elite Gen 5 (Ars Technica)\n- EU agrees on the AI Act's code of practice (The Guardian)\n- Show HN: a local-first notes app (Hacker News)",
                    model = PHONE_MODEL,
                ),
            )
            add(SessionEvent.TurnEnd(1, TurnEndReason.COMPLETED))
        }
        conversations.createFolder(RecipeCopy.FOLDER)?.let { conversations.moveToFolder(listOf(chat), it.id) }
        val brief = recipes.all().firstOrNull { it.template == RecipeTemplates.MORNING_BRIEF } ?: return
        val run = RecipeRunRecord(
            startedAt = at, finishedAt = at + 140_000, status = RecipeRunStatus.DONE, sessionId = chat,
            summary = "Qualcomm details the Hexagon NPU in the 8 Elite Gen 5 (Ars Technica)", hosts = 3, bytesSent = 2_400,
        )
        val enabled = RecipePlanner.saved(brief.copy(enabled = true), previous = null, nowMillis = now, zone = zone)
        recipes.save(enabled.copy(state = enabled.state.copy(lastRun = run)))
    }

    private suspend fun seedGallery(now: Long) {
        val prompts = listOf("Isometric greenhouse, riso print", "Lighthouse on a cliff at dusk", "Portrait of a robot, halftone", "Red tram in fog")
        prompts.forEachIndexed { i, prompt ->
            gallery.save(
                GalleryItem(
                    id = if (i == 0) DebugLaunch.DEMO_IMAGE else gallery.newId(),
                    createdAt = now - (i + 1) * 900_000L, mode = "TXT2IMG", prompt = prompt, negativePrompt = "lowres, blurry",
                    width = 512, height = 512, seed = 4182L + i, steps = 20, cfgScale = 7f, sampler = "DPM++ 2M · Karras",
                    engine = if (i == 0) "This phone" else "Forge · desktop", model = "sd 1.5", durationMs = 9_400L + i * 1_300L, paramsJson = "{}",
                ),
                image = render(i, 512),
                thumbnail = render(i, 256),
            )
        }
    }

    /** A soft abstract image (a gradient, a disc and a dark band), as iOS draws it. */
    private fun render(seed: Int, side: Int): ByteArray {
        val palettes = listOf(
            Color.rgb(237, 115, 77) to Color.rgb(38, 46, 56),
            Color.rgb(242, 199, 140) to Color.rgb(51, 64, 102),
            Color.rgb(204, 209, 217) to Color.rgb(26, 26, 31),
            Color.rgb(217, 64, 51) to Color.rgb(191, 191, 184),
        )
        val (from, to) = palettes[seed % palettes.size]
        val s = side.toFloat()
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, s, s, Paint().apply { shader = LinearGradient(0f, 0f, s, s, from, to, Shader.TileMode.CLAMP) })
        val r = s * (0.18f + 0.05f * seed)
        canvas.drawCircle(s * 0.55f, s * 0.35f, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 255, 255, 255) })
        canvas.drawRect(0f, s * 0.72f, s, s, Paint().apply { color = Color.argb(115, 0, 0, 0) })
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private companion object {
        const val DEMO_DEEPSEEK = "demo-deepseek"
        const val DEMO_OLLAMA = "demo-ollama"
        const val PHONE_MODEL = "catalog:qwen3-4b-2507-q4_0"
    }
}
