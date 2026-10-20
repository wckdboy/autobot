import SwiftUI

/// Where a run executes. Shown as a tag on every card and row.
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

/**
 `● local · cpu` / `◐ pc · lan` / `○ cloud · api`: a quiet hairline tag; the glyph says where
 (filled: on this phone). Not in the signal colour: where a run goes is information, not an alarm.
 */
struct RouteTag: View {
    let route: Route
    @Environment(\.scheme) private var c

    var body: some View {
        Text("\(route.glyph) \(route.label)")
            .textStyle(.micro)
            .textLines(1)
            .foregroundStyle(c.onSurfaceVariant)
            .padding(.horizontal, Tokens.Spacing.tagPaddingHorizontal)
            .padding(.vertical, Tokens.Spacing.tagPaddingVertical)
            .background {
                RoundedRectangle(cornerRadius: AutobotShapes.extraSmall)
                    .strokeBorder(c.outlineVariant, lineWidth: Tokens.Stroke.border)
            }
            .fixedSize()
    }
}

/// A quiet `Section · detail` caption: sentence-case mono, untracked, onSurfaceVariant.
struct SectionLabel: View {
    let text: String
    var color: Color? = nil
    @Environment(\.scheme) private var c

    init(_ text: String, color: Color? = nil) {
        self.text = text
        self.color = color
    }

    var body: some View {
        Text(text.sentenceCased)
            .textStyle(.micro)
            .foregroundStyle(color ?? c.onSurfaceVariant)
            .textLines(2, accessibility: 4)
            .accessibilityAddTraits(.isHeader)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Full-width block in the scheme's primary (a screen's primary action), sentence-case mono label.
struct PrimaryButton: View {
    let text: String
    var enabled = true
    /// Work it started runs: an ink loader stands in for the label (the button keeps its width).
    var busy = false
    let action: () -> Void
    @Environment(\.scheme) private var c

    init(_ text: String, enabled: Bool = true, busy: Bool = false, action: @escaping () -> Void) {
        self.text = text
        self.enabled = enabled
        self.busy = busy
        self.action = action
    }

    var body: some View {
        // Busy buttons are disabled, but stay lit around their loader (it is the live part).
        let lit = enabled || busy
        Button(action: action) {
            InkBusyLabel(busy: busy, color: c.onPrimary) {
                Text(text.sentenceCased)
                    .textStyle(.labelLarge)
                    .textLines(1)
            }
            .padding(.horizontal, Tokens.Spacing.primaryButtonPaddingHorizontal)
            .frame(maxWidth: .infinity, minHeight: Tokens.Spacing.primaryButtonMinHeight)
            .foregroundStyle(lit ? c.onPrimary : c.onSurface.opacity(0.38))
            .background(
                RoundedRectangle(cornerRadius: AutobotShapes.extraSmall)
                    .fill(lit ? c.primary : c.onSurface.opacity(0.12)),
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle())
        .disabled(!enabled || busy)
    }
}

/// Outlined block button; [accent] tints border and label (e.g. red for DENY).
struct GhostButton: View {
    let text: String
    var accent: Color? = nil
    var enabled = true
    /// Work it started runs: an ink loader stands in for the label (the button keeps its width).
    var busy = false
    let action: () -> Void
    @Environment(\.scheme) private var c

    init(_ text: String, accent: Color? = nil, enabled: Bool = true, busy: Bool = false, action: @escaping () -> Void) {
        self.text = text
        self.accent = accent
        self.enabled = enabled
        self.busy = busy
        self.action = action
    }

    var body: some View {
        let tint = accent ?? c.onSurface
        // Busy buttons are disabled, but stay lit around their loader (it is the live part).
        let lit = enabled || busy
        Button(action: action) {
            InkBusyLabel(busy: busy, color: accent ?? c.primary) {
                Text(text.sentenceCased)
                    .textStyle(.labelLarge)
                    .textLines(1)
            }
            .padding(.horizontal, Tokens.Spacing.ghostButtonPaddingHorizontal)
            .frame(maxWidth: .infinity, minHeight: Tokens.Spacing.ghostButtonMinHeight)
            .foregroundStyle(lit ? tint : c.onSurface.opacity(0.38))
            .overlay(
                RoundedRectangle(cornerRadius: AutobotShapes.extraSmall)
                    .strokeBorder(lit ? tint : c.outlineVariant, lineWidth: Tokens.Stroke.border),
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle())
        .disabled(!enabled || busy)
    }
}

/// The approve button on an approval card: the card's primary action, so a primary block.
struct PaperButton: View {
    let text: String
    let action: () -> Void
    @Environment(\.scheme) private var c

