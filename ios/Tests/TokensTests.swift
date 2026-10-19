import Foundation
import Testing
@testable import Autobot

/// The generated Swift tokens must match `shared/design/tokens.json` (the Android side asserts
/// Theme.kt against the same file).
struct TokensTests {
    nonisolated(unsafe) private static let json: [String: Any] = {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("shared/design/tokens.json")
        return try! JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
    }()

    private func hex(_ group: String, _ key: String) -> UInt32 {
        let value = (Self.json[group] as! [String: Any])[key] as! String
        return UInt32(value.dropFirst(), radix: 16)!
    }

    @Test func paletteMatches() {
        #expect(Tokens.Palette.red == hex("palette", "red"))
        #expect(Tokens.Palette.ink950 == hex("palette", "ink950"))
        #expect(Tokens.Palette.paper == hex("palette", "paper"))
        #expect(Tokens.Palette.bone100 == hex("palette", "bone100"))
        #expect(Tokens.Palette.orange == hex("palette", "orange"))
        #expect(Tokens.Palette.orangeLight == hex("palette", "orangeLight"))
        #expect(Tokens.Palette.lilac == hex("palette", "lilac"))
        #expect(Tokens.Palette.lilacDeep == hex("palette", "lilacDeep"))
        #expect((Self.json["palette"] as! [String: Any]).count == 27)
    }

    @Test func schemesMatch() {
        #expect(Tokens.dark.primary == hex("dark", "primary"))
        #expect(Tokens.dark.surfaceContainerHigh == hex("dark", "surfaceContainerHigh"))
        #expect(Tokens.dark.outlineVariant == hex("dark", "outlineVariant"))
        #expect(Tokens.light.background == hex("light", "background"))
        #expect(Tokens.light.onSurfaceVariant == hex("light", "onSurfaceVariant"))
        #expect(Tokens.light.onError == hex("lightMaterialDefaults", "onError"))
        #expect(Tokens.Amoled.surfaceContainer == hex("amoled", "surfaceContainer"))
    }

    @Test func inksMatch() {
        let inks = Self.json["inks"] as! [String: [String: String]]
        func hex(_ theme: String, _ key: String) -> UInt32 { UInt32(inks[theme]![key]!.dropFirst(), radix: 16)! }
        for (theme, set) in [("dark", Tokens.darkInks), ("light", Tokens.lightInks)] {
            #expect(set.amber == hex(theme, "amber"))
            #expect(set.cyan == hex(theme, "cyan"))
            #expect(set.uv == hex(theme, "uv"))
            #expect(set.signal == hex(theme, "signal"))
        }
        #expect(AutobotScheme.light.inks == .light)
        #expect(AutobotScheme.dark.amoled().inks == .dark)
    }

    /// WCAG AA for the text colours on paper and ink (Android's ContrastTest covers every pair).
    @Test func accentTextReads() {
        for scheme in [AutobotScheme.dark, .light] {
            for ground in [scheme.background, scheme.surfaceContainer, scheme.surfaceContainerHigh] {
                for ink in [scheme.onSurface, scheme.onSurfaceVariant, scheme.primary, scheme.tertiary, scheme.error,
                            scheme.inks.amber, scheme.inks.cyan, scheme.inks.uv, scheme.inks.signal] {
                    let (a, b) = (ink.luminance(), ground.luminance())
                    let ratio = (max(a, b) + 0.05) / (min(a, b) + 0.05)
                    #expect(ratio >= 4.5, "\(scheme.isDark ? "dark" : "light") \(ink) on \(ground): \(ratio)")
                }
            }
            let (p, on) = (scheme.primary.luminance(), scheme.onPrimary.luminance())
            #expect((max(p, on) + 0.05) / (min(p, on) + 0.05) >= 4.5)
        }
    }

    @Test func typeScaleMatches() {
        let type = Self.json["type"] as! [String: [String: Any]]
        let displayMedium = type["displayMedium"]!
        #expect(Tokens.Typography.displayMedium.size == CGFloat((displayMedium["size"] as! NSNumber).doubleValue))
        #expect(Tokens.Typography.displayMedium.lineHeight == CGFloat((displayMedium["lineHeight"] as! NSNumber).doubleValue))
        #expect(Tokens.Typography.micro.letterSpacing == CGFloat((type["micro"]!["letterSpacing"] as! NSNumber).doubleValue))
        #expect(Tokens.Typography.readout.tabular)
        #expect(Tokens.Typography.labelLarge.weight == 600)
    }

    @Test func generatedFileIsUpToDate() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let generated = try String(contentsOf: root.appendingPathComponent("App/Design/Tokens.generated.swift"), encoding: .utf8)
        for (key, value) in Self.json["spacing"] as! [String: NSNumber] {
            #expect(generated.contains("static let \(key): CGFloat = "), "missing spacing.\(key)")
            _ = value
        }
        #expect(Tokens.Spacing.gutter == 16)
        #expect(Tokens.Stroke.icon == 1.75)
        #expect(Tokens.Radius.extraSmall == 2)
    }
}
