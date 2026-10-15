import SwiftUI

/// Uppercase, tracked mono caption: `// SAMPLER`, `CTX`, `SEED`.
struct MicroLabel: View {
    let text: String
    var color: Color? = nil
    var lines: Int = 1
    @Environment(\.scheme) private var c

    init(_ text: String, color: Color? = nil, lines: Int = 1) {
        self.text = text
        self.color = color
        self.lines = lines
    }

    var body: some View {
        Text(text.uppercased())
            .textStyle(.micro)
            .foregroundStyle(color ?? c.onSurfaceVariant)
            .lineLimit(lines)
            .truncationMode(.tail)
    }
}

/// 1pt separator in the outline-variant colour.
struct Hairline: View {
    var color: Color? = nil
    @Environment(\.scheme) private var c

    var body: some View {
        Rectangle()
            .fill(color ?? c.outlineVariant)
            .frame(height: Tokens.Stroke.hairline)
            .frame(maxWidth: .infinity)
    }
}

private struct HairlineEdge: ViewModifier {
    let top: Bool
    let bottom: Bool
    @Environment(\.scheme) private var c

    func body(content: Content) -> some View {
        content.overlay(alignment: .top) {
            if top { Rectangle().fill(c.outlineVariant).frame(height: Tokens.Stroke.hairline) }
        }
        .overlay(alignment: .bottom) {
            if bottom { Rectangle().fill(c.outlineVariant).frame(height: Tokens.Stroke.hairline) }
        }
    }
}

extension View {
    /// Draws a hairline along the top or bottom edge without affecting layout.
    func hairlineEdge(top: Bool = false, bottom: Bool = true) -> some View {
        modifier(HairlineEdge(top: top, bottom: bottom))
    }
}

/**
 Filled print block (no border). Optional [title] row renders as a micro-label header with
 [trailing] content (e.g. a readout or toggle) separated from the body by a hairline.
 */
struct Panel<Trailing: View, Content: View>: View {
    var title: String? = nil
    var accent: Color? = nil
    var contentPadding: CGFloat = Tokens.Spacing.panelPadding
    var hasTrailing = true
    @ViewBuilder var trailing: () -> Trailing
    @ViewBuilder var content: () -> Content
    @Environment(\.scheme) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if title != nil || hasTrailing {
                HStack(spacing: 8) {
                    if let accent { Rectangle().fill(accent).frame(width: Tokens.Spacing.panelAccent, height: Tokens.Spacing.panelAccent) }
                    if let title { MicroLabel(title).frame(maxWidth: .infinity, alignment: .leading) }
                    trailing()
                }
                .padding(.horizontal, contentPadding)
                .frame(minHeight: Tokens.Spacing.panelHeaderMinHeight)
                Hairline()
            }
            VStack(alignment: .leading, spacing: Tokens.Spacing.panelGap) { content() }
                .padding(contentPadding)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall).fill(c.surfaceContainer))
    }
}

extension Panel where Trailing == EmptyView {
    init(title: String? = nil, accent: Color? = nil, contentPadding: CGFloat = Tokens.Spacing.panelPadding, @ViewBuilder content: @escaping () -> Content) {
        self.init(title: title, accent: accent, contentPadding: contentPadding, hasTrailing: false, trailing: { EmptyView() }, content: content)
    }
}

/// Stacked label/value telemetry: `SEED` over `418229931`.
struct Readout: View {
    let label: String
    let value: String
    var valueColor: Color? = nil
    @Environment(\.scheme) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: Tokens.Spacing.readoutGap) {
            MicroLabel(label)
            Text(value)
                .textStyle(.readout)
                .foregroundStyle(valueColor ?? c.onSurface)
                .lineLimit(1)
        }
    }
}

/// Square-cornered tag, e.g. `[TOOLS]`, `LORA 0.80`.
struct Tag: View {
    let text: String
    var accent: Color? = nil
    var filled = false
    /// Work it started runs: an ink loader stands in for the text (the tag keeps its size).
    var busy = false
    var action: (() -> Void)? = nil
    @Environment(\.scheme) private var c

    init(_ text: String, accent: Color? = nil, filled: Bool = false, busy: Bool = false, action: (() -> Void)? = nil) {
        self.text = text
        self.accent = accent
        self.filled = filled
        self.busy = busy
        self.action = action
    }

