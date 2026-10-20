import SwiftUI

/// One destination in [AutobotNavBar].
struct NavItem<K: Hashable>: Hashable {
    let key: K
    let label: String
}

/**
 Bottom navigation: sentence-case mono labels (11 pt), a 2 pt purple bar 6 pt under the active one
 (as Android's `AutobotNavBar`), a hairline on top. Active = onSurface medium, inactive =
 onSurfaceVariant. The bar slides to a new tab with ease-out and leaves a one-frame registration ghost.
 */
struct AutobotNavBar<K: Hashable>: View {
    let items: [NavItem<K>]
    let selected: K?
    let onSelect: (K) -> Void
    @Environment(\.scheme) private var c
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Namespace private var marker
    @State private var ghost = InkSelectionGhost()

    var body: some View {
        HStack(spacing: 0) {
            ForEach(items, id: \.self) { item in
                let active = item.key == selected
                Button { onSelect(item.key) } label: {
                    VStack(spacing: 0) {
                        // Balances the bar below, so the label sits on the bar's centre line.
                        Spacer().frame(height: Self.barHeight + Self.barGap)
                        Text(item.label.sentenceCased)
                            .textStyle(.labelSmall, weight: active ? 500 : 400)
                            .lineLimit(1)
                            // Full size: a scale factor here shrank the labels to 60 % and pushed them off centre.
                            .fixedSize()
                            .foregroundStyle(active ? c.onBackground : c.onSurfaceVariant)
                        Spacer().frame(height: Self.barGap)
                        ZStack {
                            if ghost.key == AnyHashable(item.key) {
                                Rectangle().fill(AutobotColors.purple.opacity(0.4)).offset(x: Tokens.Motion.registerOffset, y: Tokens.Motion.registerOffset)
                            }
                            if active { Rectangle().fill(AutobotColors.purple).matchedGeometryEffect(id: "bar", in: marker) }
                        }
                        .frame(width: Self.barWidth, height: Self.barHeight)
                    }
                    .frame(maxWidth: .infinity, minHeight: Tokens.Spacing.navBarHeight)
                    .contentShape(Rectangle())
                }
                .buttonStyle(PressStyle())
                // A tab bar holds four labels at any size: past xxxLarge a long press shows the
                // label in the large content viewer, as the system tab bar does.
                .accessibilityShowsLargeContentViewer { Text(item.label.sentenceCased) }
                .accessibilityAddTraits(active ? [.isSelected, .isButton] : .isButton)
            }
        }
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
        .animation(reduceMotion ? .inkReduced : .inkSlide, value: selected)
        .onChange(of: selected) { old, _ in if let old { ghost.moved(from: AnyHashable(old), reduceMotion: reduceMotion) } }
        .background(c.background)
        .hairlineEdge(top: true, bottom: false)
    }

    /// Android's `TabBarWidth` and `SelectionBarHeight`, and the 6 dp between label and bar.
    static var barWidth: CGFloat { 20 }
    static var barHeight: CGFloat { 2 }
    static var barGap: CGFloat { 6 }
}

