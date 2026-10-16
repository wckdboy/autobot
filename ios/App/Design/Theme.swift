import os
import SwiftUI
import UIKit

extension Color {
    /// Opaque sRGB colour from `0xRRGGBB`.
    init(rgb: UInt32, opacity: Double = 1) {
        self.init(
            .sRGB,
            red: Double((rgb >> 16) & 0xFF) / 255,
            green: Double((rgb >> 8) & 0xFF) / 255,
            blue: Double(rgb & 0xFF) / 255,
            opacity: opacity,
        )
    }

    /// Relative luminance (as Compose's `Color.luminance()`), used to pick ink or bone text.
    func luminance() -> Double {
        let ui = UIColor(self)
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        ui.getRed(&r, green: &g, blue: &b, alpha: &a)
        func linear(_ c: CGFloat) -> Double {
            let v = Double(c)
            return v <= 0.03928 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)
    }
}

/**
 Riso palette: one signal red printed on ink (dark) or paper (light), with warm greys. Red is
 the brand and "live / on this phone" colour, not an error colour by itself.
 */
enum AutobotColors {
    static let black = Color(rgb: Tokens.Palette.black)
    static let ink950 = Color(rgb: Tokens.Palette.ink950)
    static let ink900 = Color(rgb: Tokens.Palette.ink900)
    static let ink850 = Color(rgb: Tokens.Palette.ink850)
    static let ink800 = Color(rgb: Tokens.Palette.ink800)
    static let ink700 = Color(rgb: Tokens.Palette.ink700)
    static let line = Color(rgb: Tokens.Palette.line)
    static let ink500 = Color(rgb: Tokens.Palette.ink500)
    static let ash400 = Color(rgb: Tokens.Palette.ash400)
    static let ash200 = Color(rgb: Tokens.Palette.ash200)
    static let bone100 = Color(rgb: Tokens.Palette.bone100)
    static let bone50 = Color(rgb: Tokens.Palette.bone50)

    static let paper = Color(rgb: Tokens.Palette.paper)
    static let paperLine = Color(rgb: Tokens.Palette.paperLine)
    static let paperMuted = Color(rgb: Tokens.Palette.paperMuted)
    static let paperInk = Color(rgb: Tokens.Palette.paperInk)

    /// The signal colour.
    static let red = Color(rgb: Tokens.Palette.red)
    static let redDeep = Color(rgb: Tokens.Palette.redDeep)
    static let redDim = Color(rgb: Tokens.Palette.redDim)

    static let amber = Color(rgb: Tokens.Palette.amber)
    static let cyan = Color(rgb: Tokens.Palette.cyan)
    static let error = Color(rgb: Tokens.Palette.error)

    // Semantic aliases used across features.
    static let acid = red
    static let green = red
    static let signal = error
    static let uv = ash200
    static let violet = ash200
}

/// Material-style colour roles, resolved from the generated tokens.
struct AutobotScheme: Sendable, Equatable {
    let isDark: Bool
    var primary, onPrimary, primaryContainer, onPrimaryContainer: Color
    var secondary, onSecondary, secondaryContainer, onSecondaryContainer: Color
    var tertiary, onTertiary: Color
    var error, onError, errorContainer, onErrorContainer: Color
    var background, onBackground, surface, onSurface: Color
    var surfaceVariant, onSurfaceVariant: Color
    var surfaceContainerLowest, surfaceContainerLow, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest: Color
    var surfaceBright, surfaceDim: Color
    var outline, outlineVariant: Color
    var inverseSurface, inverseOnSurface, inversePrimary, scrim: Color

    init(_ s: Tokens.Scheme, isDark: Bool) {
        self.isDark = isDark
        primary = Color(rgb: s.primary); onPrimary = Color(rgb: s.onPrimary)
        primaryContainer = Color(rgb: s.primaryContainer); onPrimaryContainer = Color(rgb: s.onPrimaryContainer)
        secondary = Color(rgb: s.secondary); onSecondary = Color(rgb: s.onSecondary)
        secondaryContainer = Color(rgb: s.secondaryContainer); onSecondaryContainer = Color(rgb: s.onSecondaryContainer)
        tertiary = Color(rgb: s.tertiary); onTertiary = Color(rgb: s.onTertiary)
        error = Color(rgb: s.error); onError = Color(rgb: s.onError)
        errorContainer = Color(rgb: s.errorContainer); onErrorContainer = Color(rgb: s.onErrorContainer)
        background = Color(rgb: s.background); onBackground = Color(rgb: s.onBackground)
        surface = Color(rgb: s.surface); onSurface = Color(rgb: s.onSurface)
        surfaceVariant = Color(rgb: s.surfaceVariant); onSurfaceVariant = Color(rgb: s.onSurfaceVariant)
        surfaceContainerLowest = Color(rgb: s.surfaceContainerLowest)
        surfaceContainerLow = Color(rgb: s.surfaceContainerLow)
        surfaceContainer = Color(rgb: s.surfaceContainer)
        surfaceContainerHigh = Color(rgb: s.surfaceContainerHigh)
        surfaceContainerHighest = Color(rgb: s.surfaceContainerHighest)
        surfaceBright = Color(rgb: s.surfaceBright); surfaceDim = Color(rgb: s.surfaceDim)
        outline = Color(rgb: s.outline); outlineVariant = Color(rgb: s.outlineVariant)
        inverseSurface = Color(rgb: s.inverseSurface); inverseOnSurface = Color(rgb: s.inverseOnSurface)
        inversePrimary = Color(rgb: s.inversePrimary); scrim = Color(rgb: s.scrim)
    }

