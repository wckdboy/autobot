import AutobotShared
import Foundation

/**
 Loopback host detection without DNS lookups (resolving a name could itself leak). The rule is the
 shared Kotlin `Loopback`, so both apps agree on what counts as loopback (`localhost`,
 `127.0.0.0/8`, `::1`, and `10.0.2.2`, the Android emulator's alias for the host machine).
 */
enum Loopback {
    static let emulatorHost = AutobotShared.Loopback.shared.EMULATOR_HOST

    static func isLoopback(_ host: String?) -> Bool { AutobotShared.Loopback.shared.isLoopback(host: host) }

    /// Extracts the host part of a URL string without resolving it.
    static func isLoopbackURL(_ url: String?) -> Bool { AutobotShared.Loopback.shared.isLoopbackUrl(url: url) }
}
