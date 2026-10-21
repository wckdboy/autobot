import SwiftUI

/*
 The app's theme: the light/dark choice (ThemeMode) and the brand tint over system colours, and
 the sentence casing shared by labels. The riso scheme, type and inks are gone: screens use
 SwiftUI's semantic colours and text styles (NativeKit.swift), the marks keep their own colours
 (Brand.swift).
 */

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

/// Theme mode the user picked (System follows iOS light/dark, like Android's SYSTEM).
enum ThemeMode: String, Codable, CaseIterable, Sendable {
    case system = "SYSTEM", dark = "DARK", light = "LIGHT"
}

/// Applies [mode] to the hierarchy: the brand tint, and light or dark unless it follows the system.
struct AutobotTheme: ViewModifier {
    let mode: ThemeMode

    func body(content: Content) -> some View {
        content
            .tint(Color.brandPurple)
            .preferredColorScheme(mode == .system ? nil : mode == .dark ? .dark : .light)
    }
}

extension View {
    func autobotTheme(_ mode: ThemeMode) -> some View {
        modifier(AutobotTheme(mode: mode))
    }
}
