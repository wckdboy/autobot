package dev.wckdboy.autobot.feature.settings.usage

import dev.wckdboy.autobot.core.designsystem.component.ActionText
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wckdboy.autobot.core.designsystem.component.AutobotTopBar
import dev.wckdboy.autobot.core.designsystem.component.BigReadout
import dev.wckdboy.autobot.core.designsystem.component.Hairline
import dev.wckdboy.autobot.core.designsystem.component.InkProgressBar
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.NoPersonalizedLearning
import dev.wckdboy.autobot.core.designsystem.component.Panel
import dev.wckdboy.autobot.core.designsystem.component.SectionLabel
import dev.wckdboy.autobot.core.designsystem.component.Segmented
import dev.wckdboy.autobot.core.designsystem.component.Tag
import dev.wckdboy.autobot.core.designsystem.theme.AutobotColors
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.shared.usage.LimitUnit
import dev.wckdboy.autobot.shared.usage.PriceRow
import dev.wckdboy.autobot.shared.usage.PriceSource
import dev.wckdboy.autobot.shared.usage.SubscriptionWindow
import dev.wckdboy.autobot.shared.usage.UsageFormat
import dev.wckdboy.autobot.shared.usage.UsageGroup
import dev.wckdboy.autobot.shared.usage.UsageSeries
import dev.wckdboy.autobot.shared.usage.WindowPeriod
import dev.wckdboy.autobot.shared.usage.WindowStatus
import java.text.DateFormat
import java.util.Date
import java.util.UUID

@Composable
fun UsageRoute(onBack: () -> Unit, viewModel: UsageViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    UsageScreen(state, viewModel, onBack)
}

