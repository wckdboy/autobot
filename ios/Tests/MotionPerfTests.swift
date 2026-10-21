import Darwin
import SwiftUI
import Testing
import UIKit
@testable import Autobot

/**
 The render budget on real screens, measured in the simulator: process and main-thread CPU per
 second, frame pacing from a 60 Hz CADisplayLink (hitches = frames later than 1.5 intervals) and
 screen body evaluations per second (RenderProbe). Scenarios: a long agent transcript streaming,
 the transcript / chat list / models list scrolling, Run, the empty gallery and Image idle, and a push.
 Skipped unless `AUTOBOT_PERF_DIR` is set (`TEST_RUNNER_AUTOBOT_PERF_DIR=…`); writes perf.json.
 */
@MainActor
struct MotionPerfTests {
    struct Result: Codable {
        var scenario: String
        var seconds: Double
        var cpuMsPerSec: Double
        var mainCpuMsPerSec: Double
        var frames: Int
        var hitches: Int
        var worstFrameMs: Double
        var counts: [String: Double]
    }

    @Test(.timeLimit(.minutes(5))) func measureScreens() async throws {
        guard let dir = ProcessInfo.processInfo.environment["AUTOBOT_PERF_DIR"] else { return }
        let app = AppContainer()
        let nav = Navigator()
        var results: [Result] = []

        // A long agent transcript: 30 exchanges with markdown, code and tool steps.
        var long = app.conversations.create(incognito: true, providerId: nil, model: "deepseek-chat")
        long.title = "Long transcript"
        app.conversations.update(long)
        let base = Self.longState(long.id)
        app.agent.preview(long.id, state: base)
        for i in 0 ..< 80 {
            var c = app.conversations.create(incognito: true, providerId: nil, model: "qwen3")
            c.title = "Conversation \(i) · " + String(repeating: "notes ", count: i % 5 + 1)
            app.conversations.update(c)
        }
        for i in 0 ..< 8 {
            let plan = ModelPlan(
                id: "perf:model-\(i)", kind: i % 2 == 0 ? .chat : .image, source: .catalog, title: "Perf model \(i)", subtitle: "Q4_0",
                format: .gguf, engine: .llama, license: "apache-2.0",
                manifest: ModelManifest(files: [ModelFile(role: .model, name: "m\(i).gguf", url: "https://example.invalid/m\(i).gguf", sizeBytes: 1_048_576 * Int64(100 + i))],
                                        recommended: Recommended(contextLength: 4096)),
            )
            app.library.add(plan)
            app.library.progress(plan.id, downloaded: 1_048_576 * Int64(30 + i * 5), status: i == 1 ? .paused : i == 3 ? .failed : .ready)
        }

        // Streaming: the live text grows every 50 ms (20 tokens/s), a tool step runs.
        results.append(try await measure("chat-stream", ChatScreen(conversationId: long.id), app, nav, seconds: 4) { t in
            var s = base
            s.live = LiveState(text: String(repeating: "Streaming a long answer with **markdown** and `code`. ", count: Int(t * 20) / 6 + 1), reasoning: "", toolNames: [], tokensPerSecond: 20)
            app.agent.preview(long.id, state: s)
        })
        app.agent.preview(long.id, state: base)
        results.append(try await measure("chat-scroll", ChatScreen(conversationId: long.id), app, nav, seconds: 3, scroll: true))
        results.append(try await measure("chat-list-scroll", ChatListScreen(), app, nav, seconds: 3, scroll: true))
        results.append(try await measure("models-scroll", ModelsScreen(), app, nav, seconds: 3, scroll: true))
        results.append(try await measure("run-idle", RunScreen(), app, nav, seconds: 3))
        results.append(try await measure("gallery-idle", GalleryScreen(openId: nil), app, nav, seconds: 3))
        results.append(try await measure("imagine-idle", ImagineScreen(), app, nav, seconds: 3))
        // A push from the chat list into the long transcript, then back.
        nav.reset(.run, [.chatList])
        var pushed = false
        results.append(try await measure("push-chat", AutobotNavHost(), app, nav, seconds: 1.5) { t in
            if t > 0.2 && !pushed {
                pushed = true
                nav.push(.chat(conversationId: long.id))
            }
        })
        nav.reset(.run, [])

        let data = try JSONEncoder().encode(results)
        try data.write(to: URL(fileURLWithPath: "\(dir)/perf.json"))
        for r in results {
            print(String(format: "PERF %@ cpu=%.0fms/s main=%.0fms/s frames=%d hitches=%d worst=%.1fms", r.scenario, r.cpuMsPerSec, r.mainCpuMsPerSec, r.frames, r.hitches, r.worstFrameMs), r.counts)
        }
    }

    static func longState(_ id: String) -> ChatSessionState {
        var s = ChatSessionState(sessionId: id)
        s.toolsEnabled = true
        s.running = true
        var items: [SessionItem] = []
        for i in 0 ..< 30 {
            items.append(.user(key: "u\(i)", text: "Question \(i): how should the plan for day \(i % 3 + 1) look?", images: []))
            items.append(.assistant(key: "a\(i)", text: "Here is **step \(i)**.\n\n- visit the castle\n- walk to `Alfama`\n- dinner by the river\n\n```swift\nlet day = \(i)\nprint(day)\n```\n\nThat keeps the walking short.", reasoning: i % 4 == 0 ? "Keep it short." : nil, interrupted: false, outputTokens: 80, model: "deepseek-chat"))
            if i % 5 == 0 {
                items.append(.tool(.init(key: "t\(i)", callId: "c\(i)", name: "read", summary: "notes/day\(i).md", state: i == 25 ? .running : .ok, output: "ok", code: nil, galleryIds: [], durationMs: 120)))
            }
        }
        s.projection = SessionProjection(items: items, lastUsage: TokenUsage(inputTokens: 11_800, outputTokens: 420), contextWindow: 128_000)
        s.provider = "p"
        s.model = "deepseek-chat"
        return s
    }

