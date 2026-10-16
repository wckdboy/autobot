import SwiftUI
import UIKit

/**
 SETTINGS › ABOUT: the app's name, version, build and build date; this version's release notes
 (bundled, not fetched); links out to the releases page and the source (they open in Safari,
 nothing loads in the app); licences; the privacy statement; and "Copy diagnostics" (the Compute
 report, copied without being sent). No network requests from here.
 */
struct AboutScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c
    @Environment(\.openURL) private var openURL
    @State private var copied = false
    @State private var allNotes = false

    /// Blocks of the release notes shown before "Show all".
    static let notesPreview = 4
    private let info = AboutInfo.current
    /// Parsed once per launch (the screen struct is re-created whenever its parent re-renders).
    private static let releaseNotes: [ReleaseNoteBlock]? = AboutInfo.whatsNewMarkdown().map(ReleaseNotes.parse)
    private var notes: [ReleaseNoteBlock]? { Self.releaseNotes }

    var body: some View {
        VStack(spacing: 0) {
            AutobotTopBar(title: "About", onBack: { nav.back() })
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    identity
                    Hairline().padding(.vertical, 8)
                    header("What's new in \(info.version)")
                    whatsNew
                    NavigationRow(title: "All releases", trailingSymbol: "arrow.up.right") { openURL(AboutInfo.releasesURL) }
                        .accessibilityHint("Opens github.com in Safari")
                    Hairline().padding(.vertical, 8)
                    header("Autobot")
                    NavigationRow(title: "Source code", trailingSymbol: "arrow.up.right") { openURL(AboutInfo.sourceURL) }
                        .accessibilityHint("Opens github.com in Safari")
                    NavigationRow(title: "Licenses") { nav.push(.licenses) }
                    NavigationRow(title: copied ? "Diagnostics copied" : "Copy diagnostics", trailingSymbol: copied ? "checkmark" : "doc.on.doc") {
                        copyDiagnostics()
                    }
                    .disabled(!DiagnosticsReportBuilder.available)
                    MicroLabel(AboutInfo.privacyStatement.lowercased(), lines: 2)
                        .padding(.horizontal, Tokens.Spacing.gutter)
                        .padding(.top, 12)
                        .padding(.bottom, 24)
                }
            }
        }
        .background(c.background)
    }

    private var identity: some View {
        HStack(alignment: .center, spacing: 16) {
            AutobotMark(size: 64)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            VStack(alignment: .leading, spacing: 4) {
                Text(info.name).textStyle(.headlineMedium).foregroundStyle(c.onSurface)
                Text("Version \(info.version) · build \(info.build)").textStyle(.bodyMedium).foregroundStyle(c.onSurfaceVariant)
                if let date = info.buildDate {
                    MicroLabel("built \(date.formatted(date: .abbreviated, time: .omitted))")
                }
            }
            .accessibilityElement(children: .combine)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, Tokens.Spacing.gutter)
        .padding(.vertical, 16)
    }

    @ViewBuilder private var whatsNew: some View {
        if let notes, !notes.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                let shown = allNotes ? notes : Array(notes.prefix(Self.notesPreview))
                ForEach(Array(shown.enumerated()), id: \.offset) { _, block in
                    switch block {
                    case .heading(_, let text):
                        Text(text).textStyle(.titleSmall).foregroundStyle(c.onSurface).padding(.top, 6)
                    case .bullet(let text):
                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                            Text("•").textStyle(.bodyMedium).foregroundStyle(c.primary).accessibilityHidden(true)
                            Text(text).textStyle(.bodyMedium).foregroundStyle(c.onSurfaceVariant)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    case .paragraph(let text):
                        Text(text).textStyle(.bodyMedium).foregroundStyle(c.onSurface)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                if notes.count > Self.notesPreview {
                    TextAction(allNotes ? "Show less" : "Show all", color: c.primary) {
                        withAnimation(.easeInOut(duration: 0.2)) { allNotes.toggle() }
                    }
                    .frame(minHeight: 44)
                    .padding(.leading, -12) // the label lines up with the notes (TextAction pads its target)
                }
            }
            .tint(c.primary)
            .textSelection(.enabled)
            .padding(.horizontal, Tokens.Spacing.gutter)
            .padding(.top, 4)
            .padding(.bottom, 4)
        } else {
            MicroLabel("release notes are missing from this build").padding(.horizontal, Tokens.Spacing.gutter).padding(.bottom, 8)
        }
    }

    private func header(_ text: String) -> some View {
        SectionLabel(text).padding(.horizontal, Tokens.Spacing.gutter).padding(.top, 12).padding(.bottom, 4)
    }

    private func copyDiagnostics() {
        guard let report = DiagnosticsReportBuilder.render(app: app, devices: ComputeBench.quickDevices()) else { return }
        UIPasteboard.general.setItems([["public.utf8-plain-text": report]], options: [.localOnly: true])
        copied = true
        Task {
            try? await Task.sleep(for: .seconds(2))
            copied = false
        }
    }
}
