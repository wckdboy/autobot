import AutobotShared
import SwiftUI

// The basic catalog's inputs. Each one reads its value from the rendered node and writes back
// through the shared host (two-way binding on the property's data-model pointer); the host then
// re-renders, so checks and dependent text update on every change.

/// A failing check's message under an input (after the user changed it, so a fresh form is calm).
struct A2uiCheckMessage: View {
    let node: RenderNode
    let show: Bool

    var body: some View {
        if show, let error = node.firstError {
            Label(error, systemImage: "exclamationmark.circle")
                .font(.caption)
                .foregroundStyle(.red)
                .accessibilityLabel("Error: \(error)")
        }
    }
}

/// An input's visible label (inputs label themselves for VoiceOver too).
private struct A2uiFieldLabel: View {
    let text: String?
    var body: some View {
        if let text, !text.isEmpty {
            Text(text).font(.subheadline.weight(.medium)).foregroundStyle(.secondary).accessibilityHidden(true)
        }
    }
}

// MARK: TextField

struct A2uiTextFieldView: View {
    let node: RenderNode
    /// A sent form (the chat): its inputs show what was sent and take no edits.
    @Environment(\.a2uiReadOnly) private var readOnly
    @Environment(\.a2uiContext) private var context
    @State private var edited = false

    var body: some View {
        let label = node.string(name: "label")
        let placeholder = node.string(name: "placeholder") ?? label ?? ""
        let text = Binding<String>(
            get: { node.string(name: "value") ?? "" },
            set: { value in
                edited = true
                context?.model.set(node, string: value)
            },
        )
        let variant = node.variant("shortText")
        let bound = node.binding(name: "value") != nil
        VStack(alignment: .leading, spacing: 6) {
            // The label shows above when there is a separate placeholder; otherwise it is the placeholder.
            if node.string(name: "placeholder") != nil { A2uiFieldLabel(text: label) }
            Group {
                switch variant {
                case "obscured":
                    SecureField(placeholder, text: text).textContentType(.password)
                case "longText":
                    TextField(placeholder, text: text, axis: .vertical).lineLimit(3 ... 8)
                case "number":
                    TextField(placeholder, text: text).keyboardType(.decimalPad)
                default:
                    TextField(placeholder, text: text)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(.background.secondary, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(edited && !node.isValid ? AnyShapeStyle(.red) : AnyShapeStyle(.separator), lineWidth: edited && !node.isValid ? 1 : 0.5),
            )
            .disabled(!bound)
            .accessibilityLabel(label ?? placeholder)
            A2uiCheckMessage(node: node, show: edited)
        }
        .disabled(readOnly)
        .a2uiLeaf()
    }
}

// MARK: CheckBox

/// iOS has no checkbox toggle style: a switch with the label.
struct A2uiCheckBoxView: View {
    let node: RenderNode
    /// A sent form (the chat): its inputs show what was sent and take no edits.
    @Environment(\.a2uiReadOnly) private var readOnly
    @Environment(\.a2uiContext) private var context
    @State private var edited = false

    var body: some View {
        let isOn = Binding<Bool>(
            get: { node.boolOr(name: "value", fallback: false) },
            set: { value in
                edited = true
                context?.model.set(node, bool: value)
            },
        )
        VStack(alignment: .leading, spacing: 4) {
            Toggle(isOn: isOn) { Text(node.string(name: "label") ?? "") }
                .disabled(node.binding(name: "value") == nil)
            A2uiCheckMessage(node: node, show: edited)
        }
        .disabled(readOnly)
        .a2uiLeaf()
    }
}

// MARK: ChoicePicker

struct A2uiChoicePickerView: View {
    let node: RenderNode
    /// A sent form (the chat): its inputs show what was sent and take no edits.
    @Environment(\.a2uiReadOnly) private var readOnly
    @Environment(\.a2uiContext) private var context
    @State private var filter = ""
    @State private var edited = false

    private struct Option: Hashable { let label: String; let value: String }

    var body: some View {
        let options = node.value(name: "options").listItems.map(\.objectFields).compactMap { f -> Option? in
            guard let value = f["value"]?.asText() else { return nil }
            return Option(label: f["label"]?.asText() ?? value, value: value)
        }
        let single = node.variant("mutuallyExclusive") != "multipleSelection"
        let selected = Set(node.strings(name: "value"))
        let visible = filter.isEmpty ? options : options.filter { $0.label.localizedCaseInsensitiveContains(filter) }
        let label = node.string(name: "label")
        VStack(alignment: .leading, spacing: 8) {
            A2uiFieldLabel(text: label)
            if node.boolOr(name: "filterable", fallback: false) {
                TextField("Filter", text: $filter)
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background(.background.secondary, in: Capsule())
                    .overlay(Capsule().strokeBorder(.separator, lineWidth: 0.5))
                    .accessibilityLabel("Filter \(label ?? "options")")
            }
            if node.variant("checkbox", prop: "displayStyle") == "chips" {
                A2uiFlowLayout {
                    ForEach(visible, id: \.self) { option in
                        A2uiChip(title: option.label, selected: selected.contains(option.value)) { toggle(option.value, single, selected) }
                    }
                }
            } else if single, options.count > 6, filter.isEmpty {
                Picker(label ?? "Choose", selection: Binding(
                    get: { selected.first ?? "" },
                    set: { toggle($0, true, []) },
                )) {
                    Text("None").tag("")
                    ForEach(options, id: \.self) { Text($0.label).tag($0.value) }
                }
                .pickerStyle(.menu)
            } else {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(visible, id: \.self) { option in
                        let on = selected.contains(option.value)
                        Button { toggle(option.value, single, selected) } label: {
                            HStack(spacing: 10) {
                                Image(systemName: single ? (on ? "largecircle.fill.circle" : "circle") : (on ? "checkmark.square.fill" : "square"))
                                    .font(.title3)
                                    .foregroundStyle(on ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                                Text(option.label).foregroundStyle(.primary)
                                Spacer(minLength: 0)
                            }
                            .padding(.vertical, 6)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(on ? .isSelected : [])
                        .accessibilityValue(on ? "selected" : "")
                    }
                }
            }
            A2uiCheckMessage(node: node, show: edited)
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(label ?? "Choices")
        .disabled(readOnly)
        .a2uiLeaf()
    }

    private func toggle(_ value: String, _ single: Bool, _ selected: Set<String>) {
        edited = true
        let current = node.strings(name: "value")
        let next: [String]
        if single {
            next = value.isEmpty ? [] : [value]
        } else {
            next = current.contains(value) ? current.filter { $0 != value } : current + [value]
        }
        context?.model.set(node, strings: next)
    }
}

// MARK: Slider

struct A2uiSliderView: View {
    let node: RenderNode
    /// A sent form (the chat): its inputs show what was sent and take no edits.
    @Environment(\.a2uiReadOnly) private var readOnly
    @Environment(\.a2uiContext) private var context

    var body: some View {
        let lo = node.numberOr(name: "min", fallback: 0)
        let hi = max(node.numberOr(name: "max", fallback: 100), lo + .ulpOfOne)
        let steps = node.number(name: "steps")?.doubleValue ?? 0
        let step = steps >= 1 ? (hi - lo) / steps : nil
        let value = Binding<Double>(
            get: { min(max(node.numberOr(name: "value", fallback: lo), lo), hi) },
            set: { context?.model.set(node, number: $0) },
        )
        let label = node.string(name: "label") ?? "Value"
        let shown = value.wrappedValue.formatted(.number.precision(.fractionLength(0 ... (step.map { $0 < 1 ? 2 : 0 } ?? 2))))
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                A2uiFieldLabel(text: node.string(name: "label"))
                Spacer()
                Text(shown).font(.subheadline.monospacedDigit()).foregroundStyle(.secondary).accessibilityHidden(true)
            }
            Group {
                if let step {
                    Slider(value: value, in: lo ... hi, step: step)
                } else {
                    Slider(value: value, in: lo ... hi)
                }
            }
            .accessibilityLabel(label)
            .accessibilityValue(shown)
            .disabled(node.binding(name: "value") == nil)
        }
        .disabled(readOnly)
        .a2uiLeaf()
    }
}

// MARK: DateTimeInput

struct A2uiDateTimeView: View {
    let node: RenderNode
    /// A sent form (the chat): its inputs show what was sent and take no edits.
    @Environment(\.a2uiReadOnly) private var readOnly
    @Environment(\.a2uiContext) private var context

    var body: some View {
        let enableDate = node.boolOr(name: "enableDate", fallback: false)
        let enableTime = node.boolOr(name: "enableTime", fallback: false)
        // Neither flag set: a date (the most useful reading).
        let mode = A2uiDates.Mode(date: enableDate || !enableTime, time: enableTime)
        let components: DatePickerComponents = switch (mode.date, mode.time) {
        case (true, true): [.date, .hourAndMinute]
        case (false, true): [.hourAndMinute]
        default: [.date]
        }
        let raw = node.string(name: "value") ?? ""
        let date = Binding<Date>(
            get: { A2uiDates.parse(raw) ?? Date() },
            set: { context?.model.set(node, string: A2uiDates.format($0, mode)) },
        )
        let lo = A2uiDates.parse(node.string(name: "min") ?? "")
        let hi = A2uiDates.parse(node.string(name: "max") ?? "")
        let label = node.string(name: "label") ?? (mode.time && !mode.date ? "Time" : "Date")
        Group {
            switch (lo, hi) {
            case let (lo?, hi?) where lo <= hi: DatePicker(label, selection: date, in: lo ... hi, displayedComponents: components)
            case let (lo?, nil): DatePicker(label, selection: date, in: lo..., displayedComponents: components)
            case let (nil, hi?): DatePicker(label, selection: date, in: ...hi, displayedComponents: components)
            default: DatePicker(label, selection: date, displayedComponents: components)
            }
        }
        .disabled(node.binding(name: "value") == nil)
        .disabled(readOnly)
        .a2uiLeaf()
    }
}

/// ISO 8601 in and out of DateTimeInput: `yyyy-MM-dd`, `HH:mm[:ss]` or a date-time.
enum A2uiDates {
    struct Mode { let date: Bool; let time: Bool }

    static func parse(_ s: String) -> Date? {
        let t = s.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty else { return nil }
        let full = ISO8601DateFormatter()
        full.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = full.date(from: t) { return d }
        full.formatOptions = [.withInternetDateTime]
        if let d = full.date(from: t) { return d }
        for pattern in ["yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd", "HH:mm:ss", "HH:mm"] {
            let f = DateFormatter()
            f.locale = Locale(identifier: "en_US_POSIX")
            f.timeZone = .current
            f.dateFormat = pattern
            if let d = f.date(from: t) {
                if pattern.hasPrefix("HH") {
                    // A bare time: today at that time.
                    let c = Calendar.current.dateComponents([.hour, .minute, .second], from: d)
                    return Calendar.current.date(bySettingHour: c.hour ?? 0, minute: c.minute ?? 0, second: c.second ?? 0, of: Date())
                }
                return d
            }
        }
        return nil
    }

    static func format(_ d: Date, _ mode: Mode) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = .current
        switch (mode.date, mode.time) {
        case (true, true):
            let iso = ISO8601DateFormatter()
            iso.timeZone = .current
            iso.formatOptions = [.withInternetDateTime]
            return iso.string(from: d)
        case (false, true):
            f.dateFormat = "HH:mm"
        default:
            f.dateFormat = "yyyy-MM-dd"
        }
        return f.string(from: d)
    }
}
