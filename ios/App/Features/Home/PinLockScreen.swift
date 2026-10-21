import AutobotShared
import SwiftUI

/**
 The lock screen with an app PIN (port of Android's `PinLockScreen.kt`): dots, a round keypad and
 UNLOCK. The PIN is submitted only with UNLOCK (never on a length), so the length of either PIN
 shows nowhere. [status] carries the warning before a wipe and the back-off; [message] the last
 result ("Wrong PIN"). The real PIN and the duress PIN take the same path through this screen:
 [busy] while the PIN is checked, then it fades away.
 */
struct PinLockScreen: View {
    let status: LockStatus
    let message: String?
    let busy: Bool
    let biometrics: Bool
    let biometryLabel: String
    let onSubmit: (String) -> Void
    let onBiometrics: () -> Void
    @State private var pin = ""
    @State private var now = Date()
    @State private var waitUntil = Date()

    var body: some View {
        let waiting = waitUntil > now
        let enabled = !busy && !waiting
        VStack(spacing: 14) {
            Spacer()
            AutobotMark(size: 36)
            Text(PanicCopy.shared.LOCKED.sentenceCased)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .accessibilityAddTraits(.isHeader)
            Text(PanicCopy.shared.ENTER_PIN).font(.title2.weight(.semibold))
            dots
            Text(waiting ? PanicCopy.shared.tryAgainIn(millis: Int64(waitUntil.timeIntervalSince(now) * 1000)) : (message ?? " "))
                .font(.footnote)
                .foregroundStyle(.red)
                .multilineTextAlignment(.center)
                .frame(minHeight: 16)
            if status.warn, let remaining = status.remaining?.intValue {
                Text(PanicCopy.shared.attemptsLeft(remaining: Int32(remaining)))
                    .font(.subheadline)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
            }
            keypad(enabled: enabled)
            Button {
                if !pin.isEmpty { onSubmit(pin) }
            } label: {
                Group {
                    if busy { ProgressView() } else { Text(PanicCopy.shared.UNLOCK.sentenceCased) }
                }
                .frame(maxWidth: 240)
            }
            .glassProminentButton()
            .controlSize(.large)
            .disabled(!enabled || pin.isEmpty)
            Spacer()
        }
        .padding(.horizontal, 32)
        .padding(.vertical, 24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemBackground).ignoresSafeArea())
        .onAppear { waitUntil = Date().addingTimeInterval(Double(status.waitMillis) / 1000) }
        // A new result clears what was typed and restarts the wait.
        .onChange(of: status) { _, s in
            pin = ""
            waitUntil = Date().addingTimeInterval(Double(s.waitMillis) / 1000)
            now = Date()
        }
        .onChange(of: message) { _, _ in pin = "" }
        .task(id: waitUntil) {
            while Date() < waitUntil {
                now = Date()
                try? await Task.sleep(for: .milliseconds(250))
            }
            now = Date()
        }
        .task { if biometrics { onBiometrics() } }
    }

    private var dots: some View {
        HStack(spacing: 10) {
            ForEach(0 ..< min(pin.count, Int(PinRules.shared.MAX_LENGTH)), id: \.self) { _ in
                Circle().fill(.tint).frame(width: 12, height: 12)
            }
            if pin.isEmpty { Circle().strokeBorder(.secondary, lineWidth: 1).frame(width: 12, height: 12) }
        }
        .frame(height: 16)
        .accessibilityElement()
        .accessibilityLabel("\(pin.count) digits entered")
    }

    private func keypad(enabled: Bool) -> some View {
        VStack(spacing: 14) {
            ForEach(["123", "456", "789"], id: \.self) { row in
                HStack(spacing: 22) {
                    ForEach(Array(row), id: \.self) { d in key(String(d), enabled: enabled) { type(d) } }
                }
            }
            HStack(spacing: 22) {
                if biometrics && !busy {
                    key("", enabled: true, label: "Use \(biometryLabel)", action: onBiometrics) {
                        Image(systemName: biometryLabel == "Touch ID" ? "touchid" : "faceid").font(.title2).foregroundStyle(.tint)
                    }
                } else {
                    Color.clear.frame(width: 72, height: 72)
                }
                key("0", enabled: enabled) { type("0") }
                key("⌫", enabled: enabled, label: "Delete") { if !pin.isEmpty { pin.removeLast() } }
            }
        }
    }

    private func type(_ digit: Character) {
        if pin.count < Int(PinRules.shared.MAX_LENGTH) { pin.append(digit) }
    }

    private func key(_ text: String, enabled: Bool, label: String? = nil, action: @escaping () -> Void, @ViewBuilder content: () -> some View = { EmptyView() }) -> some View {
        Button(action: action) {
            ZStack {
                if text.isEmpty {
                    content()
                } else if text == "⌫" {
                    Image(systemName: "delete.left").font(.title2)
                } else {
                    Circle().fill(.fill.tertiary)
                    Text(text).font(.title).monospacedDigit()
                }
            }
            .foregroundStyle(enabled ? AnyShapeStyle(.primary) : AnyShapeStyle(.tertiary))
            .frame(width: 72, height: 72)
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityLabel(label ?? text)
    }
}
