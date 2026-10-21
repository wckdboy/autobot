import AutobotShared
import Darwin
import SwiftUI
import Testing
import UIKit
@testable import Autobot

/**
 Scrolling and idle cost of the list screens (Models, Sessions, Studio Library, Imagine) and of
 screens hidden under the Studio, measured in the simulator: process and main-thread CPU per
 second, 60 Hz frame pacing (hitches = frames later than 1.5 intervals) and RenderProbe counts per
 second (screen/row body evaluations, thumbnail decodes).
 Skipped unless `AUTOBOT_PERF_DIR` is set (`TEST_RUNNER_AUTOBOT_PERF_DIR=…`); writes screens.json.
 */
@MainActor
struct ScreenPerfTests {
    struct Result: Codable {
        var scenario: String
        var seconds: Double
        var cpuMsPerSec: Double
        var mainCpuMsPerSec: Double
        var frames: Int
        var hitches: Int
        var counts: [String: Double]
    }

    @Test(.timeLimit(.minutes(5))) func measureListScreens() async throws {
        guard let dir = ProcessInfo.processInfo.environment["AUTOBOT_PERF_DIR"] else { return }
        let app = AppContainer()
        await app.unlockData()
        let nav = Navigator()
        var results: [Result] = []

        var conversations: [String] = []
        for i in 0 ..< 80 {
            var c = app.conversations.create(incognito: true, providerId: nil, model: "qwen3")
            c.title = "Conversation \(i) · " + String(repeating: "notes ", count: i % 5 + 1)
            app.conversations.update(c)
            conversations.append(c.id)
        }
        var models: [String] = []
        for i in 0 ..< 12 {
            let plan = ModelPlan(
                id: "perf:screen-\(i)", kind: i % 2 == 0 ? .chat : .image, source: .catalog, title: "Perf model \(i)", subtitle: "Q4_0",
                format: .gguf, engine: .llama, license: "apache-2.0",
                manifest: ModelManifest(files: [ModelFile(role: .model, name: "s\(i).gguf", url: "https://example.invalid/s\(i).gguf", sizeBytes: 1_048_576 * Int64(100 + i))]),
            )
            app.library.add(plan)
            app.library.progress(plan.id, downloaded: 1_048_576 * Int64(30 + i * 5), status: i == 1 ? .paused : .ready)
            models.append(plan.id)
        }
        var assets: [String] = []
        let recipe = Recipe(engine: "import", backendId: nil, model: nil, params: [:], inputs: [], workflowId: nil, workflowVersion: nil, seed: nil, output: .image)
        for i in 0 ..< 60 {
            let png = try #require(StudioTests.image(512, 512, hue: CGFloat(i % 12) / 12).pngData())
            if let a = try? await app.studio.addAsset(png, type: .image, mime: "image/png", recipe: recipe, width: 512, height: 512, label: "perf \(i)") {
                assets.append(a.id)
            }
        }
        defer {
            for id in models { app.library.remove(id) }
            for id in assets { app.studio.deleteAsset(id) }
        }

        results.append(try await measure("models-scroll", ModelsScreen(), app, nav, seconds: 6, scroll: true))
        results.append(try await measure("sessions-scroll", ChatListScreen(), app, nav, seconds: 6, scroll: true))
        let session = StudioSession(app: app)
        results.append(try await measure("library-scroll", LibraryRoom(session: session), app, nav, seconds: 6, scroll: true))
        results.append(try await measure("imagine-idle", ImagineScreen(), app, nav, seconds: 3))
        // The app's Run tab under an open Studio (its living cards must not keep ticking).
        nav.reset(.run, [])
        nav.studio = .workflows
        results.append(try await measure("run-under-studio", AutobotNavHost(), app, nav, seconds: 3))
        nav.studio = nil

        try JSONEncoder().encode(results).write(to: URL(fileURLWithPath: "\(dir)/screens.json"))
        for r in results {
            print(String(format: "PERF %@ cpu=%.0fms/s main=%.0fms/s frames=%d hitches=%d", r.scenario, r.cpuMsPerSec, r.mainCpuMsPerSec, r.frames, r.hitches),
                  r.counts.sorted { $0.key < $1.key }.map { String(format: "%@=%.1f", $0.key, $0.value) }.joined(separator: " "))
        }
    }

    // MARK: Harness

    private func measure(_ name: String, _ view: some View, _ app: AppContainer, _ nav: Navigator, seconds: Double, scroll: Bool = false) async throws -> Result {
        let scene = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let others = scene.windows.filter { !$0.isHidden }
        others.forEach { $0.isHidden = true }
        defer { others.forEach { $0.isHidden = false } }
        let window = UIWindow(windowScene: scene)
        window.overrideUserInterfaceStyle = .dark
        let root = view.background(Color.black).environment(\.scenePhase, .active).environment(app).environment(nav).autobotTheme(.dark)
        window.rootViewController = UIHostingController(rootView: root)
        window.makeKeyAndVisible()
        try await Task.sleep(for: .milliseconds(1500))

        let scrollView = scroll ? Self.scrollView(in: window) : nil
        let pacer = Pacer()
        RenderProbe.reset()
        let cpu0 = Self.processCpu(), main0 = Self.mainThreadCpu()
        let start = CACurrentMediaTime()
        pacer.onFrame = { now in
            guard let scrollView else { return }
            let range = max(scrollView.contentSize.height - scrollView.bounds.height, 0)
            let p = 0.5 - 0.5 * cos((now - start) * .pi / 1.5)
            scrollView.setContentOffset(CGPoint(x: 0, y: CGFloat(p) * range - scrollView.adjustedContentInset.top), animated: false)
        }
        pacer.start()
        while CACurrentMediaTime() - start < seconds { try await Task.sleep(for: .milliseconds(10)) }
        pacer.stop()
        let wall = CACurrentMediaTime() - start
        let cpu = Self.processCpu() - cpu0, main = Self.mainThreadCpu() - main0
        let counts = RenderProbe.snapshot().mapValues { Double($0) / wall }
        window.isHidden = true
        window.rootViewController = nil
        return Result(scenario: name, seconds: wall, cpuMsPerSec: cpu * 1000 / wall, mainCpuMsPerSec: main * 1000 / wall,
                      frames: pacer.frames, hitches: pacer.hitches, counts: counts)
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

@MainActor
private final class Pacer: NSObject {
    var onFrame: ((Double) -> Void)?
    private(set) var frames = 0
    private(set) var hitches = 0
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
            frames += 1
            if now - last > 1.5 / 60 { hitches += 1 }
        }
        last = now
        onFrame?(now)
    }
}