/**
 * The spend and quota board: tokens and cost by period, a bar per day (week, month), the
 * breakdown by provider and model, subscription windows with what is left, and the price table.
 * Everything is computed on this phone from the providers' usage reports.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UsageScreen(state: UsageUiState, actions: UsageActions, onBack: () -> Unit) {
    var periodKey by rememberSaveable { mutableStateOf("day") }
    var seriesKey by rememberSaveable { mutableStateOf("day") }
    var byModel by rememberSaveable { mutableStateOf(false) }
    var editingPrice by remember { mutableStateOf<PriceRow?>(null) }
    var editingWindow by remember { mutableStateOf<SubscriptionWindow?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val report = state.report

    editingPrice?.let { row -> PriceDialog(row, onDismiss = { editingPrice = null }, onSave = { i, o, cr, cw -> actions.savePrice(row.provider, row.model, i, o, cr, cw); editingPrice = null }) }
    editingWindow?.let { w ->
        WindowDialog(
            window = w,
            providers = state.providers,
            isNew = report?.plan?.windows?.none { it.id == w.id } ?: true,
            onDismiss = { editingWindow = null },
            onSave = { actions.saveWindow(it); editingWindow = null },
            onDelete = { actions.deleteWindow(w.id); editingWindow = null },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear usage history?") },
            text = { Text("The counted tokens are removed from this phone. Prices and windows stay.") },
            confirmButton = { TextButton(onClick = { actions.clearHistory(); confirmClear = false }) { ActionText("Clear", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { ActionText("Cancel") } },
        )
    }
    state.message?.let { m ->
        AlertDialog(onDismissRequest = actions::dismissMessage, text = { Text(m) }, confirmButton = { TextButton(onClick = actions::dismissMessage) { ActionText("OK") } })
    }

    Scaffold(
        topBar = {
            AutobotTopBar(
                title = "Usage",
                navigation = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (report == null) {
                item { MicroLabel("counting…") }
                return@LazyColumn
            }
            val period = report.periods.firstOrNull { it.key == periodKey } ?: report.periods.first()
            item {
                Segmented(report.periods, period, { periodKey = it.key }, { it.label }, Modifier.fillMaxWidth())
            }
            item {
                // Wraps: "$6.00 + unknown" in the display face, at a large font size, leaves no room for requests.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigReadout("tokens", UsageFormat.tokens(period.sums.tokens))
                    BigReadout("cost · usd", period.sums.costLine)
                    BigReadout("requests", period.sums.requests.toString())
                }
                Spacer(Modifier.height(6.dp))
                val s = period.sums
                MicroLabel("${UsageFormat.tokens(s.input)} in · ${UsageFormat.tokens(s.output)} out · ${UsageFormat.tokens(s.cacheRead)} cached")
                if (s.onDeviceTokens > 0) MicroLabel("on this phone · ${UsageFormat.tokens(s.onDeviceTokens)} tokens · $0")
                if (s.unpricedTokens > 0) MicroLabel("${UsageFormat.tokens(s.unpricedTokens)} tokens without a price", color = AutobotTheme.inks.amber)
            }
            item {
                val series = report.series.firstOrNull { it.key == seriesKey } ?: report.series.first()
                Panel(title = "per ${series.key}", trailing = {
                    report.series.forEach { s ->
                        Tag(s.label, accent = if (s.key == series.key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, filled = s.key == series.key, onClick = { seriesKey = s.key })
                    }
                }) {
                    Bars(series)
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("by ${if (byModel) "model" else "provider"} · ${period.label}", Modifier.weight(1f))
                    Tag(if (byModel) "providers" else "models", onClick = { byModel = !byModel })
                }
            }
            val groups = if (byModel) period.models else period.providers
            if (groups.isEmpty()) item { MicroLabel("nothing counted ${period.label}") }
            items(groups, key = { "${it.provider}|${it.model}" }) { GroupRow(it) }

            item { SectionLabel("subscription windows", Modifier.padding(top = 8.dp)) }
            items(report.windows, key = { it.id }) { w -> WindowRow(w, onEdit = { editingWindow = report.plan.windows.firstOrNull { it.id == w.id } }) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.providers.firstOrNull()?.let { first ->
                        Tag("+ window", accent = MaterialTheme.colorScheme.primary, onClick = {
                            editingWindow = SubscriptionWindow(UUID.randomUUID().toString(), first.id, WindowPeriod.FIVE_HOURS, anchor = System.currentTimeMillis())
                        })
                    }
                    state.providers.filter { p -> p.openCodeGo && report.plan.windows.none { it.provider == p.id } }.forEach { p ->
                        Tag("+ ${p.name} 5 h / week / month", accent = MaterialTheme.colorScheme.primary, onClick = { actions.addOpenCodeGoWindows(p.id) })
                    }
                }
                if (state.providers.isEmpty()) MicroLabel("add a cloud provider to track its plan", maxLines = 3)
            }

            item { SectionLabel("prices · usd per 1m tokens", Modifier.padding(top = 8.dp)) }
            if (report.prices.isEmpty()) item { MicroLabel("models show up here once they have answered", maxLines = 3) }
            items(report.prices, key = { "${it.provider}|${it.model}" }) { row -> PriceRowView(row, onClick = { editingPrice = row }) }

            item {
                Hairline(Modifier.padding(vertical = 4.dp))
                Text(
                    "Counted on this phone from each provider's own usage report; nothing is sent. Costs use your price table: " +
                        "only Anthropic's published prices ship, every other model stays unknown until you enter its price. " +
                        "Incognito chats are not kept.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!report.empty) TextButton(onClick = { confirmClear = true }) { ActionText("Clear history", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

/** One bar per day (week, month): the ink part is the cloud, the hollow part ran on this phone. */
@Composable
private fun Bars(series: UsageSeries) {
    val ink = MaterialTheme.colorScheme.primary
    val local = MaterialTheme.colorScheme.onSurfaceVariant
    val track = MaterialTheme.colorScheme.outlineVariant
    val description = "Tokens per ${series.key}: " + series.bars.joinToString("; ") { "${it.label}: ${UsageFormat.tokens(it.tokens)}" }
    Canvas(Modifier.fillMaxWidth().height(72.dp).semantics { contentDescription = description }) {
        val n = series.bars.size.coerceAtLeast(1)
        val gap = 3.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        series.bars.forEachIndexed { i, bar ->
            val x = i * (w + gap)
            drawRect(track, Offset(x, size.height - 1.dp.toPx()), Size(w, 1.dp.toPx()))
            val h = (bar.fraction * size.height).toFloat()
            if (h <= 0f) return@forEachIndexed
            val localH = if (bar.tokens > 0) h * bar.onDeviceTokens / bar.tokens else 0f
            drawRect(ink, Offset(x, size.height - h), Size(w, h - localH))
            if (localH > 0f) drawRect(local, Offset(x, size.height - localH), Size(w, localH), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        }
    }
    Row(Modifier.fillMaxWidth()) {
        MicroLabel(series.bars.firstOrNull()?.label.orEmpty(), Modifier.weight(1f))
        val max = series.bars.maxOfOrNull { it.tokens } ?: 0
        MicroLabel("peak ${UsageFormat.tokens(max)}", Modifier.weight(1f))
        MicroLabel(series.bars.lastOrNull()?.label.orEmpty())
    }
}

@Composable
private fun GroupRow(group: UsageGroup) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(group.model ?: group.name, style = AutobotTheme.styles.code, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (group.onDevice) Tag("on device", accent = MaterialTheme.colorScheme.primary)
            Text(if (group.onDevice) "$0" else group.sums.costLine, style = AutobotTheme.styles.readout)
        }
        MicroLabel(
            listOfNotNull(
                group.name.takeIf { group.model != null },
                "${UsageFormat.tokens(group.sums.tokens)} tokens",
                "${group.sums.requests} req",
                group.sums.cacheRead.takeIf { it > 0 }?.let { "${UsageFormat.tokens(it)} cached" },
                group.sums.unreported.takeIf { it > 0 }?.let { "$it without usage report" },
            ).joinToString(" · "),
            maxLines = 3,
        )
    }
}

