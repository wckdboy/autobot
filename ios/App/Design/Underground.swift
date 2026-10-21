import SwiftUI
import UIKit

/// Sentence-case mono caption, untracked: `Sampler`, `Seed`, `Installed · 2`.
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
        Text(text.sentenceCased)
            .textStyle(.micro)
            .foregroundStyle(color ?? c.onSurfaceVariant)
            .textLines(lines)
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
                    // v4: no decorative squares; only a warning or an error keeps its mark.
                    if let accent, accent == AutobotColors.amber || accent == c.error || accent == c.inks.amber {
                        Rectangle().fill(accent).frame(width: Tokens.Spacing.panelAccent, height: Tokens.Spacing.panelAccent)
                    }
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

/**
 Square-cornered tag, e.g. `tools`, `lora 0.80`. Information tags stay lowercase mono; a tag with
 an [action] is a small button, so its label is sentence case. [filled] in a neutral or the primary
 accent marks a selection (the shared v4 selection, as on Android: onSurface medium text over a
 2 pt purple bar); in another accent (amber, the error red) it is a solid block.
 */
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
        let selection = filled && [c.primary, c.onSurface, c.onSurfaceVariant, c.secondary].contains(tint)
        // Solid blocks take ink or bone text, whichever reads better on the fill (ink on the
        // amber and the error red, where bone is under 3.5:1).
        let textColor = selection ? c.onSurface
            : !filled ? (tint == c.primary ? c.onSurface : tint)
            : (tint.luminance() > 0.18 ? AutobotColors.paperInk : AutobotColors.bone100)
        let label = InkBusyLabel(busy: busy, size: .inline, color: textColor) {
            // A meta chip (`forge · desktop`) stays lowercase even when it is tappable.
            Text(action == nil || text.contains(" · ") ? text : text.sentenceCased)
                .textStyle(.micro, weight: selection ? 600 : nil)
                .textLines(1)
                .foregroundStyle(textColor)
        }
        .padding(.horizontal, Tokens.Spacing.tagPaddingHorizontal)
        .padding(.vertical, Tokens.Spacing.tagPaddingVertical)
        .background {
            let shape = RoundedRectangle(cornerRadius: AutobotShapes.extraSmall)
            if selection {
                shape.strokeBorder(c.outline, lineWidth: Tokens.Stroke.border)
            } else if filled {
                shape.fill(tint)
            } else {
                // An outlined tag in the primary is a quiet action: onSurface text in a stronger hairline.
                shape.strokeBorder(tint == c.primary ? c.outline : c.outlineVariant, lineWidth: Tokens.Stroke.border)
            }
        }
        .selectionBar(selection)
        .fixedSize()
        if let action {
            Button(action: action) { label.contentShape(Rectangle()) }.buttonStyle(PressStyle()).disabled(busy)
        } else {
            label
        }
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
                Text(format(value)).textStyle(.readout).foregroundStyle(c.onSurface)
            }
            RisoSlider(value: $value, range: range, step: step, enabled: enabled)
        }
        .accessibilityLabel(label)
    }
}

/**
 Single-select segmented row: `[ txt2img | img2img | inpaint ]` in sentence-case mono. The
 selection is the shared v4 selection (as on Android): onSurface text in medium weight over a
 2 pt purple bar; the rest onSurfaceVariant, inside a 1 pt outlineVariant hairline with 4 pt
 corners. The bar slides with ease-out.
 */
struct Segmented<T: Hashable>: View {
    let options: [T]
    let selected: T
    let label: (T) -> String
    let onSelect: (T) -> Void
    @Environment(\.scheme) private var c
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dynamicType
    @Namespace private var marker

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Array(options.enumerated()), id: \.offset) { index, option in
                if index > 0 { Rectangle().fill(c.outlineVariant).frame(width: Tokens.Stroke.hairline).frame(maxHeight: .infinity) }
                let isSelected = option == selected
                Button { onSelect(option) } label: {
                    Text(label(option).sentenceCased)
                        .textStyle(.micro, weight: isSelected ? 600 : nil)
                        // At accessibility sizes a label wraps (the control grows) before it shrinks.
                        .lineLimit(dynamicType.isAccessibilitySize ? 3 : 1)
                        .multilineTextAlignment(.center)
                        .minimumScaleFactor(0.7)
                        .foregroundStyle(isSelected ? c.onSurface : c.onSurfaceVariant)
                        .padding(.vertical, dynamicType.isAccessibilitySize ? 6 : 0)
                        .frame(maxWidth: .infinity, minHeight: Tokens.Spacing.segmentedHeight)
                        .background {
                            if isSelected {
                                VStack(spacing: 0) {
                                    Spacer(minLength: 0)
                                    Rectangle().fill(AutobotColors.purple).frame(height: SelectionBar.height).padding(.horizontal, SelectionBar.segmentInset)
                                }
                                .matchedGeometryEffect(id: "selection", in: marker)
                            }
                        }
                        .contentShape(Rectangle())
                }
                .buttonStyle(PressStyle())
                .accessibilityAddTraits(isSelected ? .isSelected : [])
            }
        }
        .fixedSize(horizontal: false, vertical: true)
        .animation(reduceMotion ? .inkReduced : .inkSlide, value: selected)
        .clipShape(RoundedRectangle(cornerRadius: AutobotShapes.medium))
        .overlay(RoundedRectangle(cornerRadius: AutobotShapes.medium).strokeBorder(c.outlineVariant, lineWidth: Tokens.Stroke.border))
    }
}

/// The shared v4 selection mark: a 2 pt purple bar along the bottom edge.
enum SelectionBar {
    static let height: CGFloat = 2
    /// Inset from a segment's sides.
    static let segmentInset: CGFloat = 12
}

private struct SelectionBarModifier: ViewModifier {
    let selected: Bool
    var inset: CGFloat = 0

    func body(content: Content) -> some View {
        content.overlay(alignment: .bottom) {
            if selected { Rectangle().fill(AutobotColors.purple).frame(height: SelectionBar.height).padding(.horizontal, inset) }
        }
    }
}

extension View {
    /// Draws the 2 pt purple selection bar along the bottom edge while [selected] (layout unchanged).
    func selectionBar(_ selected: Bool, inset: CGFloat = 0) -> some View { modifier(SelectionBarModifier(selected: selected, inset: inset)) }
}