    init(_ text: String, action: @escaping () -> Void) {
        self.text = text
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Text(text.sentenceCased)
                .textStyle(.labelLarge)
                .textLines(1)
                .frame(maxWidth: .infinity, minHeight: Tokens.Spacing.ghostButtonMinHeight)
                .foregroundStyle(c.onPrimary)
                .background(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall).fill(c.primary))
                .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle())
    }
}

/**
 Small mono text button (`All`, `Tune`, `OK`), sentence case, in lilac like Android's text
 buttons; a [color] (an error red for Delete, ash for a quiet one) tints the label instead.
 */
struct TextAction: View {
    let text: String
    var color: Color? = nil
    var style: AutobotType = .labelMedium
    var enabled = true
    /// Work it started runs: an ink loader stands in for the label.
    var busy = false
    let action: () -> Void
    @Environment(\.scheme) private var c

    init(_ text: String, color: Color? = nil, style: AutobotType = .labelMedium, enabled: Bool = true, busy: Bool = false, action: @escaping () -> Void) {
        self.text = text
        self.color = color
        self.style = style
        self.enabled = enabled
        self.busy = busy
        self.action = action
    }

    var body: some View {
        // As Android's text buttons: the label in lilac (or [color]), no underline.
        Button(action: action) {
            InkBusyLabel(busy: busy, size: .inline, color: color ?? c.primary) {
                Text(text.sentenceCased)
                    .textStyle(style)
                    .textLines(1)
            }
            .foregroundStyle(enabled ? color ?? c.primary : c.onSurface.opacity(0.38))
            .padding(.horizontal, 12)
            .frame(minHeight: 40)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle())
        .disabled(!enabled || busy)
    }
}

/// Dims while pressed, like a printed block under a thumb.
struct PressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.opacity(configuration.isPressed ? 0.7 : 1)
    }
}

/// The lab's mark: a small solid square in the product's signal (Autobot: the scheme's primary).
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
    @Environment(\.scheme) private var c

    var body: some View { PixelSymbol(rows: Glyph.a, color: color ?? AutobotColors.purple, size: size) }
}

/// Invincible's symbol, the pixel i, in its orange: on anything about the node.
struct NodeMark: View {
    var size: CGFloat = 12
    @Environment(\.scheme) private var c

    var body: some View { PixelSymbol(rows: Glyph.i, color: c.node, size: size) }
}

/// The pixel a and the lowercase mono name: Autobot's wordmark.
struct Wordmark: View {
    var name = "autobot"
    var color: Color? = nil
    @Environment(\.scheme) private var c
    @Environment(\.dynamicTypeSize) private var dynamicType

    /// JetBrains Mono semibold, 17 pt at the default text size.
    static let style = Tokens.TypeStyle(font: .mono, size: 17, lineHeight: 22, weight: 600, letterSpacing: 0, trim: true, tabular: false)

    var body: some View {
        HStack(spacing: 8) {
            AutobotMark(size: 14, color: color)
            Text(name)
                .font(Font(AutobotFonts.uiFont(Self.style, dynamicType: dynamicType)))
                .foregroundStyle(c.onBackground)
                .lineLimit(1)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(name.prefix(1).uppercased() + name.dropFirst())
        .accessibilityAddTraits(.isHeader)
    }
}

/// The app icon (the pixel a in purple on white), for the places that show the icon itself.
struct AppIconImage: View {
    var size: CGFloat = Tokens.Spacing.markSize

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.22, style: .continuous).fill(Color.white)
            PixelSymbol(rows: Glyph.a, color: AutobotColors.purple, size: size * 0.5)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// Icon-only button (Material IconButton): 48pt target.
struct IconAction: View {
    enum Glyph {
        case icon(AutobotIcon)
        case symbol(String)
    }

    let glyph: Glyph
    let label: String
    var tint: Color? = nil
    var size: CGFloat = 24
    var enabled = true
    let action: () -> Void
    @Environment(\.scheme) private var c
    @Environment(\.dynamicTypeSize) private var dynamicType

    init(_ icon: AutobotIcon, label: String, tint: Color? = nil, size: CGFloat = 24, enabled: Bool = true, action: @escaping () -> Void) {
        self.glyph = .icon(icon)
        self.label = label
        self.tint = tint
        self.size = size
        self.enabled = enabled
        self.action = action
    }

