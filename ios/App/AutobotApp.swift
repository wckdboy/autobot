import AutobotShared
import SwiftUI

@main
struct AutobotApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    /// The container and navigator, replaced together when a panic wipe starts the app over.
    @State private var holder = ContainerHolder.shared

    init() {
        LaunchTrace.mark("app.init")
        // Hardware facts resolve on another core while the scene and first screen set up.
        Task.detached(priority: .utility) { _ = DeviceProfile.current() }
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                // A new container (after a panic wipe) is a new app: no view state carries over.
                .id(holder.generation)
                .environment(holder.container)
                .environment(holder.navigator)
                // `\.keyboardUp` for every screen with a field (Keyboard.swift).
                .tracksKeyboard()
                // Widgets, the Control Center control and the share extension (see EntryRouter).
                .onOpenURL { EntryRouter.shared.receive(url: $0) }
        }
    }
}

/**
 Lock gate, first-run welcome and the navigation host. While the app is not active a privacy
 cover hides its content from the app switcher (iOS has no FLAG_SECURE; this is the equivalent
 for recents thumbnails). Sent to the background while an agent turn or an image job runs, the
 app asks for time to finish it ([BackgroundGrace]).
 */
struct RootView: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scenePhase) private var phase
    @State private var lockMessage: String?
    @State private var grace = BackgroundGrace()

    var body: some View {
        let settings = app.settings.settings
        let busy = !app.agent.running.isEmpty || app.generator.isBusy || app.studio.hasRunningOnDevice
        ZStack {
            Content(lockMessage: $lockMessage)
            if phase != .active && !DebugFlags.screenshots {
                PrivacyCover()
            }
        }
        // Every screen, the Studio included, follows the app's theme (light, dark or the system's).
        .autobotTheme(DebugFlags.theme ?? settings.themeMode)
        .onChange(of: phase) { old, new in
            switch new {
            case .background:
                app.lock.didEnterBackground()
                AppActivity.shared.enteredBackground()
                grace.enteredBackground(busy: busy)
            case .active:
                AppActivity.shared.enteredForeground()
                grace.finish()
                if old == .background || old == .inactive { app.lock.willEnterForeground() }
            default: break
            }
        }
        .onChange(of: busy) { _, now in grace.workChanged(busy: now) }
        .onChange(of: app.lock.state) { _, state in
            if state == .unlocked { Task { await app.unlockData() } }
        }
        .onAppear {
            LaunchTrace.mark("root.appear")
            LaunchTrace.watchFrames { [app] in app.dataLoaded }
        }
        .task {
            if let inbox = SharedInbox.defaultRoot { Task.detached(priority: .utility) { SharedInbox.sweep(root: inbox) } }
            if app.lock.state == .unlocked { await app.unlockData() }
            #if DEBUG
            // `-AutobotOpen chat|agent|phone-first` (UI work): open the newest plain / tool session,
            // or the demo's "phone first" chat.
            let args = ProcessInfo.processInfo.arguments
            if let i = args.firstIndex(of: "-AutobotOpen"), i + 1 < args.count, ["chat", "agent", "phone-first"].contains(args[i + 1]) {
                app.conversations.conversations.forEach { _ = app.agent.session($0.id) }
                try? await Task.sleep(for: .milliseconds(1500))
                let wantTools = args[i + 1] == "agent"
                let phoneFirst = args[i + 1] == "phone-first"
                if let c = app.conversations.conversations.first(where: {
                    phoneFirst ? $0.title == "Who wrote Stiller?" : app.agent.session($0.id).state.toolsEnabled == wantTools
                }) {
                    nav.push(.chat(conversationId: c.id))
                }
            }
            #endif
        }
    }

    private struct Content: View {
        @Binding var lockMessage: String?
        @Environment(AppContainer.self) private var app
        @Environment(Navigator.self) private var nav
        @Environment(\.accessibilityReduceMotion) private var reduceMotion

        var body: some View {
            Group {
                if !app.lock.ready {
                    // Settings not loaded yet: render nothing rather than risk showing content.
                    Color(.systemBackground)
                } else if app.lock.state == .locked {
                    Group {
                        if app.lock.usesPin || app.lock.quietStart {
                            PinGate(lockMessage: $lockMessage)
                        } else {
                            LockScreen(message: lockMessage) {
                                lockMessage = nil
                                Task { lockMessage = await app.lock.authenticate(reason: "Your conversations are encrypted on this device") }
                            }
                        }
                    }
                    // On unlock the lock fades away.
                    .transition(.opacity)
                } else if !app.settings.settings.onboarded && !DebugFlags.skipWelcome {
                    WelcomeScreen(
                        onSetUp: {
                            nav.reset(.models)
                            app.settings.setOnboarded()
                        },
                        // Straight to what they came for: the pairing sheet, or the provider picker.
                        onPairPc: {
                            nav.reset(.remote, [.providers])
                            // A code a pairing link brought stays (an empty sheet must not replace it).
                            if nav.pendingPairing == nil { nav.pendingPairing = "" }
                            app.settings.setOnboarded()
                        },
                        onAddApiKey: {
                            nav.reset(.remote, [.providers])
                            nav.pendingAddProvider = true
                            app.settings.setOnboarded()
                        },

                    )
                } else {
                    AutobotNavHost()
                        .modifier(EntryPoints())
                }
            }
            .background(Color(.systemBackground).ignoresSafeArea())
            .animation(reduceMotion ? .easeOut(duration: 0.15) : .easeOut(duration: 0.35), value: app.lock.state)
        }
    }
}