    // MARK: Harness

    private func measure(_ name: String, _ view: some View, _ app: AppContainer, _ nav: Navigator, seconds: Double, scroll: Bool = false, step: ((Double) -> Void)? = nil) async throws -> Result {
        let scene = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let others = scene.windows.filter { !$0.isHidden }
        others.forEach { $0.isHidden = true }
        defer { others.forEach { $0.isHidden = false } }
        let window = UIWindow(windowScene: scene)
        window.overrideUserInterfaceStyle = .dark
        let root = view.background(Color.black).environment(\.scenePhase, .active).environment(app).environment(nav).autobotTheme(.dark)
        window.rootViewController = UIHostingController(rootView: root)
        window.makeKeyAndVisible()
        // Settle: first layout, lazy rows, entry animations.
        try await Task.sleep(for: .milliseconds(1200))

        let scrollView = scroll ? Self.scrollView(in: window) : nil
        let pacer = FramePacer()
        RenderProbe.reset()
        let cpu0 = Self.processCpu(), main0 = Self.mainThreadCpu()
        let start = CACurrentMediaTime()
        pacer.onFrame = { now in
            let t = now - start
            if let scrollView {
                let range = max(scrollView.contentSize.height - scrollView.bounds.height, 0)
                // Up and down the whole list, ~1.5 s per pass.
                let p = 0.5 - 0.5 * cos(t * .pi / 1.5)
                scrollView.setContentOffset(CGPoint(x: 0, y: CGFloat(p) * range - scrollView.adjustedContentInset.top), animated: false)
            }
        }
        pacer.start()
        var lastStep = -1.0
        while CACurrentMediaTime() - start < seconds {
            let t = CACurrentMediaTime() - start
            if let step, t - lastStep >= 0.05 {
                step(t)
                lastStep = t
            }
            try await Task.sleep(for: .milliseconds(10))
        }
        pacer.stop()
        let wall = CACurrentMediaTime() - start
        let cpu = Self.processCpu() - cpu0, main = Self.mainThreadCpu() - main0
        let counts = RenderProbe.snapshot().mapValues { Double($0) / wall }
        window.isHidden = true
        window.rootViewController = nil
        return Result(scenario: name, seconds: wall, cpuMsPerSec: cpu * 1000 / wall, mainCpuMsPerSec: main * 1000 / wall,
                      frames: pacer.frames, hitches: pacer.hitches, worstFrameMs: pacer.worst * 1000, counts: counts)
    }

    private static func scrollView(in view: UIView) -> UIScrollView? {
        var best: UIScrollView?
        var queue = [view]
        while !queue.isEmpty {
            let v = queue.removeFirst()
            if let s = v as? UIScrollView, s.contentSize.height > s.bounds.height + 50, !(s is UITextView) {
                if best == nil || s.contentSize.height > best!.contentSize.height { best = s }
            }
            queue.append(contentsOf: v.subviews)
        }
        return best
    }

    private static func processCpu() -> Double {
        var usage = rusage()
        getrusage(RUSAGE_SELF, &usage)
        func s(_ t: timeval) -> Double { Double(t.tv_sec) + Double(t.tv_usec) / 1e6 }
        return s(usage.ru_utime) + s(usage.ru_stime)
    }

    /// CPU time of the calling (main) thread.
    private static func mainThreadCpu() -> Double {
        let thread = mach_thread_self()
        defer { mach_port_deallocate(mach_task_self_, thread) }
        var info = thread_basic_info()
        var count = mach_msg_type_number_t(MemoryLayout<thread_basic_info>.size / MemoryLayout<integer_t>.size)
        let kr = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { thread_info(thread, thread_flavor_t(THREAD_BASIC_INFO), $0, &count) }
        }
        guard kr == KERN_SUCCESS else { return 0 }
        return Double(info.user_time.seconds) + Double(info.user_time.microseconds) / 1e6
            + Double(info.system_time.seconds) + Double(info.system_time.microseconds) / 1e6
    }
}

/// Frame pacing at 60 Hz: frames delivered, hitches (later than 1.5 intervals) and the worst gap.
@MainActor
private final class FramePacer: NSObject {
    var onFrame: ((Double) -> Void)?
    private(set) var frames = 0
    private(set) var hitches = 0
    private(set) var worst = 0.0
    private var last: Double?
    private var link: CADisplayLink?

    func start() {
        let link = CADisplayLink(target: self, selector: #selector(tick(_:)))
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 60, maximum: 60, preferred: 60)
        link.add(to: .main, forMode: .common)
        self.link = link
    }

    func stop() {
        link?.invalidate()
        link = nil
    }

    @objc private func tick(_ link: CADisplayLink) {
        let now = link.timestamp
        if let last {
            let gap = now - last
            frames += 1
            worst = max(worst, gap)
            if gap > 1.5 / 60 { hitches += 1 }
        }
        last = now
        onFrame?(now)
    }
}
