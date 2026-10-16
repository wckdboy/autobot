import Foundation

/**
 The Settings tab's information architecture, shared with Android: one home per thing. Model
 providers, image backends and the Hugging Face / Civitai accounts live in the Remote tab, so they
 have no row here; the Privacy Center lives here (Remote's status pill links to it).
 */
enum SettingsSection: String, CaseIterable, Identifiable {
    case privacy, agent, appearance, data, about

    var id: String { rawValue }

    var title: String {
        switch self {
        case .privacy: "Privacy & security"
        case .agent: "Agent"
        case .appearance: "Appearance"
        case .data: "Data"
        case .about: "About"
        }
    }

    var rows: [SettingsRow] {
        switch self {
        case .privacy: [.privacyCenter, .appLock, .incognito, .panicWipe]
        case .agent: [.memory, .memoryNotes, .vault, .webSearch, .quotaFallback, .mcpServers]
        case .appearance: [.theme, .amoled]
        case .data: [.exportImport, .deleteAll]
        case .about: [.about]
        }
    }
}

enum SettingsRow: String, CaseIterable, Identifiable {
    case privacyCenter, appLock, incognito, panicWipe
    case memory, memoryNotes, vault, webSearch, quotaFallback, mcpServers
    case theme, amoled
    case exportImport, deleteAll
    case about

    var id: String { rawValue }

    /// The row's label; the About row shows the live "Autobot 1.6.4 (16)".
    func title(about: AboutInfo = .current) -> String {
        switch self {
        case .privacyCenter: "Privacy Center"
        case .appLock: "App lock"
        case .incognito: "Incognito by default"
        case .panicWipe: "Panic wipe"
        case .memory: "Memory"
        case .memoryNotes: "Memory notes"
        case .vault: "Vault"
        case .webSearch: "Web search"
        case .quotaFallback: "Fallback on limit"
        case .mcpServers: "MCP servers"
        case .theme: "Theme"
        case .amoled: "AMOLED black"
        case .exportImport: "Export & import"
        case .deleteAll: "Delete all chats"
        case .about: about.title
        }
    }
}