    var body: some View {
        let tint = accent ?? c.onSurfaceVariant
        // Filled tags are solid blocks; pick ink or bone text for contrast.
        let textColor = !filled ? tint : (tint.luminance() > 0.45 ? AutobotColors.paperInk : AutobotColors.bone100)
        let label = InkBusyLabel(busy: busy, size: .inline, color: textColor) {
            Text(text.uppercased())
                .textStyle(.micro)
                .lineLimit(1)
                .foregroundStyle(textColor)
        }
        .padding(.horizontal, Tokens.Spacing.tagPaddingHorizontal)
        .padding(.vertical, Tokens.Spacing.tagPaddingVertical)
        .background {
            let shape = RoundedRectangle(cornerRadius: AutobotShapes.extraSmall)
            if filled { shape.fill(tint) } else { shape.strokeBorder(tint.opacity(0.55), lineWidth: Tokens.Stroke.border) }
        }
        .fixedSize()
        if let action {
            Button(action: action) { label.contentShape(Rectangle()) }.buttonStyle(PressStyle()).disabled(busy)
        } else {
            label
        }
    }
}

/// Status dot; pulses while [live].
struct StatusDot: View {
    let color: Color
    var live = false
    var size: CGFloat = Tokens.Spacing.statusDot
    @State private var dim = false

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: size, height: size)
            .opacity(live && dim ? Tokens.Motion.statusDotPulseMinAlpha : 1)
            .animation(live ? .easeInOut(duration: Tokens.Motion.statusDotPulseMillis / 1000).repeatForever(autoreverses: true) : .default, value: dim)
            .onAppear { if live { dim = true } }
            .onChange(of: live) { _, isLive in dim = isLive }
    }
}

/// Riso slider: red active track, hairline inactive track, square red thumb bar.
struct RisoSlider: View {
    @Binding var value: Double
    let range: ClosedRange<Double>
    var step: Double? = nil
    var enabled = true
    @Environment(\.scheme) private var c

    var body: some View {
        GeometryReader { geo in
            let width = max(1, geo.size.width)
            let fraction = (value - range.lowerBound) / max(range.upperBound - range.lowerBound, .ulpOfOne)
            let x = CGFloat(min(max(fraction, 0), 1)) * width
            ZStack(alignment: .leading) {
                Rectangle().fill(c.outlineVariant).frame(height: 4)
                Rectangle().fill(enabled ? c.primary : c.outline).frame(width: x, height: 4)
                Rectangle().fill(enabled ? c.primary : c.outline).frame(width: 4, height: 20).offset(x: min(max(x - 2, 0), width - 4))
            }
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { g in
                        guard enabled else { return }
                        let f = min(max(g.location.x / width, 0), 1)
                        var v = range.lowerBound + Double(f) * (range.upperBound - range.lowerBound)
                        if let step, step > 0 { v = (v / step).rounded() * step }
                        value = min(max(v, range.lowerBound), range.upperBound)
                    },
            )
        }
        .frame(height: Tokens.Spacing.sliderHeight)
        .accessibilityElement()
        .accessibilityValue(String(format: "%.2f", value))
        .accessibilityAdjustableAction { direction in
            let delta = step ?? (range.upperBound - range.lowerBound) / 20
            switch direction {
            case .increment: value = min(value + delta, range.upperBound)
            case .decrement: value = max(value - delta, range.lowerBound)
            @unknown default: break
            }
        }
    }
}

/// Labelled slider with a live mono readout: `CFG ············ 7.0`.
struct ValueSlider: View {
    let label: String
    @Binding var value: Double
    let range: ClosedRange<Double>
    var step: Double? = nil
    var enabled = true
    var format: (Double) -> String = { String(format: "%.2f", $0) }
    @Environment(\.scheme) private var c

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                MicroLabel(label).frame(maxWidth: .infinity, alignment: .leading)
                Text(format(value)).textStyle(.readout).foregroundStyle(c.primary)
            }
            RisoSlider(value: $value, range: range, step: step, enabled: enabled)
        }
        .accessibilityLabel(label)
    }
}

