import SwiftUI

@main
struct AutobotApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @State private var container = AppDelegate.makeContainer()
    @State private var navigator = Navigator()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(container)
                .environment(navigator)
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
        .autobotTheme(settings.themeMode, amoled: settings.amoled, paperOnTop: {
            if let room = nav.studio { return room.surface == .paper }
            if case .imagine = nav.path.last { return true }
            return false
        }(), darkOnTop: nav.studio?.surface == .darkroom)
        // Living paper holds still while heavy work runs.
        .environment(\.inkHeavyWork, busy)
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
        .task {
            if let inbox = SharedInbox.defaultRoot { SharedInbox.sweep(root: inbox) }
            if app.lock.state == .unlocked { await app.unlockData() }
            #if DEBUG
            // `-AutobotOpen chat|agent` (UI work): open the newest plain / tool session.
            let args = ProcessInfo.processInfo.arguments
            if let i = args.firstIndex(of: "-AutobotOpen"), i + 1 < args.count, ["chat", "agent"].contains(args[i + 1]) {
                app.conversations.conversations.forEach { _ = app.agent.session($0.id) }
                try? await Task.sleep(for: .milliseconds(1500))
                let wantTools = args[i + 1] == "agent"
                if let c = app.conversations.conversations.first(where: { app.agent.session($0.id).state.toolsEnabled == wantTools }) {
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
        @Environment(\.scheme) private var c
        @Environment(\.accessibilityReduceMotion) private var reduceMotion

        var body: some View {
            Group {
                if !app.lock.ready {
                    // Settings not loaded yet: render nothing rather than risk showing content.
                    c.background
                } else if app.lock.state == .locked {
                    LockScreen(message: lockMessage) {
                        lockMessage = nil
                        Task { lockMessage = await app.lock.authenticate(reason: "Your conversations are encrypted on this device") }
                    }
                    // On unlock the paper prints away.
                    .transition(.inkPrintAway)
                } else if !app.settings.settings.onboarded {
                    WelcomeScreen(
                        onSetUp: {
                            nav.reset(.models)
                            app.settings.setOnboarded()
                        },
                        onPairPc: {
                            nav.reset(.remote)
                            app.settings.setOnboarded()
                        },
                        onAddApiKey: {
                            nav.reset(.remote, [.providers])
                            app.settings.setOnboarded()
                        },
                    )
                } else {
                    AutobotNavHost()
                        .modifier(EntryPoints())
                }
            }
            .background(c.background.ignoresSafeArea())
            .animation(reduceMotion ? .inkReduced : .linear(duration: Double(Tokens.Motion.printMillis) / 1000), value: app.lock.state)
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
    @Environment(\.scheme) private var c

    var body: some View {
        ZStack {
            c.background
            HalftoneField(cell: 10).opacity(0.5)
            AutobotMark(size: 56)
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
    }
}
