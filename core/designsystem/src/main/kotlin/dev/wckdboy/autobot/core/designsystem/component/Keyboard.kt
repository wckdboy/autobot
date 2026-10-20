package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/*
 * The keyboard pattern every screen with a field uses. The activity is edge to edge with
 * `adjustResize`, so nothing moves by itself: a screen pads its bottom stack with [imePadding]
 * once (never twice: the bottom bar hides while the keyboard is up, see [keyboardUp]).
 *
 * - A chat: transcript (weight 1) + [ComposerBar] under it, the column `imePadding()`ed.
 * - A form (API key, pairing code, bot settings): [KeyboardForm], whose fields scroll into view
 *   and whose primary button stays above the keyboard.
 * - While [keyboardUp], hide what is not the field or what it answers (chips, banners, tips,
 *   empty-state art): `if (Composer.shows(Part.BANNER, keyboardUp())) Banner(...)` (the rule
 *   lives in `shared/composer/Composer.kt`, the same on iOS).
 */

/** Whether the keyboard is on screen (it takes about a third of it). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun keyboardUp(): Boolean = LocalKeyboardUp.current ?: WindowInsets.isImeVisible

/** Tests and previews set whether the keyboard is up; null reads the window's insets. */
val LocalKeyboardUp = staticCompositionLocalOf<Boolean?> { null }

/**
 * The composer of every chat: [leading] buttons (attach, dictate), the field (grows to
 * [maxLines] lines, the shared `Composer.MAX_LINES`, then scrolls inside) and [trailing] (a
 * [SendButton] or [StopButton]).
 * [above] sits between the hairline and the field (attachments, slash commands, words heard).
 * It draws its own ground and hairline; the caller places it under the transcript and pads the
 * pair for the keyboard.
 *
 * The chat no longer uses it (its composer is a Material `TextField` with icon buttons); the node
 * agent screen still does.
 */
@Composable
fun ComposerBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxLines: Int = 5,
    above: @Composable ColumnScope.() -> Unit = {},
    leading: @Composable RowScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit,
) {
    Surface(modifier, color = MaterialTheme.colorScheme.background) {
        Column(Modifier.hairlineEdge(top = true, bottom = false)) {
            above()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                leading()
                ConsoleTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f).then(fieldModifier),
                    placeholder = placeholder,
                    maxLines = maxLines,
                    enabled = enabled,
                )
                trailing()
            }
        }
    }
}

/** The side of the composer's buttons, the field's one-line height. */
val ComposerButtonSize = 52.dp

/** Send (or Queue while a turn runs): filled primary when it can send. */
@Composable
fun SendButton(onClick: () -> Unit, enabled: Boolean, label: String = "Send") {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = Modifier.size(ComposerButtonSize),
    ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = label) }
}

/**
 * Stops the running turn: a primary square on a raised ground.
 *
 * Not used by any screen since the chat's Material 3 composer (a `FilledTonalIconButton` there);
 * kept until the riso kit is retired.
 */
@Composable
fun StopButton(onClick: () -> Unit, label: String = "Stop") {
    FilledIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.primary),
        shape = MaterialTheme.shapes.extraSmall,
        modifier = Modifier.size(ComposerButtonSize).semantics { contentDescription = label },
    ) { Box(Modifier.size(14.dp).background(MaterialTheme.colorScheme.primary)) }
}

/**
 * A form with fields (API key, pairing code, bot settings): [content] scrolls, and a focused
 * field is brought into view above the keyboard; [footer] (the primary button) stays pinned
 * above the keyboard, never under it. Pass the scaffold's padding as [modifier] padding; the
 * form pads for the keyboard and the navigation bar itself.
 */
@Composable
fun KeyboardForm(
    modifier: Modifier = Modifier,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
        if (footer != null) {
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).hairlineEdge(top = true, bottom = false)
                    .windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = footer,
            )
        }
    }
}
