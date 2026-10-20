package dev.wckdboy.autobot.feature.home.node

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.component.ActionText
import dev.wckdboy.autobot.core.designsystem.component.ChoiceChip
import dev.wckdboy.autobot.core.designsystem.component.Hairline
import dev.wckdboy.autobot.core.designsystem.component.InkLoader
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.SectionLabel
import dev.wckdboy.autobot.core.designsystem.component.linesFor
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.shared.invincible.InsightBar
import dev.wckdboy.autobot.shared.invincible.NodeBotDetail
import dev.wckdboy.autobot.shared.invincible.NodeBotDetails
import dev.wckdboy.autobot.shared.invincible.NodeInsights
import dev.wckdboy.autobot.shared.invincible.NodeProfile
import dev.wckdboy.autobot.shared.invincible.NodePersonalities
import dev.wckdboy.autobot.shared.invincible.UsageTotals
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Bot detail's node parts: its personality (a choice of the node's), what it knows about the
 * owner (its USER.md, read-only here), and what it did: today and the last week, as words and
 * flat bars. A part an older node does not have is left out.
 */
internal fun LazyListScope.botDetailItems(detail: NodeBotDetail?, bot: String, node: String, setting: String?, onPersonality: (String?) -> Unit) {
    if (detail == null) {
        item(key = "detail-loading") { InkLoader(Modifier.padding(top = 16.dp)) }
        return
    }
    detail.problem?.let { item(key = "detail-problem") { MicroLabel(it, Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.error, maxLines = 3) } }
    detail.personalities?.let { p -> item(key = "personality") { PersonalitySection(p, setting, onPersonality) } }
    detail.profile?.let { p -> item(key = "profile") { ProfileSection(p, bot, node) } }
    if (detail.insights != null || detail.usage != null) {
        item(key = "usage") { UsageSection(detail.insights, detail.usage?.today) }
    }
}

@Composable
internal fun PersonalitySection(p: NodePersonalities, setting: String?, onPersonality: (String?) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).testTag("bot-personality"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel("personality")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val names = listOf(NodeBotDetails.NONE) + p.list.map { it.name }
            names.forEach { name ->
                val selected = NodeBotDetails.personality(p) == name
                ChoiceChip(
                    name,
                    selected = selected,
                    onClick = { if (!selected) onPersonality(name.takeUnless { it == NodeBotDetails.NONE }) },
                    enabled = setting == null,
                    asWritten = true,
                )
            }
        }
        Text(
            NodeBotDetails.personalityLine(p) ?: if (p.active == null) "No personality: it speaks as its SOUL.md says." else "For every turn from now on.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ProfileSection(p: NodeProfile, bot: String, node: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    if (copied) {
        LaunchedEffect(Unit) {
            delay(1500)
            copied = false
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).testTag("bot-profile"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel("what it knows about you")
        if (p.entries.isEmpty()) {
            Text("Nothing yet. It keeps what it learns about how you work as it goes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        p.entries.forEach { entry ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("–", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                Text(entry, style = MaterialTheme.typography.bodyMedium)
            }
        }
        // Read-only here: it is edited where it lives, in the node's terminal.
        // Wraps: at a large font the action goes under the line.
        FlowRow(itemVerticalAlignment = Alignment.CenterVertically) {
            MicroLabel("edit USER.md on $node", Modifier.padding(end = 8.dp), maxLines = 4)
            TextButton(onClick = {
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("command", NodeBotDetails.profileEditCommand(bot)))) }
                copied = true
            }) { ActionText(if (copied) "Copied" else "Copy command", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Hairline()
    }
}

@Composable
internal fun UsageSection(i: NodeInsights?, today: UsageTotals?) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).testTag("bot-usage"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(if (i != null) "usage · last ${NodeBotDetails.plural(i.days, "day")}" else "usage")
        if (i != null) {
            Text(NodeBotDetails.summary(i), style = MaterialTheme.typography.bodyMedium)
        }
        today?.let { MicroLabel("today · " + NodeBotDetails.today(it).removeSuffix("."), maxLines = 2) }
        if (i != null && i.turns > 0) {
            // Local against cloud: one flat bar, words first.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MicroLabel("model calls · " + NodeBotDetails.share(i), maxLines = 2)
                ShareBar(i.localCalls)
                NodeBotDetails.costLine(i)?.let { MicroLabel("cost · $it", maxLines = 2) }
            }
            Bars("tools", NodeBotDetails.toolBars(i))
            Bars("models", NodeBotDetails.modelBars(i))
            NodeBotDetails.failures(i.failures)?.let { MicroLabel("went wrong · $it", maxLines = 2) }
        }
    }
}

/** The local share of a whole, flat: local in onSurfaceVariant, cloud in the hairline color. */
@Composable
private fun ShareBar(local: Double) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(8.dp).background(scheme.outlineVariant, MaterialTheme.shapes.extraSmall).semantics { contentDescription = "local ${NodeBotDetails.percent(local)}" }) {
        if (local > 0) Box(Modifier.weight(local.toFloat().coerceAtLeast(0.001f)).height(8.dp).background(scheme.onSurfaceVariant, MaterialTheme.shapes.extraSmall))
        if (local < 1) Box(Modifier.weight((1 - local).toFloat().coerceAtLeast(0.001f)).height(8.dp))
    }
}

@Composable
private fun Bars(label: String, bars: List<InsightBar>) {
    if (bars.isEmpty()) return
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MicroLabel(label)
        bars.forEach { bar ->
            Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(bar.label, Modifier.weight(1f), style = AutobotTheme.styles.code, maxLines = linesFor(1, 2), overflow = TextOverflow.Ellipsis)
                    Text(bar.value, style = AutobotTheme.styles.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val scheme = MaterialTheme.colorScheme
                Row(Modifier.fillMaxWidth().height(4.dp).background(scheme.surfaceContainerHigh)) {
                    Box(Modifier.weight(bar.fraction.toFloat().coerceIn(0.001f, 1f)).height(4.dp).background(scheme.onSurfaceVariant))
                    if (bar.fraction < 1) Box(Modifier.weight((1 - bar.fraction).toFloat().coerceAtLeast(0.001f)).height(4.dp))
                }
            }
        }
    }
}