/**
 The PIN pad in front of the app (an app PIN is set, or a duress wipe just finished and the screen
 prints away as after any unlock).
 */
private struct PinGate: View {
    @Binding var lockMessage: String?
    @Environment(AppContainer.self) private var app
    @State private var status: LockStatus?
    @State private var busy = false

    /// How long the lock screen stays up after a duress restart before it prints away (a PIN check's time).
    private static let quietStartMillis = 250

    var body: some View {
        let quiet = app.lock.quietStart
        PinLockScreen(
            status: status ?? app.lock.pinStatus() ?? LockStatus(failures: 0, remaining: nil, warn: false, waitMillis: 0),
            message: lockMessage,
            busy: busy || quiet,
            biometrics: !quiet && app.lock.biometricsOffered(),
            biometryLabel: app.lock.biometryLabel(),
            onSubmit: { pin in
                busy = true
                lockMessage = nil
                Task {
                    switch await app.lock.submitPin(pin) {
                    case .unlocked, .wiping: break
                    case .wrong(let s, let refused):
                        status = s
                        lockMessage = refused ? nil : PanicCopy.shared.WRONG_PIN
                    }
                    busy = false
                }
            },
            onBiometrics: {
                lockMessage = nil
                Task { lockMessage = await app.lock.authenticate(reason: "Your conversations are encrypted on this device") }
            },
        )
        .task(id: quiet) {
            guard quiet else { return }
            try? await Task.sleep(for: .milliseconds(Self.quietStartMillis))
            app.lock.finishQuietStart()
        }
    }
}

/**
 Opens what a system entry point asked for, once the app is unlocked, set up, loaded and active
 (the lock re-arms when the app becomes active, before this runs).
 */
private struct EntryPoints: ViewModifier {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scenePhase) private var phase

    private struct Trigger: Equatable {
        let pending: EntryRequest?
        let loaded: Bool
        let active: Bool
    }

    func body(content: Content) -> some View {
        let router = EntryRouter.shared
        content.task(id: Trigger(pending: router.pending, loaded: app.dataLoaded, active: phase == .active)) {
            guard phase == .active, router.pending != nil else { return }
            router.open(app: app, nav: nav)
        }
    }
}

/// Shown over everything while the app is inactive (app switcher, notification centre).
struct PrivacyCover: View {
    var body: some View {
        ZStack {
            Color(.systemBackground)
            AppIconImage(size: 56)
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
    }
}
