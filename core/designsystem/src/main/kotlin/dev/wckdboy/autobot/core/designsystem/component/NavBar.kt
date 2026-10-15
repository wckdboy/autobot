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
import java.util.Locale

/** One destination in [AutobotNavBar]. */
data class NavItem<K>(val key: K, val label: String, val icon: ImageVector? = null)

/**
 * Bottom navigation as in the print mockups: mono labels only, a red dot above the active one,
 * a hairline on top. The dot slides to a new tab (ease-out) and leaves a one-frame ghost.
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
    val dot = MaterialTheme.colorScheme.primary
    val dotY = remember { floatArrayOf(0f) }
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
                val r = 2.5.dp.toPx()
                if (slide.ghost) {
                    val g = Motion.registerOffset.dp.toPx()
                    drawCircle(dot, r, Offset((slide.ghostSlot + 0.5f) * slot + g, dotY[0] + g), alpha = 0.4f)
                }
                drawCircle(dot, r, Offset((slide.position + 0.5f) * slot, dotY[0]))
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
                // The dot's slot; the dot itself is drawn by the bar so it can slide.
                Box(Modifier.size(5.dp).onPlaced { dotY[0] = it.positionInParent().y + it.size.height / 2f })
                Spacer(Modifier.height(7.dp))
                Text(
                    item.label.uppercase(Locale.ROOT),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