    init(symbol: String, label: String, tint: Color? = nil, size: CGFloat = 20, enabled: Bool = true, action: @escaping () -> Void) {
        self.glyph = .symbol(symbol)
        self.label = label
        self.tint = tint
        self.size = size
        self.enabled = enabled
        self.action = action
    }

    var body: some View {
        let color = enabled ? (tint ?? c.onSurface) : c.onSurface.opacity(0.38)
        Button(action: action) {
            Group {
                switch glyph {
                case .icon(let icon): AutobotIconView(icon: icon, size: size, color: color)
                case .symbol(let name): Image(systemName: name).symbolSize(size, weight: .medium).foregroundStyle(color)
                }
            }
            // The target grows with the glyph, and never drops under 44 pt at the small sizes.
            .frame(width: 44 * max(1, AutobotFonts.iconScale(dynamicType)), height: 44 * max(1, AutobotFonts.iconScale(dynamicType)))
            .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle())
        .disabled(!enabled)
        .accessibilityLabel(label)
    }
}

/// A footer action laid out in 32 pt (its 44 pt target overhangs); at accessibility sizes it keeps
/// its full, grown size so the glyph does not spill over its neighbours.
private struct CompactActionFrame: ViewModifier {
    @Environment(\.dynamicTypeSize) private var dynamicType

    func body(content: Content) -> some View {
        if dynamicType.isAccessibilitySize { content } else { content.frame(width: 32, height: 32) }
    }
}

extension View {
    func compactActionFrame() -> some View { modifier(CompactActionFrame()) }
}

/**
 The small solid purple square that ends a top-level title ("Models▪"), as on the sites' h1:
 0.3 em, on the baseline, 0.15 em after the last letter. [fontSize] is the title's point size.
 */
struct TitleMark: View {
    var fontSize: CGFloat = 18

    var body: some View {
        Rectangle()
            .fill(AutobotColors.purple)
            .frame(width: (fontSize * 0.3).rounded(), height: (fontSize * 0.3).rounded())
            .accessibilityHidden(true)
    }
}

/// A screen title in sentence case, ending in the [TitleMark] when [mark] is set.
struct ScreenTitle: View {
    let text: String
    var style: AutobotType = .titleLarge
    var mark = true
    @Environment(\.scheme) private var c
    @Environment(\.dynamicTypeSize) private var dynamicType

    init(_ text: String, style: AutobotType = .titleLarge, mark: Bool = true) {
        self.text = text
        self.style = style
        self.mark = mark
    }

