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

    /// Red: errors, deletion, older art. Not the signal any more (that is lilac).
    static let red = Color(rgb: Tokens.Palette.red)
    static let redDeep = Color(rgb: Tokens.Palette.redDeep)
    static let redDim = Color(rgb: Tokens.Palette.redDim)

    /// Autobot's mark: the pixel a and the icon, never text.
    static let purple = Color(rgb: Tokens.Palette.purple)

    /// Autobot's signal as text: lilac on ink, a deeper lilac on paper (5:1), and a dim fill.
    static let lilac = Color(rgb: Tokens.Palette.lilac)
    static let lilacDeep = Color(rgb: Tokens.Palette.lilacDeep)
    static let lilacDim = Color(rgb: Tokens.Palette.lilacDim)

    /// Invincible's signal (the node), on paper and on ink: screens about the node mark it in this.
    static let orange = Color(rgb: Tokens.Palette.orange)
    static let orangeLight = Color(rgb: Tokens.Palette.orangeLight)

    static let amber = Color(rgb: Tokens.Palette.amber)
    static let cyan = Color(rgb: Tokens.Palette.cyan)
    static let error = Color(rgb: Tokens.Palette.error)

    // Semantic aliases used across features.
    static let acid = lilac
    static let green = lilac
    static let signal = error
    static let uv = ash200
    static let violet = ash200
}

/**
 The accent colours as text and glyphs. The palette accents (`AutobotColors.amber`, cyan, uv,
 signal) are for fills and halftone dots; on paper they are under 2:1, so a word or an icon in an
 accent takes the ink for the ground it sits on (4.5:1 on that theme's background and panels).
 Signal text is the scheme's `primary`.
 */
struct AutobotInks: Sendable, Equatable {
    let amber, cyan, uv, signal: Color

    init(_ i: Tokens.Inks) {
        amber = Color(rgb: i.amber); cyan = Color(rgb: i.cyan); uv = Color(rgb: i.uv); signal = Color(rgb: i.signal)
    }

    static let dark = AutobotInks(Tokens.darkInks)
    static let light = AutobotInks(Tokens.lightInks)
}

/// Material-style colour roles, resolved from the generated tokens.
struct AutobotScheme: Sendable, Equatable {
    let isDark: Bool
    /// Accent inks legible on this scheme's grounds.
    var inks: AutobotInks { isDark ? .dark : .light }
    /// Invincible's orange for this ground: the mark on anything about the node.
    var node: Color { isDark ? AutobotColors.orangeLight : AutobotColors.orange }
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

