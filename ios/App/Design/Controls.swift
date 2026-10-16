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
                        Image(systemName: "arrowtriangle.down.fill").font(.system(size: 8)).foregroundStyle(c.onSurfaceVariant).padding(.horizontal, 8)
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
    @FocusState private var focused: Bool
    @Environment(\.scheme) private var c

    var body: some View {
        let mono = kind != .prose
        VStack(alignment: .leading, spacing: 4) {
            if let label { MicroLabel(label) }
            Group {
                if kind == .secret {
                    SecureField(text: $text, prompt: prompt(mono: true)) { Text(label ?? "") }
                        .textContentType(.oneTimeCode)
                } else if maxLines > 1 {
                    TextField(text: $text, prompt: prompt(mono: mono), axis: .vertical) { Text(label ?? "") }
                        .lineLimit(minLines ... maxLines)
                } else {
                    TextField(text: $text, prompt: prompt(mono: mono)) { Text(label ?? "") }
                }
            }
            .focused($focused)
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

    private func prompt(mono: Bool) -> Text? {
        placeholder.map { Text($0).font(mono ? AutobotType.code.font : AutobotType.bodyMedium.font).foregroundColor(c.onSurfaceVariant) }
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

/// A settings row: title, supporting text, trailing switch.
struct SwitchRow: View {
    let title: String
    let subtitle: String
    @Binding var isOn: Bool
    var enabled = true
    @Environment(\.scheme) private var c

    var body: some View {
        HStack(spacing: 16) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).textStyle(.bodyLarge).foregroundStyle(enabled ? c.onSurface : c.onSurfaceVariant)
                Text(subtitle).textStyle(.bodyMedium).foregroundStyle(c.onSurfaceVariant)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            RisoToggle(isOn: $isOn, enabled: enabled)
        }
        .padding(.horizontal, Tokens.Spacing.gutter)
        .padding(.vertical, 10)
        .contentShape(Rectangle())
        .onTapGesture { if enabled { isOn.toggle() } }
    }
}

/// A settings row that opens another screen.
struct NavigationRow: View {
    let title: String
    let subtitle: String
    let action: () -> Void
    @Environment(\.scheme) private var c

    var body: some View {
        Button(action: action) {
            HStack(spacing: 16) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).textStyle(.bodyLarge).foregroundStyle(c.onSurface)
                    Text(subtitle).textStyle(.bodyMedium).foregroundStyle(c.onSurfaceVariant).multilineTextAlignment(.leading)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.right").font(.system(size: 14, weight: .semibold)).foregroundStyle(c.onSurfaceVariant)
            }
            .padding(.horizontal, Tokens.Spacing.gutter)
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle())
    }
}