/// Single-select segmented row: `[ TXT2IMG | IMG2IMG | INPAINT ]`. The selection block slides
/// with ease-out and leaves a one-frame registration ghost.
struct Segmented<T: Hashable>: View {
    let options: [T]
    let selected: T
    let label: (T) -> String
    let onSelect: (T) -> Void
    @Environment(\.scheme) private var c
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Namespace private var marker
    @State private var ghost = InkSelectionGhost()

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Array(options.enumerated()), id: \.offset) { index, option in
                if index > 0 { Rectangle().fill(c.outlineVariant).frame(width: Tokens.Stroke.hairline, height: Tokens.Spacing.segmentedHeight) }
                let isSelected = option == selected
                Button { onSelect(option) } label: {
                    Text(label(option).uppercased())
                        .textStyle(.micro)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                        .foregroundStyle(isSelected ? c.onPrimary : c.onSurfaceVariant)
                        .frame(maxWidth: .infinity, minHeight: Tokens.Spacing.segmentedHeight, maxHeight: Tokens.Spacing.segmentedHeight)
                        .background {
                            if ghost.key == AnyHashable(option) {
                                Rectangle().fill(c.primary.opacity(0.35)).offset(x: Tokens.Motion.registerOffset, y: Tokens.Motion.registerOffset)
                            }
                            if isSelected { Rectangle().fill(c.primary).matchedGeometryEffect(id: "selection", in: marker) }
                        }
                        .contentShape(Rectangle())
                }
                .buttonStyle(PressStyle())
                .accessibilityAddTraits(isSelected ? .isSelected : [])
            }
        }
        .animation(reduceMotion ? .inkReduced : .inkSlide, value: selected)
        .onChange(of: selected) { old, _ in ghost.moved(from: AnyHashable(old), reduceMotion: reduceMotion) }
        .clipShape(RoundedRectangle(cornerRadius: AutobotShapes.small))
        .overlay(RoundedRectangle(cornerRadius: AutobotShapes.small).strokeBorder(c.outlineVariant, lineWidth: Tokens.Stroke.border))
    }
}

enum BlockStatus: String, CaseIterable {
    case pending = "PENDING", running = "RUNNING", ok = "OK", error = "ERROR", denied = "DENIED"

    var color: Color {
        switch self {
        case .pending: AutobotColors.amber
        case .running: AutobotColors.uv
        case .ok: AutobotColors.acid
        case .error: AutobotColors.signal
        case .denied: AutobotColors.ash400
        }
    }
}

/**
 Terminal-style block for tool invocations: a header line (`▸ fs.read  path=notes.md`) with a
 status dot, and an optional collapsible mono body with the output.
 */
struct TerminalBlock<Actions: View>: View {
    let title: String
    let status: BlockStatus
    var subtitle: String? = nil
    var bodyText: String? = nil
    var expanded = false
    var onToggle: (() -> Void)? = nil
    var hasActions = true
    @ViewBuilder var actions: () -> Actions
    @Environment(\.scheme) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            let header = HStack(spacing: 8) {
                StatusDot(color: status.color, live: status == .running || status == .pending)
                Text("▸ \(title)").textStyle(.code).foregroundStyle(c.onSurface).lineLimit(1)
                Text(subtitle ?? "")
                    .textStyle(.codeBlock)
                    .foregroundStyle(c.onSurfaceVariant)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                MicroLabel(status.rawValue, color: status.color)
            }
            .padding(.horizontal, Tokens.Spacing.terminalPaddingHorizontal)
            .padding(.vertical, Tokens.Spacing.terminalPaddingVertical)
            .contentShape(Rectangle())
            if let onToggle {
                Button(action: onToggle) { header }.buttonStyle(PressStyle())
            } else {
                header
            }
            if expanded, let bodyText, !bodyText.isEmpty {
                Hairline()
                ScrollView(.horizontal, showsIndicators: false) {
                    Text(bodyText)
                        .textStyle(.codeBlock)
                        .foregroundStyle(c.onSurfaceVariant)
                        .fixedSize(horizontal: true, vertical: false)
                        .padding(10)
                }
            }
            if hasActions {
                Hairline()
                HStack { Spacer(); actions() }
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: AutobotShapes.small).fill(c.surfaceContainerLowest))
        .overlay(RoundedRectangle(cornerRadius: AutobotShapes.small).strokeBorder(c.outlineVariant, lineWidth: Tokens.Stroke.border))
    }
}

extension TerminalBlock where Actions == EmptyView {
    init(title: String, status: BlockStatus, subtitle: String? = nil, bodyText: String? = nil, expanded: Bool = false, onToggle: (() -> Void)? = nil) {
        self.init(title: title, status: status, subtitle: subtitle, bodyText: bodyText, expanded: expanded, onToggle: onToggle, hasActions: false, actions: { EmptyView() })
    }
}
