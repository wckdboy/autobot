package dev.wckdboy.autobot.feature.settings.usage

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
import dev.wckdboy.autobot.core.designsystem.component.InfoButton
import dev.wckdboy.autobot.core.designsystem.component.labelCase
import dev.wckdboy.autobot.shared.usage.UsageWords
import dev.wckdboy.autobot.core.designsystem.component.NoPersonalizedLearning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedCard
import dev.wckdboy.autobot.feature.settings.ui.AppBar
import dev.wckdboy.autobot.feature.settings.ui.Caption
import dev.wckdboy.autobot.feature.settings.ui.Centered
import dev.wckdboy.autobot.feature.settings.ui.Choices
import dev.wckdboy.autobot.feature.settings.ui.Label
import dev.wckdboy.autobot.feature.settings.ui.Loading
import dev.wckdboy.autobot.feature.settings.ui.SectionCard
import dev.wckdboy.autobot.feature.settings.ui.SheetLabel
import dev.wckdboy.autobot.feature.settings.ui.Stat
import dev.wckdboy.autobot.feature.settings.ui.clearListItem
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
            confirmButton = {
                TextButton(
                    onClick = { actions.clearHistory(); confirmClear = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
    state.message?.let { m ->
        AlertDialog(onDismissRequest = actions::dismissMessage, text = { Text(m) }, confirmButton = { TextButton(onClick = actions::dismissMessage) { Text("OK") } })
    }

    Scaffold(
        topBar = {
            AppBar("Usage", onBack) {
                InfoButton("Usage", UsageWords.ABOUT)
            }
        },
    ) { padding ->
        Centered(Modifier.padding(padding)) { width ->
        LazyColumn(
            width.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (report == null) {
                item { Loading(caption = "Counting…") }
                return@LazyColumn
            }
            val period = report.periods.firstOrNull { it.key == periodKey } ?: report.periods.first()
            item {
                Choices(report.periods, period, { periodKey = it.key }, Modifier.fillMaxWidth()) { it.label }
            }
            item {
                // Wraps: "$6.00 + unknown", at a large font size, leaves no room for requests.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("tokens", UsageFormat.tokens(period.sums.tokens))
                    Stat(UsageWords.COST, period.sums.costLine)
                    Stat("requests", period.sums.requests.toString())
                }
                Spacer(Modifier.height(8.dp))
                val s = period.sums
                Caption("${UsageFormat.tokens(s.input)} in · ${UsageFormat.tokens(s.output)} out · ${UsageFormat.tokens(s.cacheRead)} cached")
                if (s.onDeviceTokens > 0) Caption("on this phone · ${UsageFormat.tokens(s.onDeviceTokens)} tokens · $0")
                if (s.unpricedTokens > 0) Caption("${UsageFormat.tokens(s.unpricedTokens)} tokens without a price", color = MaterialTheme.colorScheme.tertiary)
            }
            item {
                val series = report.series.firstOrNull { it.key == seriesKey } ?: report.series.first()
                SectionCard(title = labelCase("per ${series.key}")) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        report.series.forEach { s ->
                            FilterChip(selected = s.key == series.key, onClick = { seriesKey = s.key }, label = { Text(labelCase(s.label)) })
                        }
                    }
                    Bars(series)
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SheetLabel("by ${if (byModel) "model" else "provider"} · ${period.label}", Modifier.weight(1f))
                    AssistChip(onClick = { byModel = !byModel }, label = { Text(labelCase(if (byModel) "providers" else "models")) })
                }
            }
            val groups = if (byModel) period.models else period.providers
            if (groups.isEmpty()) item { Caption("nothing counted ${period.label}") }
            items(groups, key = { "${it.provider}|${it.model}" }) { GroupRow(it) }

            item { SheetLabel("subscription windows", Modifier.padding(top = 8.dp)) }
            items(report.windows, key = { it.id }) { w -> WindowRow(w, onEdit = { editingWindow = report.plan.windows.firstOrNull { it.id == w.id } }) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.providers.firstOrNull()?.let { first ->
                        AssistChip(
                            onClick = {
                                editingWindow = SubscriptionWindow(UUID.randomUUID().toString(), first.id, WindowPeriod.FIVE_HOURS, anchor = System.currentTimeMillis())
                            },
                            label = { Text(labelCase("+ window")) },
                        )
                    }
                    state.providers.filter { p -> p.openCodeGo && report.plan.windows.none { it.provider == p.id } }.forEach { p ->
                        AssistChip(onClick = { actions.addOpenCodeGoWindows(p.id) }, label = { Text(labelCase("+ ${p.name} 5 h / week / month")) })
                    }
                }
                if (state.providers.isEmpty()) Caption("add a cloud provider to track its plan")
            }

            item { SheetLabel(UsageWords.PRICES, Modifier.padding(top = 8.dp)) }
            if (report.prices.isEmpty()) item { Caption("models show up here once they have answered") }
            items(report.prices, key = { "${it.provider}|${it.model}" }) { row -> PriceRowView(row, onClick = { editingPrice = row }) }

            item {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                if (!report.empty) {
                    TextButton(
                        onClick = { confirmClear = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Clear history") }
                }
            }
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
        Caption(series.bars.firstOrNull()?.label.orEmpty(), Modifier.weight(1f))
        val max = series.bars.maxOfOrNull { it.tokens } ?: 0
        Caption("peak ${UsageFormat.tokens(max)}", Modifier.weight(1f))
        Caption(series.bars.lastOrNull()?.label.orEmpty())
    }
}

@Composable
private fun GroupRow(group: UsageGroup) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(group.model ?: group.name, style = AutobotTheme.styles.code, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (group.onDevice) Label("on device")
            Text(if (group.onDevice) "$0" else group.sums.costLine, style = MaterialTheme.typography.titleMedium)
        }
        Caption(
            listOfNotNull(
                group.name.takeIf { group.model != null },
                "${UsageFormat.tokens(group.sums.tokens)} tokens",
                "${group.sums.requests} req",
                group.sums.cacheRead.takeIf { it > 0 }?.let { "${UsageFormat.tokens(it)} cached" },
                group.sums.unreported.takeIf { it > 0 }?.let { "$it without usage report" },
            ).joinToString(" · "),
        )
    }
}

