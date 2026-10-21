import SwiftUI
import UIKit

/*
 The brand, and only the brand: the pixel marks (Autobot's a, Invincible's i), the wordmark, the
 purple dot after a title and the route a run takes. Everything else on screen is system SwiftUI
 (NativeKit.swift); the app's tint is `Color.brandPurple`.
 */

/// Where a run executes. Shown as a tag on every card and row ([RouteBadge]).
enum Route: String, CaseIterable, Sendable {
    case localNpu, localGpu, localCpu, pcLan, cloudApi

    var glyph: String {
        switch self {
        case .localNpu, .localGpu, .localCpu: "●"
        case .pcLan: "◐"
        case .cloudApi: "○"
        }
    }

    var label: String {
        switch self {
        case .localNpu: "local · npu"
        case .localGpu: "local · gpu"
        case .localCpu: "local · cpu"
        case .pcLan: "pc · lan"
        case .cloudApi: "cloud · api"
        }
    }

    var local: Bool { self == .localNpu || self == .localGpu || self == .localCpu }

    /// "npu" / "gpu" / "cpu" for a local route (chip labels), nil otherwise.
    var accelerator: String? {
        switch self {
        case .localNpu: "npu"
        case .localGpu: "gpu"
        case .localCpu: "cpu"
        case .pcLan, .cloudApi: nil
        }
    }
}

/// The marks' own colours (BRAND.md): never text, never a control.
enum BrandColors {
    /// Autobot's mark: the pixel a, the dot after a title and the icon.
    static let purple = Color(rgb: Tokens.Palette.purple)
    /// Invincible's signal: the pixel i on anything about the node.
    static let orange = Color(rgb: Tokens.Palette.orange)
}

/// The products' symbols, as pixels: Autobot's a and Invincible's i (BRAND.md).
enum Glyph {
    static let a = [".####.", "....##", ".#####", "##..##", "##..##", ".#####"]
    static let i = ["##", "..", "##", "##", "##", "##"]
}

/// A pixel symbol [size] points tall: square cells, no smoothing.
struct PixelSymbol: View {
    let rows: [String]
    let color: Color
    var size: CGFloat = 12

    var body: some View {
        let cell = size / CGFloat(rows.count)
        Canvas { ctx, _ in
            for (y, row) in rows.enumerated() {
                for (x, ch) in row.enumerated() where ch == "#" {
                    ctx.fill(Path(CGRect(x: CGFloat(x) * cell, y: CGFloat(y) * cell, width: cell + 0.5, height: cell + 0.5)), with: .color(color))
                }
            }
        }
        .frame(width: cell * CGFloat(rows[0].count), height: size)
        .accessibilityHidden(true)
    }
}

/// Autobot's symbol, the pixel a, in its purple.
struct AutobotMark: View {
    var size: CGFloat = 12
    var color: Color? = nil

    var body: some View { PixelSymbol(rows: Glyph.a, color: color ?? BrandColors.purple, size: size) }
}

/// Invincible's symbol, the pixel i, in its orange: on anything about the node.
struct NodeMark: View {
    var size: CGFloat = 12

    var body: some View { PixelSymbol(rows: Glyph.i, color: BrandColors.orange, size: size) }
}

/// The pixel a and the lowercase mono name: Autobot's wordmark.
struct Wordmark: View {
    var name = "autobot"
    var color: Color? = nil
    @ScaledMetric(relativeTo: .headline) private var size: CGFloat = 17

    var body: some View {
        HStack(spacing: 8) {
            AutobotMark(size: (size * 0.82).rounded(), color: color)
            Text(name)
                .font(Font(Self.font(size: size)))
                .foregroundStyle(.primary)
                .lineLimit(1)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(name.prefix(1).uppercased() + name.dropFirst())
        .accessibilityAddTraits(.isHeader)
    }

    /// JetBrains Mono semibold (the bundled variable font), the system monospace without it.
    static func font(size: CGFloat) -> UIFont {
        let descriptor = UIFontDescriptor(fontAttributes: [
            .family: "JetBrains Mono",
            UIFontDescriptor.AttributeName(rawValue: kCTFontVariationAttribute as String): [0x7767_6874: 600], // 'wght'
        ])
        let font = UIFont(descriptor: descriptor, size: size)
        return font.familyName == "JetBrains Mono" ? font : .monospacedSystemFont(ofSize: size, weight: .semibold)
    }
}

/// The app icon (the pixel a in purple on white), for the places that show the icon itself.
struct AppIconImage: View {
    var size: CGFloat = 40

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.22, style: .continuous).fill(Color.white)
            PixelSymbol(rows: Glyph.a, color: BrandColors.purple, size: size * 0.5)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/**
 The small solid purple square that ends a top-level title ("Models▪"), as on the sites' h1:
 0.3 em, on the baseline, 0.15 em after the last letter. [fontSize] is the title's point size.
 */
struct TitleMark: View {
    var fontSize: CGFloat = 18

    var body: some View {
        Rectangle()
            .fill(BrandColors.purple)
            .frame(width: (fontSize * 0.3).rounded(), height: (fontSize * 0.3).rounded())
            .accessibilityHidden(true)
    }
}

/// The default top bar title: the bar shows the wordmark instead.
let wordmarkTitle = "autobot"

/**
 The screen's title and actions, in the system navigation bar (Liquid Glass where the system
 draws it): the `■ autobot` wordmark on Home, a sentence-case title with the purple [TitleMark]
 elsewhere, actions at the trailing edge. The system's back button pops the stack, as [onBack]
 did; it is kept for the screens that pass it.
 */
struct AutobotTopBar<Actions: View>: View {
    var title: String = wordmarkTitle
    var showMark = true
    var titleMark: Bool? = nil
    var onBack: (() -> Void)? = nil
    @ViewBuilder var actions: () -> Actions

    var body: some View {
        Color.clear
            .frame(height: 0)
            .navigationTitle(title == wordmarkTitle ? "Autobot" : title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    if title == wordmarkTitle {
                        Wordmark()
                    } else {
                        TitleWithDot(title, mark: titleMark ?? true)
                    }
                }
                if Actions.self != EmptyView.self {
                    ToolbarItemGroup(placement: .topBarTrailing) { actions() }
                }
            }
    }
}

extension AutobotTopBar where Actions == EmptyView {
    init(title: String = wordmarkTitle, showMark: Bool = true, titleMark: Bool? = nil, onBack: (() -> Void)? = nil) {
        self.init(title: title, showMark: showMark, titleMark: titleMark, onBack: onBack, actions: { EmptyView() })
    }
}
