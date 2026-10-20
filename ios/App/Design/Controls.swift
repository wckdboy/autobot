import SwiftUI

/**
 Labelled dropdown field: micro-label above a bordered mono value with a caret.
 [options] may be empty, in which case the field is read-only.
 */
struct SelectField<T: Hashable>: View {
    let label: String
    let value: String
    let options: [T]
    var optionLabel: (T) -> String = { "\($0)" }
    var enabled = true
    let onSelect: (T) -> Void
    @Environment(\.scheme) private var c

    var body: some View {
        let interactive = enabled && !options.isEmpty
        VStack(alignment: .leading, spacing: 4) {
            MicroLabel(label)
            Menu {
                ForEach(options, id: \.self) { option in
                    Button(optionLabel(option)) { onSelect(option) }
                }
            } label: {
                HStack(spacing: 0) {
                    Text(value.isEmpty ? "—" : value)
                        .textStyle(.code)
                        .foregroundStyle(enabled ? c.onSurface : c.onSurfaceVariant)
                        .lineLimit(1)
                        .frame(maxWidth: 280, alignment: .leading)
                    Spacer(minLength: 4)
                    if interactive {
                        Image(systemName: "arrowtriangle.down.fill").symbolSize(8).foregroundStyle(c.onSurfaceVariant).padding(.horizontal, 8)
                    }
                }
                .padding(.leading, 10)
                .padding(.trailing, 4)
                .frame(minHeight: Tokens.Spacing.selectFieldMinHeight)
                .overlay(RoundedRectangle(cornerRadius: AutobotShapes.small).strokeBorder(c.outlineVariant, lineWidth: Tokens.Stroke.border))
                .contentShape(Rectangle())
            }
            .disabled(!interactive)
            .accessibilityLabel("Choose \(label)")
        }
    }
}

/// `[-] 4 [+]` integer stepper with a micro-label.
struct StepperField: View {
    let label: String
    let value: Int
    let range: ClosedRange<Int>
    let onChange: (Int) -> Void
    @Environment(\.scheme) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            MicroLabel(label)
            HStack(spacing: 0) {
                button("−", enabled: value > range.lowerBound) { onChange(value - 1) }
                Text("\(value)")
                    .textStyle(.readout)
                    .foregroundStyle(c.primary)
                    .frame(minWidth: 32)
                    .padding(.horizontal, 4)
                button("+", enabled: value < range.upperBound) { onChange(value + 1) }
            }
            .overlay(RoundedRectangle(cornerRadius: AutobotShapes.small).strokeBorder(c.outlineVariant, lineWidth: Tokens.Stroke.border))
        }
        .accessibilityElement(children: .combine)
        .accessibilityValue("\(value)")
    }

    private func button(_ glyph: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(glyph)
                .textStyle(.readout)
                .foregroundStyle(enabled ? c.onSurface : c.outline)
                .frame(width: Tokens.Spacing.stepperButton, height: Tokens.Spacing.stepperButton)
                .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle())
        .disabled(!enabled)
    }
}

/// Keyboard behaviour of a [ConsoleTextField], mirroring Android's keyboard options.
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
 Hairline-bordered text input for prompts and fields. [kind] picks the face (mono for prompts,
 URLs, ids) and the keyboard: mono and secret fields turn off auto-capitalization and
 autocorrection, and secret fields are masked (iOS keyboards do not learn from secure fields).
 */
struct ConsoleTextField: View {
    @Binding var text: String
    var placeholder: String? = nil
    var label: String? = nil
    var kind: FieldKind = .prose
    var minLines = 1
    var maxLines = 1
    var enabled = true
    var onSubmit: (() -> Void)? = nil
    /// The caller's focus, when it moves focus itself (the composer); the field's own otherwise.
    var focus: FocusState<Bool>.Binding? = nil
    @FocusState private var focused: Bool
    @Environment(\.scheme) private var c
    @Environment(\.dynamicTypeSize) private var dynamicType