@Composable
private fun WindowRow(status: WindowStatus, onEdit: () -> Unit) {
    val accent = if (status.over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    SectionCard(
        modifier = Modifier.clickable(onClick = onEdit),
        title = labelCase("${status.providerName} · ${status.period.label}${if (status.rolling) " · rolling" else ""}"),
        trailing = { TextButton(onClick = onEdit) { Text("Edit") } },
    ) {
        status.fraction?.let { f -> LinearProgressIndicator(progress = { f.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = accent) }
        Text(status.line, style = MaterialTheme.typography.bodyMedium, color = if (status.over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        if (status.limit == null) Caption("no limit set · tap edit to enter your plan's limit")
    }
}

@Composable
private fun PriceRowView(row: PriceRow, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(row.model, style = AutobotTheme.styles.code, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            when (row.source) {
                PriceSource.DEFAULT -> Label("published")
                PriceSource.YOURS -> Label("yours", color = MaterialTheme.colorScheme.primaryContainer)
                PriceSource.NONE -> Label("unknown", color = MaterialTheme.colorScheme.tertiaryContainer)
            }
        }
        Caption(
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
                Caption("${row.providerName} · ${UsageWords.PRICE_UNIT}")
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
        confirmButton = { TextButton(onClick = { onSave(input, output, cacheRead, cacheWrite) }, enabled = valid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
                SheetLabel("provider")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    providers.forEach { p ->
                        FilterChip(selected = p.id == draft.provider, onClick = { draft = draft.copy(provider = p.id) }, label = { Text(p.name) })
                    }
                }
                SheetLabel("length")
                Choices(WindowPeriod.entries, draft.period, { draft = draft.copy(period = it) }, Modifier.fillMaxWidth()) { it.short }
                SheetLabel("counts")
                Choices(listOf(true, false), draft.rolling, { draft = draft.copy(rolling = it, anchor = draft.anchor ?: System.currentTimeMillis()) }, Modifier.fillMaxWidth()) { if (it) "Rolling" else "Fixed" }
                if (!draft.rolling) {
                    Caption("resets every ${draft.period.short} from ${dates.format(Date(draft.anchor ?: System.currentTimeMillis()))}")
                    AssistChip(onClick = { draft = draft.copy(anchor = System.currentTimeMillis()) }, label = { Text("It reset just now") })
                }
                SheetLabel("limit")
                Choices(LimitUnit.entries, draft.unit, { draft = draft.copy(unit = it) }, Modifier.fillMaxWidth()) { labelCase(it.label) }
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
                if (!isNew) {
                    TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete window") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft.copy(limit = parseLimit(limit, draft.unit))) }, enabled = limit.isBlank() || parseLimit(limit, draft.unit) != null) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
