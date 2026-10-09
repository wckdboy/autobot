package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** One destination in [AutobotNavBar]. */
data class NavItem<K>(val key: K, val label: String, val icon: ImageVector)

/**
 * Bottom navigation: hairline on top, mono micro-labels, and a short acid bar above the active
 * destination instead of Material's pill indicator.
 */
@Composable
fun <K> AutobotNavBar(
    items: List<NavItem<K>>,
    selected: K?,
    onSelect: (K) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .hairlineEdge(top = true, bottom = false)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(58.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        items.forEach { item ->
            val active = item.key == selected
            val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Column(
                modifier = Modifier
                    .weight(1f)
                    .selectable(selected = active, role = Role.Tab, onClick = { onSelect(item.key) }),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(width = 22.dp, height = 2.dp)
                        .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent),
                )
                Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 9.dp).size(20.dp))
                MicroLabel(item.label, Modifier.padding(top = 5.dp), color = tint)
            }
        }
    }
}
