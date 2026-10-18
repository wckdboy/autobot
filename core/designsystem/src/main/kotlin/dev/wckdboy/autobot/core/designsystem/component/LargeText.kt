package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity

/**
 * The user set a large system font (150 % or more). Crowded rows stack and one-line labels may
 * wrap instead of losing their end; at the default size nothing changes.
 */
@Composable
@ReadOnlyComposable
fun isLargeText(): Boolean = LocalDensity.current.fontScale >= LARGE_TEXT_SCALE

/** One line at the default size; [large] lines when the user set a large font. */
@Composable
@ReadOnlyComposable
fun linesFor(default: Int, large: Int): Int = if (isLargeText()) large else default

private const val LARGE_TEXT_SCALE = 1.5f
