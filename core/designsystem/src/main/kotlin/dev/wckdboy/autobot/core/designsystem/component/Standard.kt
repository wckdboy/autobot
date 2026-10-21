package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.AssistChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The app's kit: Material 3 Expressive's own components, each plus at most the brand's two marks
 * (the pixel a and the purple pixel dot after a top-level title). Colours come from
 * MaterialTheme.colorScheme (the brand purple's scheme) and type from its typography roles.
 *
 * - Standard.kt: titles, labels, chips, widths, the long press.
 * - StandardLists.kt: the app bar, list items, cards, figures.
 * - StandardFeedback.kt: notices, empty states, loading, progress.
 * - StandardInputs.kt: fields, search, single choices.
 * - StandardControls.kt: sliders, steppers, dropdowns.
 * - StandardComposer.kt: the chat composer.
 */

/** Reading content (a transcript, a list of runs) stops growing at this width on a wide window and centres. */
val ReadingMaxWidth: Dp = 840.dp

/** Caps the width at [ReadingMaxWidth]; the parent centres it (`Alignment.CenterHorizontally` / `TopCenter`). */
fun Modifier.readingWidth(): Modifier = this.widthIn(max = ReadingMaxWidth)

/** Lists and forms (settings, models) stop growing at this width on a wide window: a comfortable line long. */
val FormMaxWidth: Dp = 720.dp

/** Centres [content] at [maxWidth] on a wide window; full width on a phone. */
@Composable
fun Centered(modifier: Modifier = Modifier, maxWidth: Dp = FormMaxWidth, content: @Composable BoxScope.(Modifier) -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        content(Modifier.widthIn(max = maxWidth).fillMaxWidth())
    }
}

/** Default padding for the content of a scrolling screen. */
val ScreenPadding = PaddingValues(bottom = 24.dp)

/**
 * A top app bar's title with the brand's purple pixel dot after it, for top-level screens
 * (`TopAppBar(title = { TitleWithDot("Chats") })`). It takes the bar's text style and colour, is
 * read as the title alone and as a heading, and steps its size down before it would truncate.
 * Pass [mark] = false on a pushed screen (one with a back arrow).
 */
@Composable
fun TitleWithDot(text: String, modifier: Modifier = Modifier, mark: Boolean = true) {
    val style = LocalTextStyle.current
    ScreenTitle(
        text,
        modifier = modifier,
        style = style,
        color = LocalContentColor.current,
        mark = mark,
        maxLines = 1,
        autoSize = if (style.fontSize.isSp) TextAutoSize.StepBased(minFontSize = TITLE_MIN, maxFontSize = style.fontSize, stepSize = 1.sp) else null,
    )
}

private val TITLE_MIN = 14.sp

/**
 * Where a run goes ([Route]: `● local · npu`, `○ cloud · api`) as a standard chip: an
 * [AssistChip] when tapping it opens something (the model picker), else a [TonalLabel].
 */
@Composable
fun RouteChip(route: Route, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val text = "${route.glyph} ${route.label}"
    if (onClick != null) {
        AssistChip(onClick = onClick, label = { Text(text) }, modifier = modifier)
    } else {
        TonalLabel(text, modifier)
    }
}

/**
 * A state, not an action (`2 live`, `interrupted`, `signed ✓`, `ready`): labelMedium on a small
 * tonal surface. Use a chip instead when it can be tapped. [contentColor] follows the container
 * (a `…Container` colour gets its `on…Container`).
 */
@Composable
fun TonalLabel(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = contentColorFor(containerColor),
) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.small, color = containerColor, contentColor = contentColor) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
    }
}

/** A caption in sentence case ([labelCase]): a hint or a state under a field or a row. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(labelCase(text), modifier, style = MaterialTheme.typography.bodySmall, color = color)
}

/** A list's section header: Material's subheader, labelLarge in the primary colour, read as a heading. */
@Composable
fun Subheader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** A bottom sheet's or a card's small heading, in sentence case, in the primary colour. */
@Composable
fun SheetLabel(text: String, modifier: Modifier = Modifier) {
    Text(labelCase(text), modifier.semantics { heading() }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

/**
 * A long press for a component that only has a tap (a [androidx.compose.material3.FilterChip]):
 * held past the long-press timeout it runs [onLongPress] and the tap is swallowed; TalkBack gets
 * it as a long-click action named [label]. A shorter press is left to the component.
 */
fun Modifier.longPressAction(label: String, onLongPress: () -> Unit): Modifier = this
    .semantics {
        onLongClick(label) {
            onLongPress()
            true
        }
    }
    .pointerInput(onLongPress) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.all { !it.pressed }) break
                }
                true
            }
            if (released == null) {
                onLongPress()
                // The rest of the gesture is ours: the component must not take it as a tap.
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                    if (event.changes.all { !it.pressed }) break
                }
            }
        }
    }
