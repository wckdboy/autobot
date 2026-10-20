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
            List {
                Section { identity }
                Section("What's new in \(info.version)") {
                    whatsNew
                    external("All releases", AboutInfo.releasesURL)
                }
                Section {
                    external("Source code", AboutInfo.sourceURL)
                    PushRow(title: "Licenses") { nav.push(.licenses) }
                    Button(action: copyDiagnostics) {
                        Label(copied ? "Diagnostics copied" : "Copy diagnostics", systemImage: copied ? "checkmark" : "doc.on.doc")
                    }
                    .disabled(!DiagnosticsReportBuilder.available)
                } header: {
                    Text("Autobot")
                } footer: {
                    Text(AboutInfo.privacyStatement)
                }
            }
            .listStyle(.insetGrouped)
        }
    }

    /// A link that leaves the app (Safari), with the system's outward arrow.
    private func external(_ title: String, _ url: URL) -> some View {
        Button { openURL(url) } label: {
            HStack {
                Text(title).foregroundStyle(.primary)
                Spacer()
                Image(systemName: "arrow.up.right").font(.footnote.weight(.semibold)).foregroundStyle(Color(uiColor: .tertiaryLabel))
                    .accessibilityHidden(true)
            }
            .contentShape(Rectangle())
        }
        .accessibilityHint("Opens github.com in Safari")
    }

    private var identity: some View {
        HStack(alignment: .center, spacing: 16) {
            AppIconImage(size: 64)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .shadow(color: .black.opacity(0.08), radius: 2, y: 1)
            VStack(alignment: .leading, spacing: 4) {
                Text(info.name).font(.title2.weight(.semibold))
                Text("Version \(info.version) · build \(info.build)").font(.subheadline).foregroundStyle(.secondary)
                if let date = info.buildDate {
                    Text("built \(date.formatted(date: .abbreviated, time: .omitted))").font(.caption).foregroundStyle(.secondary)
                }
            }
            .accessibilityElement(children: .combine)
            Spacer(minLength: 0)
        }
        .padding(.vertical, 4)
    }

    @ViewBuilder private var whatsNew: some View {
        if let notes, !notes.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                let shown = allNotes ? notes : Array(notes.prefix(Self.notesPreview))
                ForEach(Array(shown.enumerated()), id: \.offset) { _, block in
                    switch block {
                    case .heading(_, let text):
                        Text(text).font(.headline).padding(.top, 4)
                    case .bullet(let text):
                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                            Text("•").foregroundStyle(.secondary).accessibilityHidden(true)
                            Text(text).foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .font(.subheadline)
                    case .paragraph(let text):
                        Text(text).font(.subheadline)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            .textSelection(.enabled)
            .padding(.vertical, 4)
            if notes.count > Self.notesPreview {
                Button(allNotes ? "Show less" : "Show all") {
                    withAnimation(.easeInOut(duration: 0.2)) { allNotes.toggle() }
                }
            }
        } else {
            Text("release notes are missing from this build").font(.footnote).foregroundStyle(.secondary)
        }
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
