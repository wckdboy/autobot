package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The keyboard pattern every screen with a field uses. The activity is edge to edge with
 * `adjustResize`, so nothing moves by itself: a screen pads its bottom stack with [imePadding]
 * once (never twice: the bottom bar hides while the keyboard is up, see [keyboardUp]).
 *
 * - A chat: transcript (weight 1) + [MessageComposer] under it, the column `imePadding()`ed.
 * - A form (API key, pairing code, bot settings): [KeyboardForm], whose fields scroll and whose
 *   primary button stays above the keyboard; or a scrolling column `imePadding()`ed, its primary
 *   button at the end of the content or in a bottom bar.
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
 * A form with fields (API key, pairing code): [content] scrolls, and [footer] (the primary button
 * and what it answers, such as a failure) stays pinned above the keyboard under a divider, never
 * under it. The form pads for the keyboard and the navigation bar itself; pass a scaffold's
 * padding as [modifier] padding.
 */
@Composable
fun KeyboardForm(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 16.dp,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = horizontalPadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
        if (footer != null) {
            HorizontalDivider()
            Column(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = horizontalPadding, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = footer,
            )
        }
    }
}
