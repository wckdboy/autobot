import SwiftUI

/// One destination in [AutobotNavBar].
struct NavItem<K: Hashable>: Hashable {
    let key: K
    let label: String
}

/**
 Bottom navigation as in the print mockups: mono labels only, a red dot above the active one,
 a hairline on top.
 */
struct AutobotNavBar<K: Hashable>: View {
    let items: [NavItem<K>]
    let selected: K?
    let onSelect: (K) -> Void
    @Environment(\.scheme) private var c

    var body: some View {
        HStack(spacing: 0) {
            ForEach(items, id: \.self) { item in
                let active = item.key == selected
                Button { onSelect(item.key) } label: {
                    VStack(spacing: 0) {
                        Circle()
                            .fill(active ? c.primary : .clear)
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
        .background(c.background)
        .hairlineEdge(top: true, bottom: false)
    }
}
