import Foundation

/**
 What the About screen shows, read from the bundle only (no network): the version, the build, the
 build date and the bundled release notes (`shared/release/whats-new.md`, rewritten by
 `scripts/release.sh` and bundled by `project.yml`).
 */
struct AboutInfo: Equatable, Sendable {
    static let releasesURL = URL(string: "https://github.com/wckdboy/autobot/releases")!
    static let sourceURL = URL(string: "https://github.com/wckdboy/autobot")!
    static let privacyStatement = "No analytics. No telemetry. Network only where you allow it."

    let name: String
    /// CFBundleShortVersionString, e.g. "1.6.4".
    let version: String
    /// CFBundleVersion, e.g. "16".
    let build: String
    /// When the app binary was linked (nil when the file date is unreadable).
    let buildDate: Date?

    /// "Autobot 1.6.4 (16)": the Settings row and the diagnostics header.
    var title: String { "\(name) \(versionLabel)" }
    /// "1.6.4 (16)".
    var versionLabel: String { "\(version) (\(build))" }

    init(name: String, version: String, build: String, buildDate: Date?) {
        self.name = name
        self.version = version
        self.build = build
        self.buildDate = buildDate
    }

    init(bundle: Bundle = .main) {
        let info = bundle.infoDictionary ?? [:]
        let name = (info["CFBundleDisplayName"] as? String) ?? (info["CFBundleName"] as? String) ?? "Autobot"
        let date = bundle.executableURL.flatMap { try? FileManager.default.attributesOfItem(atPath: $0.path)[.modificationDate] as? Date }
        self.init(
            name: name,
            version: info["CFBundleShortVersionString"] as? String ?? "?",
            build: info["CFBundleVersion"] as? String ?? "?",
            buildDate: date,
        )
    }

    static let current = AboutInfo()

    /// The bundled release notes as markdown, nil when the resource is missing.
    static func whatsNewMarkdown(bundle: Bundle = .main) -> String? {
        bundle.url(forResource: "whats-new", withExtension: "md").flatMap { try? String(contentsOf: $0, encoding: .utf8) }
    }
}

/// A block of the release notes: headings, bullets and paragraphs (all the notes use).
enum ReleaseNoteBlock: Equatable, Sendable {
    case heading(level: Int, text: AttributedString)
    case bullet(AttributedString)
    case paragraph(AttributedString)
}

/**
 Block-level parsing of the release notes: `#`/`##`/`###` headings, `-`/`*` bullets (continuation
 lines join the bullet) and paragraphs; inline bold, italics, code and links via AttributedString.
 Links are only rendered; nothing is fetched.
 */
enum ReleaseNotes {
    static func parse(_ markdown: String) -> [ReleaseNoteBlock] {
        var blocks: [ReleaseNoteBlock] = []
        var pending: (bullet: Bool, text: String)?

        func flush() {
            guard let p = pending else { return }
            let text = inline(p.text)
            blocks.append(p.bullet ? .bullet(text) : .paragraph(text))
            pending = nil
        }

        for raw in markdown.replacingOccurrences(of: "\r\n", with: "\n").split(separator: "\n", omittingEmptySubsequences: false) {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.isEmpty {
                flush()
                continue
            }
            if line.hasPrefix("#") {
                let level = line.prefix(while: { $0 == "#" }).count
                let rest = line.dropFirst(level)
                if level <= 6, rest.first == " " {
                    flush()
                    blocks.append(.heading(level: level, text: inline(rest.trimmingCharacters(in: .whitespaces))))
                    continue
                }
            }
            if line.hasPrefix("- ") || line.hasPrefix("* ") || line.hasPrefix("+ ") {
                flush()
                pending = (true, String(line.dropFirst(2)))
                continue
            }
            if let p = pending {
                pending = (p.bullet, p.text + " " + line)
            } else {
                pending = (false, line)
            }
        }
        flush()
        return blocks
    }

    static func inline(_ text: String) -> AttributedString {
        let options = AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace, failurePolicy: .returnPartiallyParsedIfPossible)
        return (try? AttributedString(markdown: text, options: options)) ?? AttributedString(text)
    }
}
