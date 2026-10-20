package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.motion.rememberInkSlide
import dev.wckdboy.autobot.core.designsystem.theme.Motion
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import dev.wckdboy.autobot.core.designsystem.theme.AutobotColors
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme

/** The active tab's bar width. */
private val TabBarWidth = 20.dp

/** One destination in [AutobotNavBar]. */
data class NavItem<K>(val key: K, val label: String, val icon: ImageVector? = null)

/**
 * Bottom navigation: sentence-case mono labels, a hairline on top, and a 2 dp purple bar under
 * the active label (onSurface; the rest onSurfaceVariant). The bar slides to a new tab (ease-out).
 */
@Composable
fun <K> AutobotNavBar(
    items: List<NavItem<K>>,
    selected: K?,
    onSelect: (K) -> Unit,
    modifier: Modifier = Modifier,
) {
    val index = items.indexOfFirst { it.key == selected }
    val slide = rememberInkSlide(index.coerceAtLeast(0))
    val bar = AutobotColors.Purple
    val barY = remember { floatArrayOf(0f) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .hairlineEdge(top = true, bottom = false)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(56.dp)
            .drawBehind {
                if (index < 0 || items.isEmpty()) return@drawBehind
                val slot = size.width / items.size
                val w = TabBarWidth.toPx()
                val h = SelectionBarHeight.toPx()
                drawRect(bar, Offset((slide.position + 0.5f) * slot - w / 2f, barY[0]), Size(w, h))
            }
            .selectableGroup(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        items.forEach { item ->
            val active = item.key == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .selectable(selected = active, role = Role.Tab, onClick = { onSelect(item.key) }),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // Balances the bar below, so the label sits on the bar's centre line.
                Spacer(Modifier.height(SelectionBarHeight + 6.dp))
                Text(
                    item.label,
                    style = AutobotTheme.styles.tab.copy(fontWeight = if (active) FontWeight.Medium else FontWeight.Normal),
                    color = if (active) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.height(6.dp))
                // The bar's slot; the bar itself is drawn by the row so it can slide.
                Box(Modifier.size(TabBarWidth, SelectionBarHeight).onPlaced { barY[0] = it.positionInParent().y })
            }
        }
    }
}
