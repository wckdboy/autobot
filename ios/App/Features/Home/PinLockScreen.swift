import AutobotShared
import SwiftUI

/**
 The lock screen with an app PIN (port of Android's `PinLockScreen.kt`): dots, a printed keypad and
 UNLOCK. The PIN is submitted only with UNLOCK (never on a length), so the length of either PIN
 shows nowhere. [status] carries the warning before a wipe and the back-off; [message] the last
 result ("Wrong PIN"). The real PIN and the duress PIN take the same path through this screen:
 [busy] while the PIN is checked, then the paper prints away.
 */
struct PinLockScreen: View {
    let status: LockStatus
    let message: String?
    let busy: Bool
    let biometrics: Bool
    let biometryLabel: String
    let onSubmit: (String) -> Void
    let onBiometrics: () -> Void
    @Environment(\.scheme) private var c
    @State private var pin = ""
    @State private var now = Date()
    @State private var waitUntil = Date()

    var body: some View {
        let waiting = waitUntil > now
        let enabled = !busy && !waiting
        VStack(spacing: 14) {
            Spacer()
            Image(systemName: "lock.fill")
                .font(.system(size: 40, weight: .regular))
                .foregroundStyle(c.primary)
            SectionLabel(PanicCopy.shared.LOCKED)
            Text(PanicCopy.shared.ENTER_PIN).textStyle(.headlineSmall).foregroundStyle(c.onSurface)
            dots
            Text(waiting ? PanicCopy.shared.tryAgainIn(millis: Int64(waitUntil.timeIntervalSince(now) * 1000)) : (message ?? " "))
                .textStyle(.bodySmall)
                .foregroundStyle(c.error)
                .multilineTextAlignment(.center)
                .frame(minHeight: 16)
            if status.warn, let remaining = status.remaining?.intValue {
                Text(PanicCopy.shared.attemptsLeft(remaining: Int32(remaining)))
                    .textStyle(.bodyMedium)
                    .foregroundStyle(c.error)
                    .multilineTextAlignment(.center)
            }
            keypad(enabled: enabled)
            PrimaryButton(PanicCopy.shared.UNLOCK, enabled: enabled && !pin.isEmpty, busy: busy) {
                if !pin.isEmpty { onSubmit(pin) }
            }
            .frame(maxWidth: 280)
            Spacer()
        }
        .padding(.horizontal, 32)
        .padding(.vertical, 24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .inkGround()
        .background(c.background.ignoresSafeArea())
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
                Circle().fill(c.primary).frame(width: 10, height: 10)
            }
            if pin.isEmpty { Circle().stroke(c.outline, lineWidth: 1).frame(width: 10, height: 10) }
        }
        .frame(height: 16)
        .accessibilityElement()
        .accessibilityLabel("\(pin.count) digits entered")
    }

    private func keypad(enabled: Bool) -> some View {
        VStack(spacing: 8) {
            ForEach(["123", "456", "789"], id: \.self) { row in
                HStack(spacing: 8) {
                    ForEach(Array(row), id: \.self) { d in key(String(d), enabled: enabled) { type(d) } }
                }
            }
            HStack(spacing: 8) {
                if biometrics && !busy {
                    key("", enabled: true, label: "Use \(biometryLabel)", action: onBiometrics) {
                        Image(systemName: biometryLabel == "Touch ID" ? "touchid" : "faceid").foregroundStyle(c.primary)
                    }
                } else {
                    Color.clear.frame(width: 64, height: 64)
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
                RoundedRectangle(cornerRadius: Tokens.Radius.extraSmall).stroke(enabled ? c.outline : c.outlineVariant, lineWidth: 1)
                if text.isEmpty {
                    content()
                } else {
                    Text(text).textStyle(.headlineSmall).foregroundStyle(enabled ? c.onSurface : c.onSurfaceVariant)
                }
            }
            .frame(width: 64, height: 64)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityLabel(label ?? text)
    }
}
