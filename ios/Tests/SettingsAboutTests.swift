import Foundation
import Testing
@testable import Autobot

/// Settings › About: live version values and the bundled release notes.
@MainActor
struct AboutTests {
    @Test func versionValuesComeFromTheBundle() {
        let info = AboutInfo(bundle: .main)
        let plist = Bundle.main.infoDictionary ?? [:]
        #expect(info.version == plist["CFBundleShortVersionString"] as? String)
        #expect(info.build == plist["CFBundleVersion"] as? String)
        #expect(info.version.wholeMatch(of: /\d+\.\d+\.\d+/) != nil)
        #expect(Int(info.build) != nil)
        #expect(info.buildDate != nil)
        #expect(info.title == "Autobot \(info.version) (\(info.build))")
        // The Settings row shows exactly that.
        #expect(SettingsRow.about.title(about: info) == info.title)
    }

    @Test func titleFormat() {
        let info = AboutInfo(name: "Autobot", version: "1.6.4", build: "16", buildDate: nil)
        #expect(info.title == "Autobot 1.6.4 (16)")
        #expect(info.versionLabel == "1.6.4 (16)")
    }

    @Test func whatsNewIsBundledAndParses() throws {
        let markdown = try #require(AboutInfo.whatsNewMarkdown())
        #expect(!markdown.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        let blocks = ReleaseNotes.parse(markdown)
        #expect(!blocks.isEmpty)
        #expect(blocks.contains { if case .heading = $0 { true } else { false } })
        #expect(blocks.contains { if case .bullet = $0 { true } else { false } })
        // No raw markdown markers survive in headings.
        for case .heading(_, let text) in blocks { #expect(!String(text.characters).hasPrefix("#")) }
    }

    @Test func releaseNotesBlocks() {
        let blocks = ReleaseNotes.parse("""
        Intro line
        continues here.

        ## Changes
        - **Bold** item
          wrapped onto a second line
        * second `code`

        # Big
        """)
        #expect(blocks.count == 5)
        guard blocks.count == 5 else { return }
        if case .paragraph(let text) = blocks[0] {
            #expect(String(text.characters) == "Intro line continues here.")
        } else {
            Issue.record("expected a paragraph")
        }
        if case .heading(let level, let text) = blocks[1] {
            #expect(level == 2)
            #expect(String(text.characters) == "Changes")
        } else {
            Issue.record("expected a heading")
        }
        if case .bullet(let text) = blocks[2] {
            #expect(String(text.characters) == "Bold item wrapped onto a second line")
            // **Bold** became an inline presentation intent, not literal asterisks.
            #expect(text.runs.contains { $0.inlinePresentationIntent?.contains(.stronglyEmphasized) == true })
        } else {
            Issue.record("expected a bullet")
        }
        if case .bullet(let text) = blocks[3] { #expect(String(text.characters) == "second code") } else { Issue.record("expected a bullet") }
        if case .heading(let level, _) = blocks[4] { #expect(level == 1) } else { Issue.record("expected a heading") }
    }

    @Test func linksLeaveTheApp() {
        #expect(AboutInfo.releasesURL.absoluteString == "https://github.com/wckdboy/autobot/releases")
        #expect(AboutInfo.sourceURL.scheme == "https")
        #expect(AboutInfo.privacyStatement == "No analytics. No telemetry. Network only where you allow it.")
    }
}

/// The Settings tab's layout: one home per thing (matches Android).
struct SettingsStructureTests {
    @Test func sectionsInOrder() {
        #expect(SettingsSection.allCases.map(\.title) == ["Privacy & security", "Agent", "Appearance", "Data", "About"])
        #expect(SettingsSection.privacy.rows.prefix(2) == [.privacyCenter, .appLock])
        #expect(SettingsSection.privacy.rows.contains(.panicWipe))
        #expect(SettingsSection.agent.rows == [.memory, .memoryNotes, .vault, .webSearch, .quotaFallback, .mcpServers])
        #expect(SettingsSection.data.rows == [.exportImport, .deleteAll])
        #expect(SettingsSection.about.rows == [.about])
    }

    @Test func everyRowHasExactlyOneHome() {
        let placed = SettingsSection.allCases.flatMap(\.rows)
        #expect(placed.count == Set(placed).count)
        #expect(Set(placed) == Set(SettingsRow.allCases))
        let titles = placed.map { $0.title() }
        #expect(titles.count == Set(titles).count)
    }

    /// Providers, image backends and accounts live in Remote; Settings must not repeat them.
    @Test func remoteThingsAreNotDuplicated() {
        let titles = SettingsRow.allCases.map { $0.title().lowercased() }
        for remote in ["provider", "backend", "hugging face", "civitai", "account"] {
            #expect(!titles.contains { $0.contains(remote) }, "\(remote) belongs to Remote")
        }
        #expect(titles.filter { $0.contains("privacy") }.count == 1)
    }
}