    /**
     The font for [style] at the reader's text size: the token size times [scale] for
     [dynamicType] (1 at the default `.large`), so every role follows the iOS Dynamic Type curve.
     */
    static func uiFont(_ style: Tokens.TypeStyle, size: CGFloat? = nil, weight: CGFloat? = nil, dynamicType: DynamicTypeSize = .large) -> UIFont {
        let base = size ?? style.size
        let pointSize = (base * scale(for: base, dynamicType)).rounded(toPlaces: 2)
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

    /**
     Resolves every text style's font into the cache (called off the main thread at launch):
     the first lookup of a variable font by family and axis costs about a millisecond per style,
     which the first screen would otherwise pay on the main thread while it renders.
     */
    static func prewarm() {
        for style in AutobotType.allCases { _ = uiFont(style.token).lineHeight }
    }

    // MARK: Dynamic Type

    /// The UIKit text style whose Dynamic Type curve a token of [size] points follows.
    static func textStyle(for size: CGFloat) -> UIFont.TextStyle {
        switch size {
        case 40...: .largeTitle
        case 27...: .title1
        case 21...: .title2
        case 18...: .title3
        case 15.5...: .body
        case 14.5...: .subheadline
        case 13...: .footnote
        case 11.5...: .caption1
        default: .caption2
        }
    }

    /**
     How far a [size] token may grow. Display and headline type is set big already and would
     push a screen's content off it, so it stops early (1.2× for the 44–72 pt hero lines, 1.5×
     for headlines, 1.8× for titles); reading and label type follows iOS up to 3.2×.
     */
    static func maxScale(for size: CGFloat) -> CGFloat {
        switch size {
        case 40...: 1.2
        case 24...: 1.5
        case 20...: 1.8
        default: 3.2
        }
    }

    /**
     Size multiplier for a [size]-point token at [dynamicType]: exactly 1 at `.large`, never above
     1 below it and never below 1 above it. (The iOS caption curves floor at 11 pt, which would
     grow a 10.5 pt token when the reader picks smaller text.)
     */
    static func scale(for size: CGFloat, _ dynamicType: DynamicTypeSize) -> CGFloat {
        guard dynamicType != .large else { return 1 }
        let traits = UITraitCollection(preferredContentSizeCategory: UIContentSizeCategory(dynamicType))
        let scaled = UIFontMetrics(forTextStyle: textStyle(for: size)).scaledValue(for: size, compatibleWith: traits) / size
        return dynamicType < .large ? min(scaled, 1) : min(max(scaled, 1), maxScale(for: size))
    }

    /// Icons and tap targets grow with the text beside them, up to 1.6× so a bar of actions still fits.
    static func iconScale(_ dynamicType: DynamicTypeSize) -> CGFloat {
        guard dynamicType != .large else { return 1 }
        return min(scale(for: 17, dynamicType), 1.6)
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
enum AutobotType: CaseIterable {
    case displayLarge, displayMedium, displaySmall
    case headlineLarge, headlineMedium, headlineSmall
    case titleLarge, titleMedium, titleSmall
    case bodyLarge, bodyMedium, bodySmall
    case labelLarge, labelMedium, labelSmall
    /// Inline code.
    case code
    /// Code blocks, tool output.
    case codeBlock
    /// Section labels, field captions and meta: sentence-case mono, untracked.
    case micro
    /// Small numeric telemetry (tok/s, sizes): tabular mono.
    case readout
    /// Big numbers (STEPS 4, SEED 4182): tabular mono.
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
        case .labelLarge: AppUI.labelLarge
        case .labelMedium: AppUI.labelMedium
        case .labelSmall: AppUI.labelSmall
        case .code: Tokens.Typography.code
        case .codeBlock: Tokens.Typography.codeBlock
        case .micro: AppUI.micro
        case .readout: Tokens.Typography.readout
        case .number: Tokens.Typography.number
        }
    }

    var uiFont: UIFont { AutobotFonts.uiFont(token) }
    var font: Font { Font(uiFont) }

    /// The font at the reader's text size.
    func font(_ dynamicType: DynamicTypeSize) -> Font { Font(AutobotFonts.uiFont(token, dynamicType: dynamicType)) }
}

/**
 App UI v4 label type: JetBrains Mono in sentence case, so the tokens' wide tracking (set for
 the old all-caps labels) goes, and captions grow to a readable 12 pt. The token file still
 carries the v3 values (`shared/design/tokens.json` is shared with Android; see the v4 spec).
 */
enum AppUI {
    /// Section labels and meta: mono 12, no tracking.
    static let micro = Tokens.TypeStyle(font: .mono, size: 12, lineHeight: 16, weight: 450, letterSpacing: 0, trim: false, tabular: false)
    /// Button labels.
    static let labelLarge = Tokens.TypeStyle(font: .mono, size: 13, lineHeight: 16, weight: 600, letterSpacing: 0, trim: true, tabular: false)
    /// Text actions, chips.
    static let labelMedium = Tokens.TypeStyle(font: .mono, size: 12, lineHeight: 15, weight: 500, letterSpacing: 0, trim: true, tabular: false)
    /// Tab bar labels and small chips.
    static let labelSmall = Tokens.TypeStyle(font: .mono, size: 11, lineHeight: 14, weight: 500, letterSpacing: 0, trim: true, tabular: false)
}

extension String {
    /**
     Sentence case for UI labels written in lowercase mono ("installed · 2" → "Installed · 2"):
     the first letter is capitalised and a leading acronym is spelled as such ("cfg" → "CFG").
     The rest is left as written (model names, paths and units keep their case).
     */
    var sentenceCased: String { Self.spellingAbbreviations(self).leadCased }

    /// The first letter capitalised (or a leading acronym spelled); see [sentenceCased].
    private var leadCased: String {
        guard let first = firstIndex(where: \.isLetter) else { return self }
        // URLs, hosts, file names and ids lead as written ("civitai.com", "https://…", "plan.md").
        let lead = self[first...].prefix { !$0.isWhitespace }
        if lead.contains("://") || lead.dropLast().contains(where: { "._@#:".contains($0) }) { return self }
        // A leading acronym or product name, matched as a whole word.
        let rest = self[first...]
        let end = rest.firstIndex(where: { !($0.isLetter || $0.isNumber || $0 == "/") }) ?? endIndex
        let word = String(self[first ..< end])
        if let fixed = Self.acronyms[word.lowercased()], word == word.lowercased() {
            return String(self[..<first]) + fixed + String(self[end...])
        }
        // "5 sessions", "30 s", "~12 tokens": a count or unit leads, the words after it stay lowercase.
        guard self[first].isLowercase, !self[..<first].contains(where: \.isNumber) else { return self }
        let cased = String(self[..<first]) + self[first].uppercased() + String(self[index(after: first)...])
        return cased.hasPrefix("Core ml") ? "Core ML" + cased.dropFirst(7) : cased
    }