    static let dark = AutobotScheme(Tokens.dark, isDark: true)
    static let light = AutobotScheme(Tokens.light, isDark: false)

    /// Pure-black variant for OLED panels: every background-ish surface becomes #000.
    func amoled() -> AutobotScheme {
        var s = self
        s.background = Color(rgb: Tokens.Amoled.background)
        s.surface = Color(rgb: Tokens.Amoled.surface)
        s.surfaceContainerLowest = Color(rgb: Tokens.Amoled.surfaceContainerLowest)
        s.surfaceContainerLow = Color(rgb: Tokens.Amoled.surfaceContainerLow)
        s.surfaceContainer = Color(rgb: Tokens.Amoled.surfaceContainer)
        s.surfaceDim = Color(rgb: Tokens.Amoled.surfaceDim)
        return s
    }
}

private struct SchemeKey: EnvironmentKey {
    static let defaultValue = AutobotScheme.dark
}

extension EnvironmentValues {
    /// The active riso colour scheme (ink or paper).
    var scheme: AutobotScheme {
        get { self[SchemeKey.self] }
        set { self[SchemeKey.self] = newValue }
    }
}

// MARK: - Type

/// Corner radii: hard edges so cards read as printed blocks.
enum AutobotShapes {
    static let extraSmall = Tokens.Radius.extraSmall
    static let small = Tokens.Radius.small
    static let medium = Tokens.Radius.medium
    static let large = Tokens.Radius.large
    static let extraLarge = Tokens.Radius.extraLarge
}

/// Fonts are variable (`wght` axis), as on Android where weights come from `FontVariation`.
enum AutobotFonts {
    static let displayFamily = "Antonio"
    static let monoFamily = "JetBrains Mono"
    private static let weightAxis = 0x7767_6874 // 'wght'

    private struct FontKey: Hashable {
        let font: Int
        let size: CGFloat
        let weight: CGFloat
        let tabular: Bool
    }

    /// Resolved fonts by role, size, weight and figures: every `textStyle` asks on each body
    /// evaluation, and building a variable font from a descriptor is not free (list rows).
    private static let cache = OSAllocatedUnfairLock(initialState: [FontKey: UIFont]())

    static func uiFont(_ style: Tokens.TypeStyle, size: CGFloat? = nil, weight: CGFloat? = nil) -> UIFont {
        let pointSize = size ?? style.size
        let w = weight ?? style.weight
        let role = switch style.font {
        case .sans: 0
        case .display: 1
        case .mono: 2
        }
        let key = FontKey(font: role, size: pointSize, weight: w, tabular: style.tabular)
        if let hit = cache.withLock({ $0[key] }) { return hit }
        let font = makeFont(style, pointSize: pointSize, weight: w)
        cache.withLock { $0[key] = font }
        return font
    }

    private static func makeFont(_ style: Tokens.TypeStyle, pointSize: CGFloat, weight w: CGFloat) -> UIFont {
        let font: UIFont
        switch style.font {
        case .sans:
            font = .systemFont(ofSize: pointSize, weight: uiWeight(w))
        case .display, .mono:
            let family = style.font == .display ? displayFamily : monoFamily
            let descriptor = UIFontDescriptor(fontAttributes: [
                .family: family,
                UIFontDescriptor.AttributeName(rawValue: kCTFontVariationAttribute as String): [weightAxis: w],
            ])
            font = UIFont(descriptor: descriptor, size: pointSize)
        }
        guard style.tabular else { return font }
        let tabular = font.fontDescriptor.addingAttributes([
            .featureSettings: [[
                UIFontDescriptor.FeatureKey.type: kNumberSpacingType,
                UIFontDescriptor.FeatureKey.selector: kMonospacedNumbersSelector,
            ]],
        ])
        return UIFont(descriptor: tabular, size: pointSize)
    }

    static func uiWeight(_ w: CGFloat) -> UIFont.Weight {
        switch w {
        case ..<150: .ultraLight
        case ..<250: .thin
        case ..<350: .light
        case ..<450: .regular
        case ..<550: .medium
        case ..<650: .semibold
        case ..<750: .bold
        case ..<850: .heavy
        default: .black
        }
    }
}

