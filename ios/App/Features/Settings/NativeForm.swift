import SwiftUI

/*
 The system pieces the Settings, Models and Remote screens are built from: Liquid Glass buttons
 (bordered on iOS 18–25), an ⓘ popover for a row's explanation and a small tinted tag. Everything
 else is plain SwiftUI (`Form`, `List`, `Section`, `Toggle`, `Picker`, `LabeledContent`).
 */

extension View {
    /// The screen's primary action: Liquid Glass, prominent, in the tint (iOS 26+); bordered prominent before.
    @ViewBuilder func primaryActionStyle() -> some View {
        if #available(iOS 26.0, *) {
            buttonStyle(.glassProminent)
        } else {
            buttonStyle(.borderedProminent)
        }
    }

    /// A secondary action: Liquid Glass (iOS 26+); bordered before.
    @ViewBuilder func secondaryActionStyle() -> some View {
        if #available(iOS 26.0, *) {
            buttonStyle(.glass)
        } else {
            buttonStyle(.bordered)
        }
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

/// A small tag (`local`, `cloud · api`, `GET`): caption text in a capsule of the tint (or [color]).
struct TintTag: View {
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
            .foregroundStyle(color.map { AnyShapeStyle($0) } ?? AnyShapeStyle(.tint))
            .padding(.horizontal, 8)
            .padding(.vertical, 2)
            .background(Capsule().fill(color.map { AnyShapeStyle($0.opacity(0.15)) } ?? AnyShapeStyle(.tint.opacity(0.15))))
            .fixedSize()
    }
}

/// An empty state with the brand's pixel a: `ContentUnavailableView` with the mark as its image.
struct MarkUnavailableView<Actions: View>: View {
    let title: String
    var message: String? = nil
    @ViewBuilder var actions: () -> Actions

    var body: some View {
        ContentUnavailableView {
            Label { Text(title) } icon: { AutobotMark(size: 32) }
        } description: {
            if let message { Text(message) }
        } actions: {
            actions()
        }
    }
}

extension MarkUnavailableView where Actions == EmptyView {
    init(_ title: String, message: String? = nil) {
        self.init(title: title, message: message, actions: { EmptyView() })
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
