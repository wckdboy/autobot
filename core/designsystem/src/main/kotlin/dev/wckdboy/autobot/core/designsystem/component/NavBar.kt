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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.util.Locale

/** One destination in [AutobotNavBar]. */
data class NavItem<K>(val key: K, val label: String, val icon: ImageVector? = null)

/**
 * Bottom navigation as in the print mockups: mono labels only, a red dot above the active one,
 * a hairline on top.
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
            .background(MaterialTheme.colorScheme.background)
            .hairlineEdge(top = true, bottom = false)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(56.dp)
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
                Box(Modifier.size(5.dp).background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape))
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