/// The app's text styles (Material names plus the extra riso styles).
enum AutobotType {
    case displayLarge, displayMedium, displaySmall
    case headlineLarge, headlineMedium, headlineSmall
    case titleLarge, titleMedium, titleSmall
    case bodyLarge, bodyMedium, bodySmall
    case labelLarge, labelMedium, labelSmall
    /// Inline code.
    case code
    /// Code blocks, tool output.
    case codeBlock
    /// Uppercase micro labels: `// SECTION`, field captions.
    case micro
    /// Small numeric telemetry (tok/s, sizes): tabular mono.
    case readout
    /// Big numbers (STEPS 4, SEED 4182): condensed display.
    case number

    var token: Tokens.TypeStyle {
        switch self {
        case .displayLarge: Tokens.Typography.displayLarge
        case .displayMedium: Tokens.Typography.displayMedium
        case .displaySmall: Tokens.Typography.displaySmall
        case .headlineLarge: Tokens.Typography.headlineLarge
        case .headlineMedium: Tokens.Typography.headlineMedium
        case .headlineSmall: Tokens.Typography.headlineSmall
        case .titleLarge: Tokens.Typography.titleLarge
        case .titleMedium: Tokens.Typography.titleMedium
        case .titleSmall: Tokens.Typography.titleSmall
        case .bodyLarge: Tokens.Typography.bodyLarge
        case .bodyMedium: Tokens.Typography.bodyMedium
        case .bodySmall: Tokens.Typography.bodySmall
        case .labelLarge: Tokens.Typography.labelLarge
        case .labelMedium: Tokens.Typography.labelMedium
        case .labelSmall: Tokens.Typography.labelSmall
        case .code: Tokens.Typography.code
        case .codeBlock: Tokens.Typography.codeBlock
        case .micro: Tokens.Typography.micro
        case .readout: Tokens.Typography.readout
        case .number: Tokens.Typography.number
        }
    }

    var uiFont: UIFont { AutobotFonts.uiFont(token) }
    var font: Font { Font(uiFont) }
}

private struct TypeStyleModifier: ViewModifier {
    let style: AutobotType
    let weight: CGFloat?

    func body(content: Content) -> some View {
        let token = style.token
        let font = AutobotFonts.uiFont(token, weight: weight)
        let natural = font.lineHeight
        if #available(iOS 26.0, *) {
            content
                .font(Font(font))
                .tracking(token.letterSpacing)
                .lineHeight(.exact(points: token.lineHeight))
        } else {
            content
                .font(Font(font))
                .tracking(token.letterSpacing)
                .lineSpacing(max(0, token.lineHeight - natural))
        }
    }
}

extension View {
    /// Applies a riso text style: font, letter spacing and line height from the tokens.
    func textStyle(_ style: AutobotType, weight: CGFloat? = nil) -> some View {
        modifier(TypeStyleModifier(style: style, weight: weight))
    }
}

/// Theme mode the user picked (System follows iOS light/dark, like Android's SYSTEM).
enum ThemeMode: String, Codable, CaseIterable, Sendable {
    case system = "SYSTEM", dark = "DARK", light = "LIGHT"
}

/// Applies the riso scheme for [mode] to the hierarchy: environment scheme, tint, background.
struct AutobotTheme: ViewModifier {
    let mode: ThemeMode
    let amoled: Bool
    /// The paper studio is on screen: dark status-bar glyphs whatever the theme.
    var paperOnTop = false
    /// The Studio's darkroom is on screen: light status-bar glyphs whatever the theme.
    var darkOnTop = false
    @Environment(\.colorScheme) private var system

    func body(content: Content) -> some View {
        let dark = switch mode {
        case .system: system == .dark
        case .dark: true
        case .light: false
        }
        let scheme = dark ? (amoled ? AutobotScheme.dark.amoled() : .dark) : .light
        content
            .environment(\.scheme, scheme)
            .tint(scheme.primary)
            .preferredColorScheme(paperOnTop ? .light : darkOnTop ? .dark : mode == .system ? nil : (dark ? .dark : .light))
    }
}

/// The Studio's darkroom: the ink scheme regardless of the app theme (image and video rooms).
struct DarkroomTheme: ViewModifier {
    let amoled: Bool

    func body(content: Content) -> some View {
        let scheme = amoled ? AutobotScheme.dark.amoled() : .dark
        content
            .environment(\.scheme, scheme)
            .tint(scheme.primary)
    }
}

/// Paper (light) surface regardless of the app theme — used by the image studio.
struct PaperTheme: ViewModifier {
    func body(content: Content) -> some View {
        content
            .environment(\.scheme, .light)
            .tint(AutobotScheme.light.primary)
            .toolbarColorScheme(.light, for: .navigationBar)
    }
}

extension View {
    func autobotTheme(_ mode: ThemeMode, amoled: Bool = false, paperOnTop: Bool = false, darkOnTop: Bool = false) -> some View {
        modifier(AutobotTheme(mode: mode, amoled: amoled, paperOnTop: paperOnTop, darkOnTop: darkOnTop))
    }
    func paperTheme() -> some View { modifier(PaperTheme()) }
    func darkroomTheme(amoled: Bool = false) -> some View { modifier(DarkroomTheme(amoled: amoled)) }
}
