import Testing
@testable import Autobot

/// The Compute screen's report uses the shared Kotlin renderer and leaks no identifiers.
@MainActor
struct DiagnosticsReportTests {
    @Test func rendersSharedReportWithoutPathsOrIds() throws {
        let app = AppContainer()
        var devices = ComputeDevices()
        devices.engineDevices = [LocalDevice(name: "MTL0", description: "Apple GPU", kind: "gpu", memoryTotalBytes: 8 << 30)]
        let text = try #require(DiagnosticsReportBuilder.render(app: app, devices: devices))
        #expect(text.hasPrefix("## Autobot "))
        #expect(text.contains("os: iOS "))
        #expect(text.contains("### engine devices"))
        #expect(text.contains("- gpu: MTL0 — Apple GPU · 8.0 GB"))
        #expect(text.contains("### image accelerator"))
        #expect(text.contains("Core ML ANE: "))
        #expect(text.contains("network: Offline"))
        #expect(!text.contains("/Users/") && !text.contains("/var/") && !text.contains("Application Support"))
    }
}
