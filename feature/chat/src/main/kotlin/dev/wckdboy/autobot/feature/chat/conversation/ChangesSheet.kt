package dev.wckdboy.autobot.feature.chat.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import dev.wckdboy.autobot.core.designsystem.component.TonalLabel
import dev.wckdboy.autobot.core.designsystem.icon.AutobotIcons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.component.counted
import dev.wckdboy.autobot.core.designsystem.motion.stamp
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.shared.session.ChangeGroup
import dev.wckdboy.autobot.shared.session.ChangeOp
import dev.wckdboy.autobot.shared.session.FileChange
import dev.wckdboy.autobot.shared.session.TurnChanges

/** Diff colours: added lines in the tertiary colour, removed lines in the error colour. */
@Composable
private fun diffInk(line: String): Color = when {
    line.startsWith("+") -> MaterialTheme.colorScheme.tertiary
    line.startsWith("-") -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** `+3 −1` for a file step, in the diff inks. */
@Composable
internal fun ChangeCounts(change: FileChange) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val style = MaterialTheme.typography.labelMedium
        if (change.added > 0) Text("+${change.added}", style = style, color = MaterialTheme.colorScheme.tertiary)
        if (change.removed > 0) Text("−${change.removed}", style = style, color = MaterialTheme.colorScheme.error)
        if (change.added == 0 && change.removed == 0) {
            Text(change.op.name.lowercase(), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A step's line diff (code: monospace), `+` and `−` lines on a wash of their colour, scrolling sideways. */
@Composable
internal fun DiffBlock(change: FileChange, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(vertical = 8.dp)) {
        if (change.diff.isEmpty()) {
            Text(
                change.note ?: if (change.op == ChangeOp.DELETED) "folder removed" else "no text changes",
                modifier = Modifier.padding(horizontal = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                change.diff.forEach { line ->
                    val ink = diffInk(line)
                    val wash = if (line.startsWith("+") || line.startsWith("-")) ink.copy(alpha = 0.12f) else Color.Transparent
                    Text(
                        if (line.startsWith("@@")) line else line.ifEmpty { " " },
                        modifier = Modifier.background(wash).padding(horizontal = 10.dp),
                        style = AutobotTheme.styles.codeBlock,
                        color = ink,
                        softWrap = false,
                    )
                }
                if (change.truncated) Note("… diff shortened", Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
            }
            change.note?.let { Note(it, Modifier.padding(horizontal = 10.dp, vertical = 2.dp)) }
        }
    }
}

/** A line of detail in the label type. */
@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Under a turn's last answer: `Undo turn · 2 files`, or `Redo turn` once undone. Stamps in when
 * the state flips.
 */
@Composable
internal fun UndoTurnLine(changes: TurnChanges?, running: Boolean, onUndo: (Int) -> Unit, onRedo: (String) -> Unit) {
    if (changes == null || changes.files == 0) return
    val files = "${changes.files} file${if (changes.files == 1) "" else "s"}"
    Box(Modifier.stamp(changes.undone, enabled = true)) {
        val redoId = changes.revertId
        when {
            changes.undone && redoId != null -> AssistChip(onClick = { if (!running) onRedo(redoId) }, label = { Text("Redo turn · $files") })
            changes.undone -> TonalLabel("Undone · $files")
            changes.undoable -> AssistChip(
                onClick = { if (!running) onUndo(changes.turn) },
                label = { Text("Undo turn · $files") },
                leadingIcon = { Icon(AutobotIcons.Undo, contentDescription = null, Modifier.size(AssistChipDefaults.IconSize)) },
            )
        }
    }
}

/** Every file the agent changed in this chat, grouped by turn, with undo per file step and per turn. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChangesSheet(
    groups: List<ChangeGroup>,
    running: Boolean,
    onUndoStep: (String) -> Unit,
    onUndoTurn: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Changes · ${counted(groups.sumOf { it.files.size }, "file")}",
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (groups.isEmpty()) {
                Text("The agent hasn't changed any files in this chat.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            groups.asReversed().forEach { group ->
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Turn ${group.turn}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    if (group.files.any { !it.undone }) {
                        TextButton(onClick = { if (!running) onUndoTurn(group.turn) }) { Text("Undo turn") }
                    }
                }
                group.files.forEach { file ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            file.path,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textDecoration = if (file.undone) TextDecoration.LineThrough else null,
                            color = if (file.undone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                        ChangeCounts(FileChange(file.path, file.op, file.added, file.removed))
                        Box(Modifier.stamp(file.undone)) {
                            if (file.undone) {
                                Note("Undone")
                            } else {
                                TextButton(onClick = { if (!running) onUndoStep(file.callId) }) { Text("Undo") }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(
                "Before the agent changes a file, Autobot keeps a copy on this phone (up to 50 MB per chat), so any step or turn can be undone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