@Composable
private fun WindowRow(status: WindowStatus, onEdit: () -> Unit) {
    val accent = if (status.over) AutobotTheme.inks.amber else MaterialTheme.colorScheme.primary
    Panel(
        modifier = Modifier.clickable(onClick = onEdit),
        title = "${status.providerName} · ${status.period.label}${if (status.rolling) " · rolling" else ""}",
        accent = accent,
        trailing = { Tag("edit", onClick = onEdit) },
    ) {
        status.fraction?.let { f -> InkProgressBar(f.toFloat().coerceIn(0f, 1f), Modifier.fillMaxWidth().height(2.dp), color = accent) }
        Text(status.line, style = MaterialTheme.typography.bodySmall, color = if (status.over) AutobotTheme.inks.amber else MaterialTheme.colorScheme.onSurface)
        if (status.limit == null) MicroLabel("no limit set · tap edit to enter your plan's limit", maxLines = 3)
    }
}

@Composable
private fun PriceRowView(row: PriceRow, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(row.model, style = AutobotTheme.styles.code, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            when (row.source) {
                PriceSource.DEFAULT -> Tag("published")
                PriceSource.YOURS -> Tag("yours", accent = MaterialTheme.colorScheme.primary)
                PriceSource.NONE -> Tag("unknown", accent = AutobotTheme.inks.amber)
            }
        }
        MicroLabel(
            if (row.input == null && row.output == null) {
                "${row.providerName} · tap to enter"
            } else {
                listOfNotNull(
                    row.providerName,
                    "in ${UsageFormat.price(row.input).ifEmpty { "?" }}",
                    "out ${UsageFormat.price(row.output).ifEmpty { "?" }}",
                    row.cacheRead?.let { "cached ${UsageFormat.price(it)}" },
                    row.cacheWrite?.let { "cache write ${UsageFormat.price(it)}" },
                ).joinToString(" · ")
            },
            maxLines = 3,
        )
    }
}