    var body: some View {
        let mono = kind != .prose
        let focused = focus?.wrappedValue ?? self.focused
        VStack(alignment: .leading, spacing: 4) {
            if let label { MicroLabel(label) }
            Group {
                if kind == .secret {
                    SecureField(text: $text, prompt: prompt(mono: true)) { Text(label ?? "") }
                        .textContentType(.oneTimeCode)
                } else if maxLines > 1 {
                    // A vertical field wraps its prompt well short of its width and hides the second
                    // line ("message · /" on a narrow phone): the prompt stays for VoiceOver but is
                    // drawn on top, truncating within the field's minimum lines.
                    TextField(text: $text, prompt: prompt(mono: mono, color: .clear), axis: .vertical) { Text(label ?? "") }
                        .lineLimit(minLines ... maxLines)
                        .overlay(alignment: .topLeading) {
                            if text.isEmpty, let placeholder {
                                Text(placeholder)
                                    .textStyle(mono ? .code : .bodyMedium)
                                    .foregroundStyle(c.onSurfaceVariant)
                                    .lineLimit(max(minLines, 1))
                                    .allowsHitTesting(false)
                                    .accessibilityHidden(true)
                            }
                        }
                } else {
                    TextField(text: $text, prompt: prompt(mono: mono)) { Text(label ?? "") }
                }
            }
            .focused(focus ?? $focused)
            .textStyle(mono ? .code : .bodyMedium)
            .foregroundStyle(c.onSurface)
            .tint(c.primary)
            .disabled(!enabled)
            .autocorrectionDisabled(kind != .prose)
            .textInputAutocapitalization(kind == .prose ? .sentences : .never)
            .keyboardType(kind == .url ? .URL : kind == .number ? .numberPad : kind == .decimal ? .numbersAndPunctuation : .default)
            .submitLabel(onSubmit != nil ? .search : .return)
            .onSubmit { onSubmit?() }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(RoundedRectangle(cornerRadius: AutobotShapes.small).fill(c.surfaceContainerLowest))
            .overlay(
                RoundedRectangle(cornerRadius: AutobotShapes.small)
                    .strokeBorder(focused ? c.primary : c.outlineVariant, lineWidth: focused ? 2 : Tokens.Stroke.border),
            )
            .privacySensitive(kind == .secret)
        }
    }

    private func prompt(mono: Bool, color: Color? = nil) -> Text? {
        placeholder.map { Text($0).font(mono ? AutobotType.code.font(dynamicType) : AutobotType.bodyMedium.font(dynamicType)).foregroundColor(color ?? c.onSurfaceVariant) }
    }
}

/// Material-style switch row content: riso tint on the system toggle.
struct RisoToggle: View {
    @Binding var isOn: Bool
    var enabled = true
    @Environment(\.scheme) private var c

    var body: some View {
        Toggle("", isOn: $isOn)
            .labelsHidden()
            .tint(c.primary)
            .disabled(!enabled)
    }
}

/// An ⓘ button that shows a short explanation in a popover (progressive disclosure: rows carry
/// a one- or two-word label, the why sits one tap away).
struct InfoButton: View {
    let title: String
    let text: String
    @State private var shown = false
    @Environment(\.scheme) private var c

    var body: some View {
        Button { shown = true } label: {
            Image(systemName: "info.circle")
                .symbolSize(17, weight: .regular)
                .foregroundStyle(c.onSurfaceVariant)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle())
        .accessibilityLabel("About \(title)")
        .popover(isPresented: $shown) {
            Text(text)
                .textStyle(.bodyMedium)
                .foregroundStyle(c.onSurface)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: 300, alignment: .leading)
                .padding(16)
                .presentationCompactAdaptation(.popover)
                .presentationBackground(c.surfaceContainerHigh)
        }
    }
}

/// A settings row: title, optional state line (only when it carries state), an optional ⓘ, a switch.
struct SwitchRow: View {
    let title: String
    var subtitle: String? = nil
    @Binding var isOn: Bool
    var enabled = true
    /// The explanation, behind an ⓘ button.
    var info: String? = nil
    @Environment(\.scheme) private var c

    init(title: String, subtitle: String? = nil, isOn: Binding<Bool>, enabled: Bool = true, info: String? = nil) {
        self.title = title
        self.subtitle = subtitle
        self._isOn = isOn
        self.enabled = enabled
        self.info = info
    }

