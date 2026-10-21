#if DEBUG
import Foundation
import UIKit

/**
 Sample data for screenshots and UI work (`-AutobotDemo`, Debug builds only): providers, a chat
 with reasoning and code, an agent run with tool steps, a few gallery images, an installed model,
 a PC backend and some audit-log entries. Runs once, on an empty install.
 */
@MainActor
enum DemoSeed {
    static func seedIfEmpty(_ app: AppContainer) {
        guard DebugFlags.demo else { return }
        // The audit log lives in memory only: every demo launch gets its entries.
        recordAudit(app)
        guard app.conversations.conversations.isEmpty, app.providers.providers.isEmpty else { return }
        let now = nowMillis()
        app.settings.setOnboarded()

        _ = try? app.providers.save(Provider(id: "demo-deepseek", kind: .deepseek, displayName: "DeepSeek", baseUrl: ProviderPresets.deepSeekBaseUrl, defaultModel: "deepseek-flash", presetId: "deepseek"))
        _ = try? app.providers.save(Provider(id: "demo-ollama", kind: .ollama, displayName: "Ollama", baseUrl: ProviderPresets.ollamaBaseUrl, defaultModel: "qwen3:8b", routing: .direct, presetId: "ollama"))
        // A paired Invincible node, so Remote shows the node's agent row (its orange mark) and its
        // three sample bots (BotsDemo: default, ops with Slack down, support on email).
        _ = try? app.providers.save(Provider(id: BotsDemo.nodeId, kind: .invincibleNode, displayName: "studio-mac", baseUrl: "http://192.168.1.20:8787/v1", defaultModel: "qwen3:8b", routing: .direct))

        let plan = ModelPlan(
            id: "catalog:qwen3-4b-2507-q4_0", kind: .chat, source: .catalog, title: "Qwen3 4B Instruct 2507", subtitle: "Q4_0 · strong tool calling",
            format: .gguf, engine: .llama, license: "apache-2.0",
            manifest: ModelManifest(files: [ModelFile(role: .model, name: "Qwen3-4B-Instruct-2507-Q4_0.gguf",
                                                      url: "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_0.gguf",
                                                      sizeBytes: 2266 * 1_048_576, auth: .huggingFace)],
                                    recommended: Recommended(contextLength: 16384)),
        )
        app.library.add(plan)
        app.library.progress(plan.id, downloaded: plan.totalBytes, status: .ready)
        app.backends.save(DiffusionBackend(id: "demo-forge", kind: .sdApi, name: "Forge · desktop", baseUrl: "https://studio.tail1234.ts.net"))

        let sessions = SessionStore(store: app.store)
        func seed(_ title: String, provider: String, model: String, minutesAgo: Int64, tools: Bool, _ events: [SessionEvent]) {
            var c = app.conversations.create(incognito: false, providerId: provider, model: model, title: title)
            c.updatedAt = now - minutesAgo * 60_000
            app.conversations.update(c)
            var events = events
            if !tools { events.insert(.agentSettingsChanged(toolsEnabled: false), at: 0) }
            let base = now - minutesAgo * 60_000
            try? sessions.append(c.id, events.enumerated().map { LogEntry(seq: Int64($0.offset), time: base + Int64($0.offset) * 700, event: $0.element) })
        }

        seed("Reverse a list in Swift", provider: "demo-ollama", model: "qwen3", minutesAgo: 3, tools: false, [
            .turnStart(turn: 0),
            .userMessage(text: "How do I reverse a list in Swift?"),
            .requestContext(provider: "demo-ollama", model: "qwen3", contextWindow: 32_768),
            .assistantMessage(.init(turn: 0, step: 0,
                                    text: "Use `reversed()` for a lazy reversed view, or `reverse()` to reverse an array in place.\n\n```swift\nvar xs = [1, 2, 3]\nlet ys = Array(xs.reversed())\nxs.reverse()\n```\n\n| Call | Returns | Cost |\n|:-----|:--------|-----:|\n| `reversed()` | a lazy view | O(1) |\n| `reverse()` | nothing, in place | O(n) |\n\nCopying the view into an array is O(n).",
                                    reasoning: "The user wants the idiomatic way. Mention reversed() vs reverse(), show both.",
                                    usage: TokenUsage(inputTokens: 412, outputTokens: 96, totalTokens: 508), model: "qwen3")),
            .turnEnd(turn: 0, reason: .completed, detail: nil),
        ])
        seed("Summarize notes.md into a plan", provider: "demo-deepseek", model: "deepseek-chat", minutesAgo: 40, tools: true, [
            .turnStart(turn: 0),
            .userMessage(text: "Summarize notes.md into a plan"),
            .requestContext(provider: "demo-deepseek", model: "deepseek-chat", contextWindow: 128_000),
            .assistantMessage(.init(turn: 0, step: 0, text: "", toolCalls: [ToolCallRecord(id: "c1", name: "read", arguments: #"{"file_path":"notes.md"}"#)],
                                    usage: TokenUsage(inputTokens: 1_840, outputTokens: 22), model: "deepseek-chat")),
            .toolCall(callId: "c1", name: "read", arguments: #"{"file_path":"notes.md"}"#),
            .toolResult(.init(callId: "c1", name: "read", content: "# Notes\n- ship iOS port\n- verify NPU path\n- write README")),
            .todoWrite(todos: [TodoItem(content: "Group notes by theme", status: .completed),
                               TodoItem(content: "Draft the plan", status: .inProgress),
                               TodoItem(content: "Write plan.md", status: .pending)]),
            .assistantMessage(.init(turn: 0, step: 1, text: "", toolCalls: [ToolCallRecord(id: "c2", name: "write", arguments: #"{"file_path":"plan.md","content":"1. Ship the iOS port\n2. Verify the NPU path\n3. Write the README\n"}"#)], model: "deepseek-chat")),
            .toolCall(callId: "c2", name: "write", arguments: #"{"file_path":"plan.md","content":"1. Ship the iOS port\n2. Verify the NPU path\n3. Write the README\n"}"#),
            .toolResult(.init(callId: "c2", name: "write", content: "wrote 412 bytes")),
            .assistantMessage(.init(turn: 0, step: 2, text: "", toolCalls: [ToolCallRecord(id: "c3", name: "generate_image", arguments: #"{"prompt":"isometric greenhouse, riso print"}"#)], model: "deepseek-chat")),
            .toolCall(callId: "c3", name: "generate_image", arguments: #"{"prompt":"isometric greenhouse, riso print"}"#),
            .toolResult(.init(callId: "c3", name: "generate_image", content: "1 image saved to the gallery",
                              meta: .object(["gallery": .array([.string("demo-image-0")])]))),
            .assistantMessage(.init(turn: 0, step: 3, text: "Done. `plan.md` has three milestones: ship the iOS port, verify the NPU path on hardware, then write the README.",
                                    usage: TokenUsage(inputTokens: 2_310, outputTokens: 64), model: "deepseek-chat")),
            .turnEnd(turn: 0, reason: .completed, detail: nil),
        ])
        seed("Private question", provider: "demo-ollama", model: "qwen3", minutesAgo: 180, tools: false, [
            .turnStart(turn: 0),
            .userMessage(text: "Private question"),
            .assistantMessage(.init(turn: 0, step: 0, text: "Sure — nothing here leaves this phone.", model: "qwen3")),
            .turnEnd(turn: 0, reason: .completed, detail: nil),
        ])

        seedCascade(app, sessions: sessions, now: now)
        // Recipes: the three templates, and the morning brief switched on with a finished run.
        let briefAt = now - 95 * 60_000
        let briefTitle = RecipeRules.chatTitle("Morning brief from my feeds", at: briefAt)
        seed(briefTitle, provider: ProviderPresets.localProviderId, model: plan.id, minutesAgo: 95, tools: true, [
            .turnStart(turn: 1),
            .userMessage(text: "Fetch each feed below with web_fetch and write a short morning brief."),
            .assistantMessage(.init(turn: 1, step: 1,
                                    text: "- Qualcomm details the Hexagon NPU in the 8 Elite Gen 5 (Ars Technica)\n- EU agrees on the AI Act's code of practice (The Guardian)\n- Show HN: a local-first notes app (Hacker News)",
                                    model: plan.id)),
            .turnEnd(turn: 1, reason: .completed, detail: nil),
        ])
        if let chat = app.conversations.conversations.first(where: { $0.title == briefTitle }), let folder = app.conversations.createFolder(RecipeRules.copy.FOLDER) {
            app.conversations.move([chat.id], to: folder.id)
            let repository = RecipeRepository(store: app.store)
            if var brief = repository.load(now: now).first(where: { $0.template == "template-morning-brief" }) {
                brief.enabled = true
                brief = RecipeRules.saved(brief, previous: nil, now: now)
                var run = RecipeRunDoc(startedAt: briefAt, finishedAt: briefAt + 140_000, status: "DONE", sessionId: chat.id,
                                       summary: "Qualcomm details the Hexagon NPU in the 8 Elite Gen 5 (Ars Technica)")
                run.hosts = 3
                run.bytesSent = 2_400
                brief.state.lastRun = run
                try? repository.save(brief)
            }
        }

        let prompts = ["Isometric greenhouse, riso print", "Lighthouse on a cliff at dusk", "Portrait of a robot, halftone", "Red tram in fog"]
        Task {
            for (i, prompt) in prompts.enumerated() {
                guard let png = render(seed: i), let thumb = render(seed: i, side: 256) else { continue }
                let request = DiffusionRequest()
                let params = (try? JSONEncoder.store.encode(request)).map { String(decoding: $0, as: UTF8.self) } ?? "{}"
                try? await app.gallery.save(GalleryItem(
                    id: i == 0 ? "demo-image-0" : app.gallery.newId(), createdAt: now - Int64(i + 1) * 900_000, mode: "TXT2IMG", prompt: prompt, negativePrompt: "lowres, blurry",
                    width: 512, height: 512, seed: 4182 + Int64(i), steps: 20, cfgScale: 7, sampler: "DPM++ 2M · Karras",
                    engine: i == 0 ? "This phone" : "Forge · desktop", model: "sd 1.5", durationMs: 9_400 + Int64(i) * 1_300, paramsJson: params,
                ), image: png, thumbnail: thumb)
            }
        }

    }

    private static func recordAudit(_ app: AppContainer) {
        app.auditLog.record(method: "POST", host: "api.deepseek.com", path: "/chat/completions", tag: "DeepSeek", route: "offline", blocked: true, error: "Offline mode: network access is disabled")

        app.auditLog.record(method: "GET", host: "127.0.0.1", path: "/v1/models", bytesReceived: 1_204, status: 200, tag: "Ollama (local)", route: "loopback", blocked: false)
        app.auditLog.record(method: "GET", host: "huggingface.co", path: "/api/models", bytesReceived: 18_330, status: 200, tag: "models:huggingface", route: "tor", blocked: false)
    }

    /**
     "Phone first" (the local-first cascade) on a plain DeepSeek chat (`-AutobotOpen phone-first`
     opens it): the phone said it doesn't know, the user asked the cloud (follow-up under its
     notice), then the phone answered a weather question and the offer is still open. Receipts are
     written as the shared runtime writes them (the Swift event enum has no receipt case).
     */
    private static func seedCascade(_ app: AppContainer, sessions: SessionStore, now: Int64) {
        var c = app.conversations.create(incognito: false, providerId: "demo-deepseek", model: "deepseek-chat", title: "Who wrote Stiller?")
        c.updatedAt = now - 5 * 60_000
        app.conversations.update(c)
        let base = now - 5 * 60_000
        let phone = "catalog:qwen3-4b-2507-q4_0"
        var lines: [Data] = []
        func add(_ e: SessionEvent) {
            if let data = try? SessionJSON.encode(LogEntry(seq: Int64(lines.count), time: base + Int64(lines.count) * 700, event: e)) { lines.append(data) }
        }
        func raw(_ event: String) {
            lines.append(Data(#"{"seq":\#(lines.count),"time":\#(base + Int64(lines.count) * 700),"event":\#(event)}"#.utf8))
        }
        let onPhone = #"[{"provider":"local","model":"\#(phone)","name":"This phone","onDevice":true,"policy":"on device"}]"#
        add(.agentSettingsChanged(toolsEnabled: false))
        raw(#"{"type":"agent/cascade","policy":"ASK"}"#)

        add(.turnStart(turn: 1))
        add(.userMessage(text: "Who wrote the novel Stiller?"))
        add(.assistantMessage(.init(turn: 1, step: 1, text: "I don't know who wrote it. I can't place that title.", model: phone)))
        raw(#"{"type":"turn/receipt","turn":1,"models":\#(onPhone),"network":[],"cascade":{"role":"local","policy":"ASK","verdict":"WEAK","reasons":["dont-know"],"action":"OFFERED","judge":"heuristic","judgeMs":3}}"#)
        add(.turnEnd(turn: 1, reason: .completed, detail: nil))

        add(.turnStart(turn: 2))
        add(.userMessage(text: "asked", kind: .context, contextId: "cascade/escalate"))
        add(.requestContext(provider: "demo-deepseek", model: "deepseek-chat", contextWindow: 128_000))
        add(.assistantMessage(.init(turn: 2, step: 1, text: "*Stiller* (1954) is a novel by the Swiss writer **Max Frisch**. A man arrested at the border insists he is not the missing sculptor Anatol Stiller.",
                                    usage: TokenUsage(inputTokens: 318, outputTokens: 44), model: "deepseek-chat")))
        raw(#"{"type":"turn/receipt","turn":2,"models":[{"provider":"demo-deepseek","model":"deepseek-chat","name":"DeepSeek","policy":"kept 30 days"}],"network":[{"host":"api.deepseek.com","route":"direct","requests":1,"bytesSent":1843,"bytesReceived":2210}],"redacted":{},"cascade":{"role":"cloud","policy":"ASK","verdict":"WEAK","reasons":["dont-know"],"action":"ESCALATED","trigger":"asked"}}"#)
        add(.turnEnd(turn: 2, reason: .completed, detail: nil))

        add(.turnStart(turn: 3))
        add(.userMessage(text: "Is it raining in Zurich right now?"))
        add(.assistantMessage(.init(turn: 3, step: 1, text: "Zurich in October is often grey, 8–14 °C, with rain about one day in three. Check a live forecast for right now.", model: phone)))
        raw(#"{"type":"turn/receipt","turn":3,"models":\#(onPhone),"network":[],"cascade":{"role":"local","policy":"ASK","verdict":"UNSURE","reasons":["fresh"],"action":"OFFERED","judge":"self-check","judgeMs":412}}"#)
        add(.turnEnd(turn: 3, reason: .completed, detail: nil))
        try? sessions.appendJSON(c.id, lines.enumerated().map { (seq: Int64($0.offset), json: $0.element) })
    }

    /// A soft abstract image (gradients and a disc) so halftone previews have something to sample.
    private static func render(seed: Int, side: CGFloat = 512) -> Data? {
        let palettes: [[UIColor]] = [
            [.init(red: 0.93, green: 0.45, blue: 0.30, alpha: 1), .init(red: 0.15, green: 0.18, blue: 0.22, alpha: 1)],
            [.init(red: 0.95, green: 0.78, blue: 0.55, alpha: 1), .init(red: 0.20, green: 0.25, blue: 0.40, alpha: 1)],
            [.init(red: 0.80, green: 0.82, blue: 0.85, alpha: 1), .init(red: 0.10, green: 0.10, blue: 0.12, alpha: 1)],
            [.init(red: 0.85, green: 0.25, blue: 0.20, alpha: 1), .init(red: 0.75, green: 0.75, blue: 0.72, alpha: 1)],
        ]
        let colors = palettes[seed % palettes.count]
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: side, height: side))
        let image = renderer.image { ctx in
            let cg = ctx.cgContext
            let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: [colors[0].cgColor, colors[1].cgColor] as CFArray, locations: [0, 1])!
            cg.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: side, y: side), options: [])
            cg.setFillColor(UIColor(white: 1, alpha: 0.55).cgColor)
            let r = side * (0.18 + 0.05 * CGFloat(seed))
            cg.fillEllipse(in: CGRect(x: side * 0.55 - r, y: side * 0.35 - r, width: r * 2, height: r * 2))
            cg.setFillColor(UIColor(white: 0, alpha: 0.45).cgColor)
            cg.fill(CGRect(x: 0, y: side * 0.72, width: side, height: side * 0.28))
        }
        return image.pngData()
    }
}
#endif
