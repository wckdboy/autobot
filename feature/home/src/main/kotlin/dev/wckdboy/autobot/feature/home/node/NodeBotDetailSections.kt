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
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.designsystem.component.labelCase
import dev.wckdboy.autobot.feature.home.remoteui.Loading
import dev.wckdboy.autobot.feature.home.remoteui.Subheader
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.semantics.clearAndSetSemantics
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
        item(key = "detail-loading") { Loading(Modifier.padding(top = 16.dp)) }
        return
    }
    detail.problem?.let { item(key = "detail-problem") { Text(labelCase(it), Modifier.padding(horizontal = 16.dp).padding(top = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } }
    detail.personalities?.let { p -> item(key = "personality") { PersonalitySection(p, setting, onPersonality) } }
    detail.profile?.let { p -> item(key = "profile") { ProfileSection(p, bot, node) } }
    if (detail.insights != null || detail.usage != null) {
        item(key = "usage") { UsageSection(detail.insights, detail.usage?.today) }
    }
}

@Composable
internal fun PersonalitySection(p: NodePersonalities, setting: String?, onPersonality: (String?) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("bot-personality"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Subheader(labelCase("personality"))
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val names = listOf(NodeBotDetails.NONE) + p.list.map { it.name }
            names.forEach { name ->
                val selected = NodeBotDetails.personality(p) == name
                FilterChip(
                    selected = selected,
                    onClick = { if (!selected) onPersonality(name.takeUnless { it == NodeBotDetails.NONE }) },
                    label = { Text(name) },
                    enabled = setting == null,
                )
            }
        }
        Text(
            NodeBotDetails.personalityLine(p) ?: if (p.active == null) "No personality: it speaks as its SOUL.md says." else "For every turn from now on.",
            Modifier.padding(horizontal = 16.dp),
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
    Subheader(labelCase("what it knows about you"))
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("bot-profile"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
            Text(labelCase("edit USER.md on $node"), Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = {
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("command", NodeBotDetails.profileEditCommand(bot)))) }
                copied = true
            }) { Text(if (copied) "Copied" else "Copy command") }
        }
    }
}

@Composable
internal fun UsageSection(i: NodeInsights?, today: UsageTotals?) {
    Subheader(labelCase(if (i != null) "usage · last ${NodeBotDetails.plural(i.days, "day")}" else "usage"))
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("bot-usage"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (i != null) {
            Text(NodeBotDetails.summary(i), style = MaterialTheme.typography.bodyMedium)
        }
        today?.let { Caption("today · " + NodeBotDetails.today(it).removeSuffix(".")) }
        if (i != null && i.turns > 0) {
            // Local against cloud: one flat bar, words first.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Caption("model calls · " + NodeBotDetails.share(i))
                ShareBar(i.localCalls)
                NodeBotDetails.costLine(i)?.let { Caption("cost · $it") }
            }
            Bars("tools", NodeBotDetails.toolBars(i))
            Bars("models", NodeBotDetails.modelBars(i))
            NodeBotDetails.failures(i.failures)?.let { Caption("went wrong · $it") }
        }
    }
}

/** The local share of a whole: Material's linear indicator, local filled, cloud the track. */
@Composable
private fun ShareBar(local: Double) {
    LinearProgressIndicator(
        progress = { local.toFloat().coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "local ${NodeBotDetails.percent(local)}" },
    )
}

/** A quiet caption line in sentence case ("Model calls · all local"). */
@Composable
private fun Caption(text: String) {
    Text(labelCase(text), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Bars(label: String, bars: List<InsightBar>) {
    if (bars.isEmpty()) return
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Caption(label)
        bars.forEach { bar ->
            Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(bar.label, Modifier.weight(1f), style = AutobotTheme.styles.code)
                    Text(bar.value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LinearProgressIndicator(progress = { bar.fraction.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
            }
        }
    }
}