    var body: some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                // The ⓘ sits right after the title (its 44 pt target overhangs the text edge).
                HStack(spacing: 0) {
                    Text(title).textStyle(.bodyLarge).foregroundStyle(enabled ? c.onSurface : c.onSurfaceVariant)
                    if let info { InfoButton(title: title, text: info).padding(.vertical, -12).padding(.leading, -6) }
                }
                if let subtitle { Text(subtitle).textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .onTapGesture { if enabled { isOn.toggle() } }
            RisoToggle(isOn: $isOn, enabled: enabled)
                .accessibilityLabel(title)
        }
        .padding(.horizontal, Tokens.Spacing.gutter)
        .padding(.vertical, 8)
        .frame(minHeight: Self.minHeight)
    }

    /// Every settings row is at least this tall (≥ the 44 pt touch minimum), as Android's `RowMinHeight`.
    static let minHeight: CGFloat = Tokens.Spacing.rowMinHeight
}

/// A settings row that does something destructive (red title, optional ⓘ, no chevron).
struct DestructiveRow: View {
    let title: String
    var info: String? = nil
    let action: () -> Void
    @Environment(\.scheme) private var c

    var body: some View {
        HStack(spacing: 0) {
            Button(action: action) {
                Text(title).textStyle(.bodyLarge).foregroundStyle(c.error)
                    .frame(minHeight: SwitchRow.minHeight, alignment: .leading)
                    .contentShape(Rectangle())
            }
            .buttonStyle(PressStyle())
            if let info { InfoButton(title: title, text: info).padding(.leading, -6) }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, Tokens.Spacing.gutter)
        .frame(maxWidth: .infinity, minHeight: SwitchRow.minHeight, alignment: .leading)
    }
}

/// A settings row that opens another screen: title, an optional ⓘ, optional trailing state value, chevron
/// (as Android's `NavRow`).
struct NavigationRow: View {
    let title: String
    /// A short state value (`On`, `Off`, `3`), trailing like iOS Settings; nil when there is none.
    var value: String? = nil
    /// The explanation, behind an ⓘ button after the title.
    var info: String? = nil
    /// The trailing glyph: a chevron (pushes a screen), `arrow.up.right` for a link that leaves the app.
    var trailingSymbol = "chevron.right"
    let action: () -> Void
    @Environment(\.scheme) private var c

    init(title: String, value: String? = nil, info: String? = nil, trailingSymbol: String = "chevron.right", action: @escaping () -> Void) {
        self.title = title
        self.value = value
        self.info = info
        self.trailingSymbol = trailingSymbol
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                HStack(spacing: 0) {
                    Text(title).textStyle(.bodyLarge).foregroundStyle(c.onSurface)
                    if let info { InfoButton(title: title, text: info).padding(.vertical, -12).padding(.leading, -6) }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if let value {
                    Text(value).textStyle(.bodyMedium).foregroundStyle(c.onSurfaceVariant).textLines(1)
                }
                Image(systemName: trailingSymbol).symbolSize(14, weight: .semibold).foregroundStyle(c.onSurfaceVariant)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, Tokens.Spacing.gutter)
            .frame(minHeight: SwitchRow.minHeight)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle())
    }
}

enum NoticeTone { case info, warning, error }

/// Why something is empty or not working, and the one tap that fixes it ("No model yet. · Add
/// provider"): a bar in the tone's colour, one line of text, an optional action (as Android's `Notice`).
struct Notice: View {
    let text: String
    var tone: NoticeTone = .info
    var action: String? = nil
    var onAction: (() -> Void)? = nil
    @Environment(\.scheme) private var c

    init(_ text: String, tone: NoticeTone = .info, action: String? = nil, onAction: (() -> Void)? = nil) {
        self.text = text
        self.tone = tone
        self.action = action
        self.onAction = onAction
    }

    var body: some View {
        // The bar is a fill: the vivid amber, whose text-safe ink on paper is a dull brown.
        let bar = switch tone {
        case .info: c.primary
        case .warning: AutobotColors.amber
        case .error: c.error
        }
        HStack(spacing: 10) {
            Rectangle().fill(bar).frame(width: 3)
            Text(text).textStyle(.bodySmall).foregroundStyle(c.onSurface)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.vertical, 10)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let action, let onAction { TextAction(action, action: onAction) } else { Spacer().frame(width: 2) }
        }
        .frame(minHeight: 44)
        .fixedSize(horizontal: false, vertical: true)
        .background(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall).fill(c.surfaceContainerHigh))
        .clipShape(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall))
    }
}
