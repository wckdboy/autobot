package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/*
 * The app bar and the list pieces of the kit (see Standard.kt): Material's TopAppBar, ListItem,
 * Switch and OutlinedCard in the brand scheme.
 */

/**
 * The screen's top app bar: its [title] ([TitleWithDot]: the pixel dot when [mark], i.e. a
 * top-level screen), a back arrow when [onBack] is given, and [actions].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBar(
    title: String,
    onBack: (() -> Unit)?,
    mark: Boolean = onBack == null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { TitleWithDot(title, mark = mark) },
        navigationIcon = { if (onBack != null) BackIcon(onBack) },
        actions = actions,
        scrollBehavior = scrollBehavior,
    )
}

/** The top app bar's back arrow. */
@Composable
fun BackIcon(onBack: () -> Unit) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
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
fun NavListItem(
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
fun SwitchItem(
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

/** A label over a value, as a list item without an action ("Model" / "Qwen3 4B"), with an optional line under it. */
@Composable
fun InfoItem(label: String, value: String, modifier: Modifier = Modifier, supporting: String? = null) {
    ListItem(
        overlineContent = { Text(label) },
        headlineContent = { Text(value) },
        supportingContent = supporting?.let { { Text(it) } },
        modifier = modifier,
    )
}

/**
 * A row with its [actions] (labels and buttons): [title], a one-line [subtitle], an optional
 * [leading] picture and [extra] lines, the actions on the trailing side. At a large font the
 * actions move under the text, where they have the row's width.
 */
@Composable
fun ActionListItem(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    extra: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val large = isLargeText()
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

/** List item colours on a sheet or a card: no container of its own. */
@Composable
fun clearListItem(): ListItemColors = ListItemDefaults.colors(containerColor = Color.Transparent)

/** A titled group of content in a Material card (outlined, low emphasis). */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(modifier.fillMaxWidth()) {
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

/** A figure with its label above it ("Tokens" / "1.2M"). */
@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(labelCase(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}
