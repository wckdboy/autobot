package dev.wckdboy.autobot.feature.models.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wckdboy.autobot.core.designsystem.component.AutobotMark
import dev.wckdboy.autobot.core.designsystem.component.InfoButton
import dev.wckdboy.autobot.core.designsystem.component.NoPersonalizedLearning
import dev.wckdboy.autobot.core.designsystem.component.PrivateKeyboardOptions
import dev.wckdboy.autobot.core.designsystem.component.PromptKeyboardOptions
import dev.wckdboy.autobot.core.designsystem.component.Route
import dev.wckdboy.autobot.core.designsystem.component.ScreenTitle
import dev.wckdboy.autobot.core.designsystem.component.rememberBufferedText
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme

/*
 * Material 3 Expressive building blocks for this module's screens: the platform's own components
 * (TopAppBar, ListItem, Switch, Card, LoadingIndicator) in the theme's brand scheme, plus the
 * brand's one decoration, the purple pixel dot after a top-level screen's title.
 */

/** Reading width on a wide window: lists and forms stay a comfortable line long, centred. */
internal val ContentMaxWidth: Dp = 720.dp

/**
 * The screen's top app bar: its [title] (with the pixel dot when [mark], i.e. a top-level screen),
 * a back arrow when [onBack] is given, and [actions].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppBar(
    title: String,
    onBack: (() -> Unit)?,
    mark: Boolean = onBack == null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val style = MaterialTheme.typography.titleLarge
    TopAppBar(
        title = {
            ScreenTitle(
                title,
                style = style,
                color = MaterialTheme.colorScheme.onSurface,
                mark = mark,
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = style.fontSize, stepSize = 1.sp),
            )
        },
        navigationIcon = { if (onBack != null) BackIcon(onBack) },
        actions = actions,
        scrollBehavior = scrollBehavior,
    )
}

/** The top app bar's back arrow. */
@Composable
internal fun BackIcon(onBack: () -> Unit) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
}

/** Centres [content] at [ContentMaxWidth] on a wide window; full width on a phone. */
@Composable
internal fun Centered(modifier: Modifier = Modifier, content: @Composable BoxScope.(Modifier) -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        content(Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth())
    }
}

/** A list subheader: Material's section label, labelLarge in the primary colour. */
@Composable
internal fun Subheader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** A headline with its ⓘ right after it. */
@Composable
private fun Headline(title: String, info: String?, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f, fill = false), color = color)
        if (info != null) InfoButton(title, info)
    }
}

/**
 * A list item that opens something: [title], an optional [supporting] line, an optional [value]
 * carrying state ("On", "3", "Offline") and a chevron when it navigates.
 */
@Composable
internal fun NavItem(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    supporting: String? = null,
    info: String? = null,
    titleColor: Color = Color.Unspecified,
    chevron: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Headline(title, info, titleColor) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = leading,
        trailingContent = if (value == null && !chevron) null else {
            {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium)
                    if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
        },
        modifier = modifier.clickable(role = Role.Button, onClick = onClick),
    )
}

/** A list item with a trailing [Switch]; the whole item toggles. [state] is a short line under the title. */
@Composable
internal fun SwitchItem(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    state: String? = null,
    info: String? = null,
    enabled: Boolean = true,
) {
    ListItem(
        headlineContent = {
            Headline(title, info, if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant)
        },
        supportingContent = state?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    )
}

/** A label over a value, as a list item without an action ("Model" / "Qwen3 4B"). */
@Composable
internal fun InfoItem(label: String, value: String, modifier: Modifier = Modifier) {
    ListItem(
        overlineContent = { Text(label) },
        headlineContent = { Text(value) },
        modifier = modifier,
    )
}

/** A small tonal label: a state or a route ("local · cpu", "ready"). Not a control. */
@Composable
internal fun Label(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.secondaryContainer) {
    Surface(modifier, shape = MaterialTheme.shapes.small, color = color) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
    }
}

/** Where a run goes, as a tonal label: `● local · cpu`, `○ cloud · api`. */
@Composable
internal fun RouteLabel(route: Route, modifier: Modifier = Modifier) = Label("${route.glyph} ${route.label}", modifier)

internal enum class Tone { INFO, WARNING, ERROR }