    /// Abbreviations anywhere in a label, as whole lowercase words, in their written form
    /// ("models · api & lan" → "models · API & LAN"): the same words and rule as Android's `labelCase`.
    static func spellingAbbreviations(_ text: String) -> String {
        guard text.contains(where: \.isLowercase) else { return text }
        if let hit = abbreviationCache.object(forKey: text as NSString) { return hit as String }
        let ns = text as NSString
        var out = text
        for match in abbreviationWord.matches(in: text, range: NSRange(location: 0, length: ns.length)).reversed() {
            let word = ns.substring(with: match.range)
            if let fixed = abbreviations[word], let range = Range(match.range, in: out) { out.replaceSubrange(range, with: fixed) }
        }
        abbreviationCache.setObject(out as NSString, forKey: text as NSString)
        return out
    }

    nonisolated(unsafe) private static let abbreviationWord = try! NSRegularExpression(pattern: "(?<![\\w./-])[a-z][a-z0-9+-]*(?![\\w/.-])")
    nonisolated(unsafe) private static let abbreviationCache: NSCache<NSString, NSString> = {
        let cache = NSCache<NSString, NSString>()
        cache.countLimit = 2000
        return cache
    }()

    /// Android's `labelCase` abbreviations.
    private static let abbreviations: [String: String] = [
        "api": "API", "apis": "APIs", "lan": "LAN", "pc": "PC", "pcs": "PCs", "npu": "NPU", "gpu": "GPU", "cpu": "CPU",
        "usd": "USD", "cfg": "CFG", "lora": "LoRA", "loras": "LoRAs", "apk": "APK", "mcp": "MCP", "http": "HTTP",
        "sha-256": "SHA-256", "ram": "RAM", "url": "URL", "vae": "VAE", "comfyui": "ComfyUI", "controlnet": "ControlNet",
        "aicore": "AICore", "ai": "AI", "pin": "PIN",
    ]

    private static let acronyms: [String: String] = [
        "cfg": "CFG", "npu": "NPU", "gpu": "GPU", "cpu": "CPU", "api": "API", "usd": "USD", "mcp": "MCP",
        "url": "URL", "lora": "LoRA", "loras": "LoRAs", "comfyui": "ComfyUI", "ios": "iOS", "pc": "PC", "pcs": "PCs",
        "json": "JSON", "x/y": "X/Y", "ram": "RAM", "ai": "AI", "hf": "HF",
        "img2img": "Img2img", "txt2img": "Txt2img",
    ]
}

private extension CGFloat {
    /// Keeps the font cache small: sizes that differ in the third decimal are one font.
    func rounded(toPlaces places: Int) -> CGFloat {
        let m = pow(10, CGFloat(places))
        return (self * m).rounded() / m
    }
}

private struct TypeStyleModifier: ViewModifier {
    let style: AutobotType
    let weight: CGFloat?
    @Environment(\.dynamicTypeSize) private var dynamicType

    func body(content: Content) -> some View {
        let token = style.token
        let scale = AutobotFonts.scale(for: token.size, dynamicType)
        let font = AutobotFonts.uiFont(token, weight: weight, dynamicType: dynamicType)
        let natural = font.lineHeight
        let lineHeight = token.lineHeight * scale
        if #available(iOS 26.0, *) {
            content
                .font(Font(font))
                .tracking(token.letterSpacing)
                .lineHeight(.exact(points: lineHeight))
        } else {
            content
                .font(Font(font))
                .tracking(token.letterSpacing)
                .lineSpacing(max(0, lineHeight - natural))
        }
    }
}

/// A line limit that lets the text wrap at accessibility sizes instead of truncating.
private struct AccessibleLineLimit: ViewModifier {
    let lines: Int
    let accessibility: Int
    @Environment(\.dynamicTypeSize) private var dynamicType

    func body(content: Content) -> some View {
        content.lineLimit(dynamicType.isAccessibilitySize ? max(lines, accessibility) : lines)
    }
}

/// An SF Symbol at [size] points, grown with the reader's text size (see [AutobotFonts.iconScale]).
private struct ScaledSymbolModifier: ViewModifier {
    let size: CGFloat
    let weight: Font.Weight
    @Environment(\.dynamicTypeSize) private var dynamicType

    func body(content: Content) -> some View {
        content.font(.system(size: size * AutobotFonts.iconScale(dynamicType), weight: weight))
    }
}

extension View {
    /// Applies a riso text style: font, letter spacing and line height from the tokens.
    func textStyle(_ style: AutobotType, weight: CGFloat? = nil) -> some View {
        modifier(TypeStyleModifier(style: style, weight: weight))
    }

    /// [lines] lines at the standard text sizes; at the accessibility sizes, where a label one line
    /// long would cut off its essential words, it wraps to [accessibility] lines (the row grows).
    func textLines(_ lines: Int, accessibility: Int = 3) -> some View {
        modifier(AccessibleLineLimit(lines: lines, accessibility: accessibility))
    }

    /// An SF Symbol font of [size] points that scales with Dynamic Type (capped at 1.6×).
    func symbolSize(_ size: CGFloat, weight: Font.Weight = .regular) -> some View {
        modifier(ScaledSymbolModifier(size: size, weight: weight))
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
