import SwiftUI

/// Riso inks for diffs (Compose `diffInk`): added lines in cyan, removed lines in red.
private func diffInk(_ line: String, _ c: AutobotScheme) -> Color {
    if line.hasPrefix("+") { return AutobotColors.cyan }
    if line.hasPrefix("-") { return c.primary }
    return c.onSurfaceVariant
}

/// `+3 −1` for a file step, in the diff inks.
struct ChangeCounts: View {
    let change: FileChange
    @Environment(\.scheme) private var c

    var body: some View {
        HStack(spacing: 4) {
            if change.added > 0 { Text("+\(change.added)").textStyle(.micro).foregroundStyle(AutobotColors.cyan) }
            if change.removed > 0 { Text("−\(change.removed)").textStyle(.micro).foregroundStyle(c.primary) }
            if change.added == 0 && change.removed == 0 { Text(change.op.rawValue).textStyle(.micro).foregroundStyle(c.onSurfaceVariant) }
        }
    }
}

/// A step's line diff: monospace, `+` lines on a cyan wash, `−` lines on a red one, scrolls sideways.
struct DiffBlock: View {
    let change: FileChange
    @Environment(\.scheme) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if change.diff.isEmpty {
                Text(change.note ?? (change.op == .deleted ? "folder removed" : "no text changes"))
                    .textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).padding(.horizontal, 10)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(Array(change.diff.enumerated()), id: \.offset) { _, line in
                            let ink = diffInk(line, c)
                            let changed = line.hasPrefix("+") || line.hasPrefix("-")
                            Text(line.isEmpty ? " " : line)
                                .textStyle(.codeBlock)
                                .foregroundStyle(ink)
                                .lineLimit(1)
                                .fixedSize()
                                .padding(.horizontal, 10)
                                .background(changed ? ink.opacity(0.12) : .clear)
                        }
                        if change.truncated { MicroLabel("… diff shortened").padding(.horizontal, 10).padding(.vertical, 2) }
                    }
                }
                if let note = change.note { MicroLabel(note, lines: 2).padding(.horizontal, 10).padding(.vertical, 2) }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 6)
        .background(c.surfaceContainerLow)
        .textSelection(.enabled)
    }
}

/// Under a turn's last answer: `↶ UNDO TURN · 2 FILES`, or `↷ REDO TURN` once undone. Stamps in when the state flips.
struct UndoTurnLine: View {
    let changes: TurnChanges?
    let running: Bool
    let onUndo: (Int) -> Void
    let onRedo: (String) -> Void
    @Environment(\.scheme) private var c

    var body: some View {
        if let changes, changes.files > 0 {
            let files = "\(changes.files) file\(changes.files == 1 ? "" : "s")"
            ZStack(alignment: .leading) {
                if changes.undone, let redo = changes.revertId {
                    Tag("↷ redo turn · " + files, accent: c.primary) { if !running { onRedo(redo) } }
                } else if changes.undone {
                    Tag("undone · " + files)
                } else if changes.undoable {
                    Tag("↶ undo turn · " + files, accent: c.primary) { if !running { onUndo(changes.turn) } }
                }
            }
            .stamp(changes.undone)
        }
    }
}

/// Every file the agent changed in this chat, grouped by turn, with undo per file step and per turn.
struct ChangesSheet: View {
    let groups: [ChangeGroup]
    let running: Bool
    let onUndoStep: (String) -> Void
    let onUndoTurn: (Int) -> Void
    @Environment(\.scheme) private var c

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                SectionLabel("changes · \(groups.reduce(0) { $0 + $1.files.count }) files")
                if groups.isEmpty {
                    Text("The agent hasn't changed any files in this chat.").textStyle(.bodyMedium).foregroundStyle(c.onSurfaceVariant)
                }
                ForEach(groups.reversed()) { group in
                    Hairline().padding(.vertical, 2)
                    HStack {
                        MicroLabel("turn \(group.turn)").frame(maxWidth: .infinity, alignment: .leading)
                        if group.files.contains(where: { !$0.undone }) {
                            Tag("↶ undo turn", accent: c.primary) { if !running { onUndoTurn(group.turn) } }
                        }
                    }
                    ForEach(group.files) { file in
                        HStack(spacing: 8) {
                            Text(file.path)
                                .textStyle(.code)
                                .strikethrough(file.undone)
                                .foregroundStyle(file.undone ? c.onSurfaceVariant : c.onSurface)
                                .lineLimit(1)
                                .truncationMode(.middle)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            ChangeCounts(change: FileChange(path: file.path, op: file.op, added: file.added, removed: file.removed))
                            ZStack {
                                if file.undone {
                                    MicroLabel("undone")
                                } else {
                                    Tag("undo") { if !running { onUndoStep(file.callId) } }
                                }
                            }
                            .stamp(file.undone)
                        }
                    }
                }
                Hairline().padding(.vertical, 2)
                Text("Before the agent changes a file, Autobot keeps a copy on this phone (up to 50 MB per chat), so any step or turn can be undone.")
                    .textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
            }
            .padding(.horizontal, 16)
            .padding(.top, 20)
            .padding(.bottom, 24)
        }
        .presentationDetents([.medium, .large])
        .presentationBackground(c.surfaceContainerLow)
        .presentationDragIndicator(.visible)
    }
}
