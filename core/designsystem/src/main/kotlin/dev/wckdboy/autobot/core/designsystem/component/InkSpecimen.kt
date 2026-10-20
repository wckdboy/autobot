package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
 * The progress components on one sheet, for inspection in debug builds: the flat bar at fixed
 * progress and indeterminate, the loaders, an empty canvas and an empty state.
 */
@Composable
fun InkSpecimen() {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Specimen")
        for (p in listOf(0f, 0.25f, 0.5f, 1f)) {
            MicroLabel("Bar ${(p * 100).toInt()}%")
            InkProgressBar(p, Modifier.fillMaxWidth().height(2.dp), state = InkBarState.Idle)
        }
        MicroLabel("Bar indeterminate")
        InkProgressBar(null, Modifier.fillMaxWidth().height(2.dp))
        MicroLabel("Loaders")
        InkLoader()
        InkLoaderBlock()
        Segmented(listOf("txt2img", "img2img", "inpaint"), "img2img", {}, { it }, Modifier.fillMaxWidth())
        EmptyCanvas(Modifier.fillMaxWidth().height(120.dp), hint = "Nothing generated yet")
        EmptyState("No chats yet.", action = "New chat", onAction = {})
    }
}