/**
 * Why something is empty or not working and the one tap that fixes it, as a tonal card
 * ("Offline mode is on · Network", "Search failed · Retry").
 */
@Composable
internal fun NoticeCard(
    text: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.INFO,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val large = dev.wckdboy.autobot.core.designsystem.component.isLargeText()
    val (container, content) = when (tone) {
        Tone.INFO -> scheme.secondaryContainer to scheme.onSecondaryContainer
        Tone.WARNING -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        Tone.ERROR -> scheme.errorContainer to scheme.onErrorContainer
    }
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container, contentColor = content)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                when (tone) {
                    Tone.INFO -> Icons.Outlined.Info
                    Tone.WARNING -> Icons.Outlined.Warning
                    Tone.ERROR -> Icons.Outlined.Warning
                },
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(text, Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
            if (!large && action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
        }
        // A large font gives the sentence the card's width and puts the action under it.
        if (large && action != null && onAction != null) {
            TextButton(onClick = onAction, modifier = Modifier.align(Alignment.End).padding(end = 8.dp, bottom = 4.dp)) { Text(action) }
        }
    }
}

/** Nothing here yet: the pixel a, one plain sentence and at most one action. */
@Composable
internal fun EmptyMessage(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AutobotMark(size = 24.dp, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null && onAction != null) OutlinedButton(onClick = onAction) { Text(action) }
    }
}

/** Material's expressive loading indicator, centred, with an optional caption. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun Loading(modifier: Modifier = Modifier, caption: String? = null) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LoadingIndicator()
        if (caption != null) Text(caption, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** A button's label, or a small loading indicator in its place while [busy]. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BusyLabel(text: String, busy: Boolean) {
    if (busy) LoadingIndicator(Modifier.size(24.dp), color = androidx.compose.material3.LocalContentColor.current) else Text(text)
}

/** A titled group of content in a Material card (outlined, low emphasis). */
@Composable
internal fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.material3.OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null || trailing != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (title != null) Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    trailing?.invoke(this)
                }
            }
            content()
        }
    }
}

/**
 * Material's outlined text field that keeps the keyboard from learning what is typed. [mono] uses
 * the code face (URLs, ids, keys) and turns off capitalization and autocorrect. [value] may arrive
 * late from a view model: edits show at once and stale echoes are ignored.
 */
@Composable
internal fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supporting: String? = null,
    isError: Boolean = false,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    mono: Boolean = false,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions? = null,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailingIcon: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val (shown, change) = rememberBufferedText(value, onValueChange)
    NoPersonalizedLearning {
        OutlinedTextField(
            value = shown,
            onValueChange = change,
            modifier = modifier,
            enabled = enabled,
            textStyle = if (mono) AutobotTheme.styles.code else MaterialTheme.typography.bodyLarge,
            label = label?.let { { Text(it) } },
            placeholder = placeholder?.let { { Text(it) } },
            supportingText = supporting?.let { { Text(it) } },
            isError = isError,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else minLines,
            maxLines = if (singleLine) 1 else maxLines,
            keyboardOptions = keyboardOptions ?: if (mono) PromptKeyboardOptions else PrivateKeyboardOptions,
            keyboardActions = keyboardActions,
            trailingIcon = trailingIcon,
            visualTransformation = visualTransformation,
        )
    }
}

/** Default padding for the content of a scrolling screen. */
internal val ScreenPadding = PaddingValues(bottom = 24.dp)

/**
 * A single choice among a few options, as Material filter chips that wrap to a second line on a
 * narrow window or at a large font (a segmented row would cut its labels).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun <T> ChoiceChips(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier.fillMaxWidth().then(Modifier.selectableGroup()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            androidx.compose.material3.FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
            )
        }
    }
}

/**
 * A model row: [title], a one-line [subtitle], an optional [leading] picture and [extra] lines,
 * and its [actions] (labels and buttons) on the trailing side; at a large font the actions move
 * under the text, where they have the row's width.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ModelRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    extra: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val large = dev.wckdboy.autobot.core.designsystem.component.isLargeText()
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (subtitle.isNotEmpty()) Text(subtitle)
                extra?.invoke()
                if (large) {
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
                }
            }
        },
        leadingContent = leading,
        trailingContent = if (large) null else {
            { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions) }
        },
        modifier = modifier.then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
    )
}
