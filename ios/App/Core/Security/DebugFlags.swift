import Foundation

/**
 Debug-build switches for UI work (Android's debug-only `allow_screenshots` extra):
 `-AutobotScreenshots` skips the app lock and the app-switcher privacy cover, `-AutobotDemo` fills
 an empty install with sample data. Release builds always return `false`.
 */
enum DebugFlags {
    static var screenshots: Bool {
        #if DEBUG
        ProcessInfo.processInfo.arguments.contains("-AutobotScreenshots")
        #else
        false
        #endif
    }

    static var demo: Bool {
        #if DEBUG
        ProcessInfo.processInfo.arguments.contains("-AutobotDemo")
        #else
        false
        #endif
    }
}
