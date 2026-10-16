package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The ink components on one sheet, for pixel inspection in debug builds: the dot bar at fixed
 * progress and indeterminate, the loaders, a living card and the paper ground.
 */
@Composable
fun InkSpecimen() {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (p in listOf(0f, 0.25f, 0.5f, 1f)) {
            MicroLabel("bar ${(p * 100).toInt()}%")
            InkProgressBar(p, Modifier.fillMaxWidth().height(6.dp), state = InkBarState.Idle)
        }
        MicroLabel("bar indeterminate")
        InkProgressBar(null, Modifier.fillMaxWidth().height(6.dp))
        MicroLabel("loaders")
        InkLoader()
        InkLoaderBlock()
        MicroLabel("living card")
        Box(Modifier.fillMaxWidth().height(120.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh).inkCard())
        MicroLabel("paper ground")
        Box(Modifier.fillMaxWidth().height(160.dp).inkGround())
    }
}
