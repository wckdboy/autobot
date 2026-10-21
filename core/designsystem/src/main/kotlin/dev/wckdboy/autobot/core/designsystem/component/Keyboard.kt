package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/*
 * The keyboard pattern every screen with a field uses. The activity is edge to edge with
 * `adjustResize`, so nothing moves by itself: a screen pads its bottom stack with [imePadding]
 * once (never twice: the bottom bar hides while the keyboard is up, see [keyboardUp]).
 *
 * - A chat: transcript (weight 1) + [MessageComposer] under it, the column `imePadding()`ed.
 * - A form (API key, pairing code, bot settings): a scrolling column `imePadding()`ed, its
 *   primary button at the end of the content or in a bottom bar.
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
