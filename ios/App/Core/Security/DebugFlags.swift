import Foundation

/**
 Debug-build switches for UI work (Android's debug-only `allow_screenshots` extra):
 `-AutobotScreenshots` skips the app lock and the app-switcher privacy cover, `-AutobotDemo` fills
 an empty install with sample data, `-AutobotSkipWelcome` starts past the first-run welcome, `-AutobotTheme light` picks the theme. Release builds always return `false`.
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

    /// `-AutobotTheme light|dark|system`: this launch's theme over the saved one (screenshots).
    static let theme: ThemeMode? = {
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-AutobotTheme"), i + 1 < args.count else { return nil }
        return ThemeMode(rawValue: args[i + 1].uppercased())
        #else
        nil
        #endif
    }()

    static var skipWelcome: Bool {
        #if DEBUG
        ProcessInfo.processInfo.arguments.contains("-AutobotSkipWelcome")
        #else
        false
        #endif
    }
}
