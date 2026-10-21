import SwiftUI

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