    var body: some View {
        let size = AutobotFonts.uiFont(style.token, dynamicType: dynamicType).pointSize
        HStack(alignment: .lastTextBaseline, spacing: (size * 0.15).rounded()) {
            Text(text)
                .textStyle(style)
                .foregroundStyle(c.onBackground)
                .textLines(1)
                .truncationMode(.tail)
                .layoutPriority(1)
            if mark {
                // Its bottom on the text baseline.
                TitleMark(fontSize: size).alignmentGuide(.lastTextBaseline) { d in d[.bottom] }
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
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
                        ScreenTitle(title, style: .titleMedium, mark: titleMark ?? true)
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

/// Small boxed counter (e.g. `16+` / running jobs).
struct CountBadge: View {
    let text: String
    @Environment(\.scheme) private var c

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .textStyle(.micro)
            .foregroundStyle(c.onSurface)
            .padding(.horizontal, Tokens.Spacing.tagPaddingHorizontal)
            .padding(.vertical, Tokens.Spacing.tagPaddingVertical)
            .overlay(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall).strokeBorder(c.outline, lineWidth: Tokens.Stroke.border))
    }
}

extension View {
    /**
     Keeps a halftone or living dot field from showing through this text: a soft-edged patch of
     [ground] (the surface under the field) right behind it. Static, so the field's animation does
     not re-render it.
     */
    func inkClearance(_ ground: Color, enabled: Bool = true) -> some View {
        background {
            if enabled {
                RoundedRectangle(cornerRadius: 4).fill(ground).padding(-4).blur(radius: 3).accessibilityHidden(true)
            }
        }
    }
}

/// Hairline-separated list row: title, mono subtitle, trailing slot.
struct RunRow<Leading: View, Trailing: View>: View {
    let title: String
    let subtitle: String
    var action: (() -> Void)? = nil
    @ViewBuilder var leading: () -> Leading
    @ViewBuilder var trailing: () -> Trailing
    @Environment(\.scheme) private var c

    init(
        title: String,
        subtitle: String,
        action: (() -> Void)? = nil,
        @ViewBuilder leading: @escaping () -> Leading,
        @ViewBuilder trailing: @escaping () -> Trailing,
    ) {
        self.title = title
        self.subtitle = subtitle
        self.action = action
        self.leading = leading
        self.trailing = trailing
    }

    var body: some View {
        VStack(spacing: 0) {
            let row = HStack(spacing: Tokens.Spacing.rowGap) {
                leading()
                VStack(alignment: .leading, spacing: Tokens.Spacing.rowTextGap) {
                    Text(title)
                        .textStyle(.titleMedium)
                        .foregroundStyle(c.onSurface)
                        .textLines(1)
                    if !subtitle.isEmpty {
                        // Separators hold to both neighbours: a wrap never leaves a `·` dangling at a line end.
                        Text(subtitle.replacingOccurrences(of: " · ", with: "\u{00A0}·\u{00A0}"))
                            .textStyle(.micro)
                            .foregroundStyle(c.onSurfaceVariant)
                            // A status line wraps once instead of losing its end on narrow phones.
                            .textLines(2, accessibility: 4)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                trailing()
            }
            .padding(.vertical, Tokens.Spacing.rowPaddingVertical)
            .contentShape(Rectangle())
            if let action {
                Button(action: action) { row }.buttonStyle(PressStyle())
            } else {
                row
            }
            Hairline()
        }
    }
}

extension RunRow where Leading == EmptyView {
    init(title: String, subtitle: String, action: (() -> Void)? = nil, @ViewBuilder trailing: @escaping () -> Trailing) {
        self.init(title: title, subtitle: subtitle, action: action, leading: { EmptyView() }, trailing: trailing)
    }
}

extension RunRow where Leading == EmptyView, Trailing == EmptyView {
    init(title: String, subtitle: String, action: (() -> Void)? = nil) {
        self.init(title: title, subtitle: subtitle, action: action, leading: { EmptyView() }, trailing: { EmptyView() })
    }
}

/// The trailing `›` of a row that opens a screen (decorative: the row is the button).
struct Chevron: View {
    @Environment(\.scheme) private var c

    var body: some View {
        Image(systemName: "chevron.right")
            .symbolSize(14, weight: .semibold)
            .foregroundStyle(c.onSurfaceVariant)
            .accessibilityHidden(true)
    }
}

/// `STEPS` over a big condensed number.
struct BigReadout: View {
    let label: String
    let value: String
    var action: (() -> Void)? = nil
    @Environment(\.scheme) private var c

    var body: some View {
        let content = VStack(alignment: .leading, spacing: Tokens.Spacing.readoutGap) {
            MicroLabel(label)
            Text(value)
                .textStyle(.number)
                .foregroundStyle(c.onSurface)
                .lineLimit(1)
        }
        if let action {
            Button(action: action) { content.contentShape(Rectangle()) }.buttonStyle(PressStyle())
        } else {
            content
        }
    }
}

/**
 An empty state (App UI v4): the purple pixel a, one plain sentence, at most one action.
 */
struct EmptyState: View {
    let text: String
    var actionLabel: String? = nil
    var action: (() -> Void)? = nil
    var markSize: CGFloat = 24
    @Environment(\.scheme) private var c

    init(_ text: String, actionLabel: String? = nil, markSize: CGFloat = 24, action: (() -> Void)? = nil) {
        self.text = text
        self.actionLabel = actionLabel
        self.markSize = markSize
        self.action = action
    }

    var body: some View {
        VStack(spacing: 14) {
            AutobotMark(size: markSize)
            Text(text)
                .textStyle(.bodyMedium)
                .foregroundStyle(c.onSurfaceVariant)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            // A hairline block button, as Android's empty states.
            if let actionLabel, let action {
                GhostButton(actionLabel, action: action).fixedSize()
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 32)
        .padding(.horizontal, Tokens.Spacing.gutter)
    }
}

/**
 An empty canvas (App UI v4): flat, the pixel a small and centred at low emphasis, and a one-line
 hint. Sits on the canvas' own surfaceContainer.
 */
struct EmptyCanvas: View {
    var hint: String? = nil
    @Environment(\.scheme) private var c

    var body: some View {
        VStack(spacing: 10) {
            AutobotMark(size: 18, color: AutobotColors.purple.opacity(0.55))
            if let hint {
                Text(hint)
                    .textStyle(.micro)
                    .foregroundStyle(c.onSurfaceVariant)
                    .multilineTextAlignment(.center)
                    .textLines(2)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .combine)
    }
}
