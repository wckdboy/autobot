import SwiftUI

/*
 The system pieces the converted screens share: standard SwiftUI controls with Liquid Glass where
 the SDK and the OS have it, and the brand only through the tint, the pixel a and the purple dot
 after a title. Nothing here draws its own chrome.
 */

/// A screen title in the system's headline style, ending in the purple pixel dot ([TitleMark]).
struct TitleWithDot: View {
    let text: String
    var font: Font = .headline
    var mark = true

    init(_ text: String, font: Font = .headline, mark: Bool = true) {
        self.text = text
        self.font = font
        self.mark = mark
    }

    @ScaledMetric(relativeTo: .headline) private var size: CGFloat = 17

    var body: some View {
        HStack(alignment: .lastTextBaseline, spacing: (size * 0.15).rounded()) {
            Text(text)
                .font(font)
                .lineLimit(1)
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

/**
 Where a run goes, as a small capsule tag: `● local · npu`, `◐ pc · lan`, `○ cloud · api`. The
 glyph says where (filled: on this device); the words are the same as [RouteTag]'s.
 */
struct RouteBadge: View {
    let route: Route

    var body: some View {
        Text("\(route.glyph) \(route.label)")
            .font(.caption)
            .lineLimit(1)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .foregroundStyle(.tint)
            .background(Capsule().fill(.tint.opacity(0.15)))
            .fixedSize()
            .accessibilityLabel("Runs \(route.label.replacingOccurrences(of: " · ", with: ", "))")
    }
}

/// A small capsule tag in the tint (`3 live`, `interrupted`), or in [color].
struct CapsuleTag: View {
    let text: String
    var color: Color? = nil

    init(_ text: String, color: Color? = nil) {
        self.text = text
        self.color = color
    }

    var body: some View {
        Text(text)
            .font(.caption)
            .lineLimit(1)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .foregroundStyle(color.map(AnyShapeStyle.init) ?? AnyShapeStyle(.tint))
            .background(Capsule().fill(color.map { AnyShapeStyle($0.opacity(0.15)) } ?? AnyShapeStyle(.tint.opacity(0.15))))
            .fixedSize()
    }
}

/// An empty state: the system's unavailable view with the purple pixel a, one sentence, and at most one action.
struct MarkUnavailable: View {
    let title: String
    var description: String? = nil
    var actionLabel: String? = nil
    var action: (() -> Void)? = nil

    init(_ title: String, description: String? = nil, actionLabel: String? = nil, action: (() -> Void)? = nil) {
        self.title = title
        self.description = description
        self.actionLabel = actionLabel
        self.action = action
    }

    var body: some View {
        ContentUnavailableView {
            Label {
                Text(title)
            } icon: {
                AutobotMark(size: 36)
            }
        } description: {
            if let description { Text(description) }
        } actions: {
            if let actionLabel, let action {
                Button(actionLabel, action: action).glassButton()
            }
        }
    }
}

extension View {
    /// The primary action's button style: Liquid Glass, prominent, where available (bordered prominent before iOS 26).
    @ViewBuilder func glassProminentButton() -> some View {
        if #available(iOS 26.0, *) {
            buttonStyle(.glassProminent)
        } else {
            buttonStyle(.borderedProminent)
        }
    }

    /// A secondary action's button style: Liquid Glass where available (bordered before iOS 26).
    @ViewBuilder func glassButton() -> some View {
        if #available(iOS 26.0, *) {
            buttonStyle(.glass)
        } else {
            buttonStyle(.bordered)
        }
    }

    /// A Liquid Glass ground in [shape] (a bar or a card that floats over content); the system's
    /// bar material before iOS 26.
    @ViewBuilder func glassSurface(in shape: some Shape = RoundedRectangle(cornerRadius: 24, style: .continuous), interactive: Bool = false) -> some View {
        if #available(iOS 26.0, *) {
            glassEffect(interactive ? .regular.interactive() : .regular, in: shape)
        } else {
            background(.bar, in: shape)
        }
    }

    /// A small icon action under a message (copy, share, retry, read aloud): borderless, 32 pt,
    /// secondary, in the tint while [active].
    func messageAction(active: Bool = false) -> some View {
        buttonStyle(.borderless)
            .font(.body)
            .foregroundStyle(active ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
            .frame(minWidth: 32, minHeight: 32)
            .contentShape(Rectangle())
    }

    /// A capsule chip that switches something (a tap, sometimes a long press): tinted while [active].
    func chipLook(active: Bool) -> some View {
        font(.caption.weight(.medium))
            .lineLimit(1)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .foregroundStyle(active ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
            .background(Capsule().fill(active ? AnyShapeStyle(.tint.opacity(0.15)) : AnyShapeStyle(Color(.tertiarySystemFill))))
            .contentShape(Capsule())
    }

    /// A bounded reading width centred in a wide window (iPad, Mac): text lines stay readable.
    func readableWidth(_ max: CGFloat = 760) -> some View {
        frame(maxWidth: max).frame(maxWidth: .infinity)
    }
}
