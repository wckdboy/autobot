import SwiftUI

/// One destination in [AutobotNavBar].
struct NavItem<K: Hashable>: Hashable {
    let key: K
    let label: String
}

/**
 Bottom navigation as in the print mockups: mono labels only, a red dot above the active one,
 a hairline on top. The dot slides to a new tab with ease-out and leaves a one-frame
 registration ghost.
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
                        ZStack {
                            if ghost.key == AnyHashable(item.key) {
                                Circle().fill(c.primary.opacity(0.4)).offset(x: Tokens.Motion.registerOffset, y: Tokens.Motion.registerOffset)
                            }
                            if active { Circle().fill(c.primary).matchedGeometryEffect(id: "dot", in: marker) }
                        }
                        .frame(width: Tokens.Spacing.navDot, height: Tokens.Spacing.navDot)
                        Spacer().frame(height: Tokens.Spacing.navDotGap)
                        Text(item.label.uppercased())
                            .textStyle(.labelMedium)
                            .foregroundStyle(active ? c.onBackground : c.onSurfaceVariant)
                    }
                    .frame(maxWidth: .infinity, minHeight: Tokens.Spacing.navBarHeight, maxHeight: Tokens.Spacing.navBarHeight)
                    .contentShape(Rectangle())
                }
                .buttonStyle(PressStyle())
                .accessibilityAddTraits(active ? [.isSelected, .isButton] : .isButton)
            }
        }
        .animation(reduceMotion ? .inkReduced : .inkSlide, value: selected)
        .onChange(of: selected) { old, _ in if let old { ghost.moved(from: AnyHashable(old), reduceMotion: reduceMotion) } }
        .background(c.background)
        .hairlineEdge(top: true, bottom: false)
    }
}
