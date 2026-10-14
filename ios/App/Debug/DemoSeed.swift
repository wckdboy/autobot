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
        guard DebugFlags.demo, app.conversations.conversations.isEmpty, app.providers.providers.isEmpty else { return }
        let now = nowMillis()
        app.settings.setOnboarded()

        _ = try? app.providers.save(Provider(id: "demo-deepseek", kind: .deepseek, displayName: "DeepSeek", baseUrl: ProviderPresets.deepSeekBaseUrl, defaultModel: "deepseek-chat"))
        _ = try? app.providers.save(Provider(id: "demo-ollama", kind: .ollama, displayName: "Ollama (local)", baseUrl: ProviderPresets.ollamaBaseUrl, defaultModel: "qwen3", routing: .direct))

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
                                    text: "Use `reversed()` for a lazy reversed view, or `reverse()` to reverse an array in place.\n\n```swift\nvar xs = [1, 2, 3]\nlet ys = Array(xs.reversed())\nxs.reverse()\n```\n\nBoth run in O(n).",
                                    reasoning: "The user wants the idiomatic way. Mention reversed() vs reverse(), show both.",
                                    usage: TokenUsage(inputTokens: 412, outputTokens: 96, totalTokens: 508), model: "qwen3")),
            .turnEnd(turn: 0, reason: .completed, detail: nil),
        ])
        seed("Summarize notes.md into a plan", provider: "demo-deepseek", model: "deepseek-chat", minutesAgo: 40, tools: true, [
            .turnStart(turn: 0),
            .userMessage(text: "Summarize notes.md into a plan"),
            .requestContext(provider: "demo-deepseek", model: "deepseek-chat", contextWindow: 128_000),
            .assistantMessage(.init(turn: 0, step: 0, text: "", toolCalls: [ToolCallRecord(id: "c1", name: "read", arguments: #"{"path":"notes.md"}"#)],
                                    usage: TokenUsage(inputTokens: 1_840, outputTokens: 22), model: "deepseek-chat")),
            .toolCall(callId: "c1", name: "read", arguments: #"{"path":"notes.md"}"#),
            .toolResult(.init(callId: "c1", name: "read", content: "# Notes\n- ship iOS port\n- verify NPU path\n- write README")),
            .todoWrite(todos: [TodoItem(content: "Group notes by theme", status: .completed),
                               TodoItem(content: "Draft the plan", status: .inProgress),
                               TodoItem(content: "Write plan.md", status: .pending)]),
            .assistantMessage(.init(turn: 0, step: 1, text: "", toolCalls: [ToolCallRecord(id: "c2", name: "write", arguments: #"{"path":"plan.md"}"#)], model: "deepseek-chat")),
            .toolCall(callId: "c2", name: "write", arguments: #"{"path":"plan.md"}"#),
            .toolResult(.init(callId: "c2", name: "write", content: "wrote 412 bytes")),
            .assistantMessage(.init(turn: 0, step: 2, text: "Done. `plan.md` has three milestones: ship the iOS port, verify the NPU path on hardware, then write the README.",
                                    usage: TokenUsage(inputTokens: 2_310, outputTokens: 64), model: "deepseek-chat")),
            .turnEnd(turn: 0, reason: .completed, detail: nil),
        ])
        seed("Private question", provider: "demo-ollama", model: "qwen3", minutesAgo: 180, tools: false, [
            .turnStart(turn: 0),
            .userMessage(text: "Private question"),
            .assistantMessage(.init(turn: 0, step: 0, text: "Sure — nothing here leaves this phone.", model: "qwen3")),
            .turnEnd(turn: 0, reason: .completed, detail: nil),
        ])

        let prompts = ["Isometric greenhouse, riso print", "Lighthouse on a cliff at dusk", "Portrait of a robot, halftone", "Red tram in fog"]
        Task {
            for (i, prompt) in prompts.enumerated() {
                guard let png = render(seed: i), let thumb = render(seed: i, side: 256) else { continue }
                let request = DiffusionRequest()
                let params = (try? JSONEncoder.store.encode(request)).map { String(decoding: $0, as: UTF8.self) } ?? "{}"
                try? await app.gallery.save(GalleryItem(
                    id: app.gallery.newId(), createdAt: now - Int64(i + 1) * 900_000, mode: "TXT2IMG", prompt: prompt, negativePrompt: "lowres, blurry",
                    width: 512, height: 512, seed: 4182 + Int64(i), steps: 20, cfgScale: 7, sampler: "DPM++ 2M · Karras",
                    engine: i == 0 ? "This phone" : "Forge · desktop", model: "sd 1.5", durationMs: 9_400 + Int64(i) * 1_300, paramsJson: params,
                ), image: png, thumbnail: thumb)
            }
        }

        app.auditLog.record(method: "POST", host: "api.deepseek.com", path: "/chat/completions", tag: "DeepSeek", route: "offline", blocked: true, error: "Offline mode: network access is disabled")
        app.auditLog.record(method: "GET", host: "127.0.0.1", path: "/v1/models", bytesReceived: 1_204, status: 200, tag: "Ollama (local)", route: "loopback", blocked: false)
        app.auditLog.record(method: "GET", host: "huggingface.co", path: "/api/models", bytesReceived: 18_330, status: 200, tag: "models:huggingface", route: "tor", blocked: false)
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
