import Foundation
import os
import QuartzCore

/**
 Launch milestones, as `os_signpost` events (subsystem `ai.wckd.autobot`, category `launch`: see
 them in Instruments' os_signpost instrument) and, when started with `-AutobotLaunchTrace` (any
 build: it only records timings), as `Library/Caches/launch-trace.json` with each milestone's time since the
 process started and the main thread's CPU time so far (read by `ios/scripts/launch-bench.sh`).

 Milestones: `container.*` (object graph phases), `root.appear` (the first SwiftUI body is on
 screen), `firstFrame` (the first display refresh after it), `data.first` (conversations and
 providers published), `data.loaded` (every repository loaded), `interactive` (data loaded and
 the main thread idle again), `studio.loaded`.
 */
enum LaunchTrace {
    private static let signposter = OSSignposter(subsystem: "ai.wckd.autobot", category: "launch")

    static let enabled = ProcessInfo.processInfo.arguments.contains("-AutobotLaunchTrace")
    nonisolated(unsafe) private static var marks: [(String, Double, Double)] = []
    private static let lock = NSLock()

    /// Records milestone [name] (main thread or not; the CPU column is the calling thread's).
    static func mark(_ name: StaticString) {
        signposter.emitEvent(name)
        guard enabled else { return }
        let entry = ("\(name)", sinceProcessStart(), threadCpuMs())
        lock.lock()
        marks.append(entry)
        lock.unlock()
    }

    /// Marks `firstFrame` on the next display refresh, then `interactive` once [ready] holds and the
    /// main run loop has gone idle (checked on later refreshes).
    @MainActor static func watchFrames(ready: @escaping @MainActor () -> Bool) {
        guard enabled else { return }
        FrameWatcher.shared.start(ready: ready)
    }

    static func write() {
        lock.lock()
        let rows = marks
        lock.unlock()
        let json = "{" + rows.map { String(format: "\"%@\":{\"ms\":%.1f,\"cpuMs\":%.1f}", $0.0, $0.1, $0.2) }.joined(separator: ",") + "}"
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        try? Data(json.utf8).write(to: caches.appendingPathComponent("launch-trace.json"), options: .atomic)
    }

    /// Wall-clock milliseconds since the kernel started this process.
    private static func sinceProcessStart() -> Double {
        var info = kinfo_proc()
        var size = MemoryLayout<kinfo_proc>.stride
        var mib: [Int32] = [CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()]
        guard sysctl(&mib, 4, &info, &size, nil, 0) == 0 else { return -1 }
        let start = info.kp_proc.p_un.__p_starttime
        var now = timeval()
        gettimeofday(&now, nil)
        return Double(now.tv_sec - start.tv_sec) * 1000 + Double(now.tv_usec - start.tv_usec) / 1000
    }

    private static func threadCpuMs() -> Double {
        let thread = mach_thread_self()
        defer { mach_port_deallocate(mach_task_self_, thread) }
        var info = thread_basic_info()
        var count = mach_msg_type_number_t(MemoryLayout<thread_basic_info>.size / MemoryLayout<integer_t>.size)
        let kr = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { thread_info(thread, thread_flavor_t(THREAD_BASIC_INFO), $0, &count) }
        }
        guard kr == KERN_SUCCESS else { return -1 }
        return Double(info.user_time.seconds + info.system_time.seconds) * 1000
            + Double(info.user_time.microseconds + info.system_time.microseconds) / 1000
    }

    @MainActor private final class FrameWatcher: NSObject {
        static let shared = FrameWatcher()
        private var link: CADisplayLink?
        private var ready: (@MainActor () -> Bool)?
        private var framed = false
        private var last: CFTimeInterval = 0
        private var calm = 0

        func start(ready: @escaping @MainActor () -> Bool) {
            guard link == nil, !framed else { return }
            self.ready = ready
            let link = CADisplayLink(target: self, selector: #selector(tick(_:)))
            link.add(to: .main, forMode: .common)
            self.link = link
        }

        @objc private func tick(_ link: CADisplayLink) {
            if !framed {
                framed = true
                LaunchTrace.mark("firstFrame")
                last = link.timestamp
                return
            }
            // Interactive: data published and three refreshes in a row on time (no main-thread stall).
            let onTime = link.timestamp - last < 1.5 / 60
            last = link.timestamp
            guard ready?() == true else { calm = 0; return }
            calm = onTime ? calm + 1 : 0
            guard calm >= 3 else { return }
            LaunchTrace.mark("interactive")
            link.invalidate()
            self.link = nil
            // Late milestones (studio.loaded) land before the file is written.
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) { LaunchTrace.write() }
        }
    }
}
