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
}

/// `● LOCAL · CPU` (filled red) / `◐ PC · LAN` / `○ CLOUD · API` (outlined).
struct RouteTag: View {
    let route: Route
    @Environment(\.scheme) private var c

    var body: some View {
        Text("\(route.glyph) \(route.label)".uppercased())
            .textStyle(.micro)
            .lineLimit(1)
            .foregroundStyle(route.local ? c.onPrimary : c.onSurface)
            .padding(.horizontal, Tokens.Spacing.tagPaddingHorizontal)
            .padding(.vertical, Tokens.Spacing.tagPaddingVertical)
            .background {
                let shape = RoundedRectangle(cornerRadius: AutobotShapes.extraSmall)
                if route.local {
                    shape.fill(c.primary)
                } else {
                    shape.strokeBorder(c.outline, lineWidth: Tokens.Stroke.border)
                }
            }
            .fixedSize()
    }
}

/// Red `// SECTION · DETAIL` line.
struct SectionLabel: View {
    let text: String
    var color: Color? = nil
    @Environment(\.scheme) private var c

    init(_ text: String, color: Color? = nil) {
        self.text = text
        self.color = color
    }

    var body: some View {
        Text("// \(text.uppercased())")
            .textStyle(.micro)
            .foregroundStyle(color ?? c.primary)
            .lineLimit(1)
            .truncationMode(.tail)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Full-width red block button with a mono caps label.
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
                Text(text.uppercased())
                    .textStyle(.labelLarge)
                    .lineLimit(1)
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
                Text(text.uppercased())
                    .textStyle(.labelLarge)
                    .lineLimit(1)
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

/// Cream block button (APPROVE on red cards).
struct PaperButton: View {
    let text: String
    let action: () -> Void

    init(_ text: String, action: @escaping () -> Void) {
        self.text = text
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Text(text.uppercased())
                .textStyle(.labelLarge)
                .lineLimit(1)
                .frame(maxWidth: .infinity, minHeight: Tokens.Spacing.ghostButtonMinHeight)
                .foregroundStyle(AutobotColors.paperInk)
                .background(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall).fill(AutobotColors.bone100))
                .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle())
    }
}

/// Small mono text button (`ALL →`, `TUNE`, `OK`): Material's TextButton in the riso type.
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
        Button(action: action) {
            InkBusyLabel(busy: busy, size: .inline, color: color ?? c.primary) {
                Text(text.uppercased())
                    .textStyle(style)
                    .lineLimit(1)
            }
            .foregroundStyle(enabled ? (color ?? c.primary) : c.onSurface.opacity(0.38))
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

/// The brand mark (halftone "a" on red).
struct AutobotMark: View {
    var size: CGFloat = Tokens.Spacing.markSize

    var body: some View {
        Image("AutobotMark")
            .resizable()
            .interpolation(.high)
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
                case .symbol(let name): Image(systemName: name).font(.system(size: size, weight: .medium)).foregroundStyle(color)
                }
            }
            .frame(width: 44, height: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle())
        .disabled(!enabled)
        .accessibilityLabel(label)
    }
}

/// Top bar: mark + condensed wordmark (or a title) and trailing actions.
struct AutobotTopBar<Actions: View>: View {
    var title: String = "AUTOBOT"
    var showMark = true
    var onBack: (() -> Void)? = nil
    @ViewBuilder var actions: () -> Actions
    @Environment(\.scheme) private var c

    var body: some View {
        HStack(spacing: Tokens.Spacing.topBarGap) {
            if let onBack {
                IconAction(symbol: "arrow.left", label: "Back", action: onBack)
            } else if showMark {
                AutobotMark()
            }
            Text(title.uppercased())
                .textStyle(.titleLarge)
                .foregroundStyle(c.onBackground)
                .lineLimit(1)
                .truncationMode(.tail)
                .titleGhost(c.primary)
                .frame(maxWidth: .infinity, alignment: .leading)
            actions()
        }
        .padding(.leading, onBack == nil ? Tokens.Spacing.topBarPaddingStart : 4)
        .padding(.trailing, Tokens.Spacing.topBarPaddingEnd)
        .frame(minHeight: Tokens.Spacing.topBarMinHeight)
        .background(c.background)
    }
}

extension AutobotTopBar where Actions == EmptyView {
    init(title: String = "AUTOBOT", showMark: Bool = true, onBack: (() -> Void)? = nil) {
        self.init(title: title, showMark: showMark, onBack: onBack, actions: { EmptyView() })
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

/// A mode tile on the Run screen: `03` / big title / model / route tag.
struct ModeCard<Background: View>: View {
    let index: String
    let title: String
    let subtitle: String
    let route: Route?
    let action: () -> Void
    @ViewBuilder var art: () -> Background
    var hasArt = true
    @Environment(\.scheme) private var c

    var body: some View {
        let card = c.surfaceContainerHigh
        Button(action: action) {
            ZStack(alignment: .topLeading) {
                card
                if hasArt {
                    art()
                    // Fade the art into the card colour so the title stays legible over dense dots.
                    LinearGradient(
                        stops: [
                            .init(color: .clear, location: Tokens.Halftone.modeCardFadeStart),
                            .init(color: card.opacity(Tokens.Halftone.modeCardFadeMidAlpha), location: Tokens.Halftone.modeCardFadeMid),
                            .init(color: card, location: 1),
                        ],
                        startPoint: .top,
                        endPoint: .bottom,
                    )
                }
                VStack(alignment: .leading, spacing: 0) {
                    HStack(alignment: .center) {
                        Text(index)
                            .textStyle(.micro)
                            .foregroundStyle(c.onSurfaceVariant)
                            .padding(.horizontal, hasArt ? 4 : 0)
                            .padding(.vertical, hasArt ? 2 : 0)
                            .background(hasArt ? card : .clear)
                        Spacer(minLength: 0)
                        if let route { RouteTag(route: route) }
                    }
                    Spacer(minLength: 0)
                    Text(title)
                        .textStyle(.headlineMedium)
                        .foregroundStyle(c.onSurface)
                        .lineLimit(1)
                    Text(subtitle)
                        .textStyle(.micro)
                        .foregroundStyle(c.onSurfaceVariant)
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
                .padding(Tokens.Spacing.cardPadding)
            }
            .clipShape(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall))
            .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle())
    }
}

extension ModeCard where Background == EmptyView {
    init(index: String, title: String, subtitle: String, route: Route?, action: @escaping () -> Void) {
        self.init(index: index, title: title, subtitle: subtitle, route: route, action: action, art: { EmptyView() }, hasArt: false)
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
                        .lineLimit(1)
                    Text(subtitle)
                        .textStyle(.micro)
                        .foregroundStyle(c.onSurfaceVariant)
                        .lineLimit(1)
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
