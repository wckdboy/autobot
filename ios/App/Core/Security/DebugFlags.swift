import Foundation

/**
 Debug-build switches for UI work (Android's debug-only `allow_screenshots` extra):
 `-AutobotScreenshots` skips the app lock and the app-switcher privacy cover, `-AutobotDemo` fills
 an empty install with sample data, `-AutobotSkipWelcome` starts past the first-run welcome. Release builds always return `false`.
 */
enum DebugFlags {
    /// Read once (the root view asks on every evaluation).
    static let screenshots: Bool = {
        #if DEBUG
        ProcessInfo.processInfo.arguments.contains("-AutobotScreenshots")
        #else
        false
        #endif
    }()

    static var demo: Bool {
        #if DEBUG
        ProcessInfo.processInfo.arguments.contains("-AutobotDemo")
        #else
        false
        #endif
    }

    static var skipWelcome: Bool {
        #if DEBUG
        ProcessInfo.processInfo.arguments.contains("-AutobotSkipWelcome")
        #else
        false
        #endif
    }
}