@Composable
private fun PriceDialog(row: PriceRow, onDismiss: () -> Unit, onSave: (String, String, String, String) -> Unit) {
    var input by remember { mutableStateOf(UsageFormat.price(row.input)) }
    var output by remember { mutableStateOf(UsageFormat.price(row.output)) }
    var cacheRead by remember { mutableStateOf(UsageFormat.price(row.cacheRead)) }
    var cacheWrite by remember { mutableStateOf(UsageFormat.price(row.cacheWrite)) }
    // A typo must not save as "unknown": SAVE waits until every filled-in field is a price.
    val valid = listOf(input, output, cacheRead, cacheWrite).all { it.isBlank() || UsageFormat.parsePrice(it) != null }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.model, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MicroLabel("${row.providerName} · usd per 1m tokens")
                NoPersonalizedLearning {
                    PriceField("Input", input) { input = it }
                    PriceField("Output", output) { output = it }
                    PriceField("Cached input", cacheRead) { cacheRead = it }
                    PriceField("Cache write", cacheWrite) { cacheWrite = it }
                }
                Text(
                    "From the provider's pricing page. Blank stays unknown; blank cache prices count at the input price.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(input, output, cacheRead, cacheWrite) }, enabled = valid) { ActionText("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { ActionText("Cancel") } },
    )
}

@Composable
private fun PriceField(label: String, value: String, onChange: (String) -> Unit) {
    val invalid = value.isNotBlank() && UsageFormat.parsePrice(value) == null
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = invalid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WindowDialog(
    window: SubscriptionWindow,
    providers: List<UsageProviderOption>,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (SubscriptionWindow) -> Unit,
    onDelete: () -> Unit,
) {
    var draft by remember { mutableStateOf(window) }
    var limit by remember { mutableStateOf(window.limit?.let { if (window.unit == LimitUnit.USD) UsageFormat.price(it) else it.toLong().toString() }.orEmpty()) }
    val dates = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New window" else "Window") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MicroLabel("provider")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    providers.forEach { p ->
                        Tag(p.name, accent = if (p.id == draft.provider) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, filled = p.id == draft.provider, onClick = { draft = draft.copy(provider = p.id) })
                    }
                }
                MicroLabel("length")
                Segmented(WindowPeriod.entries, draft.period, { draft = draft.copy(period = it) }, { it.short }, Modifier.fillMaxWidth())
                MicroLabel("counts")
                Segmented(listOf(true, false), draft.rolling, { draft = draft.copy(rolling = it, anchor = draft.anchor ?: System.currentTimeMillis()) }, { if (it) "rolling" else "fixed" }, Modifier.fillMaxWidth())
                if (!draft.rolling) {
                    MicroLabel("resets every ${draft.period.short} from ${dates.format(Date(draft.anchor ?: System.currentTimeMillis()))}")
                    Tag("it reset just now", accent = MaterialTheme.colorScheme.primary, onClick = { draft = draft.copy(anchor = System.currentTimeMillis()) })
                }
                MicroLabel("limit")
                Segmented(LimitUnit.entries, draft.unit, { draft = draft.copy(unit = it) }, { it.label }, Modifier.fillMaxWidth())
                NoPersonalizedLearning {
                    OutlinedTextField(
                        value = limit,
                        onValueChange = { limit = it },
                        label = { Text("Limit (blank: none)") },
                        singleLine = true,
                        isError = limit.isNotBlank() && parseLimit(limit, draft.unit) == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    "Enter the limit from your plan's page. Rolling counts the last ${draft.period.short}; fixed starts over at the same time each ${draft.period.short}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!isNew) TextButton(onClick = onDelete) { ActionText("Delete window", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft.copy(limit = parseLimit(limit, draft.unit))) }, enabled = limit.isBlank() || parseLimit(limit, draft.unit) != null) { ActionText("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { ActionText("Cancel") } },
    )
}

/**
 * A window limit as typed: dollars like a price, token and request counts as whole numbers that
 * may carry grouping (`1,000,000`, `1.000.000`, `1 000 000`). Null: not a number (blank included).
 */
internal fun parseLimit(text: String, unit: LimitUnit): Double? =
    if (unit == LimitUnit.USD) {
        UsageFormat.parsePrice(text)
    } else {
        text.trim().takeIf { WHOLE_COUNT.matches(it) }?.filter { it.isDigit() }?.toLongOrNull()?.toDouble()
    }

/** `1500000`, or digits grouped by threes with one separator (`1,500,000`); `1.5` is not a count. */
private val WHOLE_COUNT = Regex("""\d+|\d{1,3}([,. _'\u00A0\u202F])\d{3}(\1\d{3})*""")
