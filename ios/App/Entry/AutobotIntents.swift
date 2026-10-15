import AppIntents
import Foundation

/*
 App Intents: Siri, Shortcuts, Spotlight and the Action button (through a shortcut or the
 Control Center control). Every intent opens the app: an answer is never produced in the
 background, because that would hand a transcript to Shortcuts without the app lock and with the
 encrypted store still closed. A locked Autobot shows its lock screen first; the request waits.
 */

struct AskAutobotIntent: AppIntent {
    static let title: LocalizedStringResource = "Ask Autobot"
    static let description = IntentDescription("Opens a new chat in Autobot and sends your question. Autobot asks for Face ID first when it is locked.")
    static let openAppWhenRun = true

    @Parameter(title: "Question", inputOptions: String.IntentInputOptions(multiline: true), requestValueDialog: "What do you want to ask?")
    var prompt: String

    init() {}

    init(prompt: String) { self.prompt = prompt }

    @MainActor
    func perform() async throws -> some IntentResult {
        let text = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        EntryRouter.shared.receive(.ask(prompt: text.isEmpty ? nil : String(text.prefix(EntryLink.maxText)), send: !text.isEmpty))
        return .result()
    }
}

struct StartVoiceChatIntent: AppIntent {
    static let title: LocalizedStringResource = "Start Voice Chat"
    static let description = IntentDescription("Opens a new Autobot chat in voice mode. Speech is transcribed on this phone.")
    static let openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        EntryRouter.shared.receive(.voice)
        return .result()
    }
}

struct GenerateImageIntent: AppIntent {
    static let title: LocalizedStringResource = "Generate Image"
    static let description = IntentDescription("Opens Autobot's image studio with your prompt filled in.")
    static let openAppWhenRun = true

    @Parameter(title: "Prompt")
    var prompt: String?

    @MainActor
    func perform() async throws -> some IntentResult {
        let text = prompt?.trimmingCharacters(in: .whitespacesAndNewlines)
        EntryRouter.shared.receive(.imagine(prompt: text?.isEmpty == false ? String(text!.prefix(EntryLink.maxText)) : nil))
        return .result()
    }
}

struct AutobotShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: AskAutobotIntent(),
            phrases: ["Ask \(.applicationName)", "Ask \(.applicationName) a question"],
            shortTitle: "Ask Autobot",
            systemImageName: "bubble.left.and.text.bubble.right",
        )
        AppShortcut(
            intent: StartVoiceChatIntent(),
            phrases: ["Talk to \(.applicationName)", "Start a voice chat in \(.applicationName)"],
            shortTitle: "Voice Chat",
            systemImageName: "waveform",
        )
        AppShortcut(
            intent: GenerateImageIntent(),
            phrases: ["Generate an image with \(.applicationName)", "Imagine with \(.applicationName)"],
            shortTitle: "Generate Image",
            systemImageName: "photo",
        )
    }
}
