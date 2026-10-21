import SwiftUI

/*
 The system pieces the converted screens share: standard SwiftUI controls with Liquid Glass where
 the SDK and the OS have it, and the brand only through the tint, the pixel a and the purple dot
 after a title. Nothing here draws its own chrome. Everything else is plain SwiftUI (`Form`, `List`, `Section`,
 `Toggle`, `Picker`, `LabeledContent`).
 */

extension Color {
    /// The app's tint: the brand purple, deeper on light grounds for contrast (#6A4BEB light,
    /// #9C85FF dark; the `BrandPurple` colour set, also the asset catalog's accent colour).
    static let brandPurple = Color("BrandPurple")
}

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

/// An empty state: the system's unavailable view with the purple pixel a, one sentence, and at
/// most one action (a glass button).
struct MarkUnavailable<Actions: View>: View {
    let title: String
    var description: String? = nil
    @ViewBuilder var actions: () -> Actions

    init(_ title: String, description: String? = nil, @ViewBuilder actions: @escaping () -> Actions) {
        self.title = title
        self.description = description
        self.actions = actions
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
            actions()
        }
    }
}

extension MarkUnavailable where Actions == EmptyView {
    init(_ title: String, description: String? = nil) {
        self.init(title, description: description, actions: { EmptyView() })
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

/// An ⓘ after a row's title: the why, one tap away, in a popover (keeps rows to one or two words).
struct RowInfo: View {
    let title: String
    let text: String
    @State private var shown = false

    var body: some View {
        Button { shown = true } label: {
            Image(systemName: "info.circle")
                .imageScale(.medium)
                // A concrete colour: a borderless button would tint a hierarchical style.
                .foregroundStyle(Color(uiColor: .secondaryLabel))
        }
        .buttonStyle(.borderless)
        .accessibilityLabel("About \(title)")
        .popover(isPresented: $shown) {
            Text(text)
                .font(.callout)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: 300, alignment: .leading)
                .padding(16)
                .presentationCompactAdaptation(.popover)
        }
    }
}

/// A row title with an optional ⓘ after it.
struct InfoTitle: View {
    let title: String
    var info: String? = nil

    init(_ title: String, info: String? = nil) {
        self.title = title
        self.info = info
    }

    var body: some View {
        HStack(spacing: 6) {
            Text(title)
            if let info { RowInfo(title: title, text: info) }
        }
    }
}

/**
 A Form text field with the keyboard of its [kind] (as `ConsoleTextField`): mono, URL, number and
 secret fields turn off auto-capitalization and autocorrection; secret fields are masked and
 never offered to password managers. [label] is the field's title and, without a [prompt], its
 placeholder; with a prompt the label leads the row (`LabeledContent`).
 */
struct FormField: View {
    let label: String
    @Binding var text: String
    var prompt: String? = nil
    var kind: FieldKind = .prose
    var axis: Axis = .horizontal

    init(_ label: String, text: Binding<String>, prompt: String? = nil, kind: FieldKind = .prose, axis: Axis = .horizontal) {
        self.label = label
        self._text = text
        self.prompt = prompt
        self.kind = kind
        self.axis = axis
    }

    var body: some View {
        if let prompt {
            LabeledContent(label) { field(prompt).multilineTextAlignment(.trailing) }
        } else {
            field(label)
        }
    }

    @ViewBuilder private func field(_ placeholder: String) -> some View {
        Group {
            if kind == .secret {
                SecureField(label, text: $text, prompt: Text(placeholder))
                    .textContentType(.oneTimeCode)
            } else {
                TextField(label, text: $text, prompt: Text(placeholder), axis: axis)
            }
        }
        .autocorrectionDisabled(kind != .prose)
        .textInputAutocapitalization(kind == .prose ? .sentences : .never)
        .keyboardType(kind == .url ? .URL : kind == .number ? .numberPad : kind == .decimal ? .numbersAndPunctuation : .default)
        .privacySensitive(kind == .secret)
    }
}

/// Keyboard behaviour of a [FormField], mirroring Android's keyboard options.
enum FieldKind {
    /// Chat input and names: sentence caps, autocorrect on.
    case prose
    /// Prompts, ids and URLs: no auto-caps, no autocorrect.
    case mono
    /// URLs: like mono, URL keyboard.
    case url
    /// Numbers.
    case number
    /// Signed or decimal numbers (a keyboard with "-" and ".").
    case decimal
    /// Secrets: masked, no suggestions, no autocorrect, never offered to password managers.
    case secret
}

/**
 A slider under its label, the value trailing in monospaced digits (`CFG ········· 7.0`). The
 system `Slider` in the tint; VoiceOver reads the label and the formatted value.
 */
struct LabeledSlider: View {
    let label: String
    @Binding var value: Double
    let range: ClosedRange<Double>
    var step: Double? = nil
    var format: (Double) -> String = { String(format: "%.2f", $0) }

    init(_ label: String, value: Binding<Double>, in range: ClosedRange<Double>, step: Double? = nil, format: @escaping (Double) -> String = { String(format: "%.2f", $0) }) {
        self.label = label
        self._value = value
        self.range = range
        self.step = step
        self.format = format
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            LabeledContent(label) {
                Text(format(value)).monospacedDigit()
            }
            Group {
                if let step {
                    Slider(value: $value, in: range, step: step)
                } else {
                    Slider(value: $value, in: range)
                }
            }
            .accessibilityLabel(label)
            .accessibilityValue(format(value))
        }
    }
}
