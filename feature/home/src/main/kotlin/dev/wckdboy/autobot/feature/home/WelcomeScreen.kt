package dev.wckdboy.autobot.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.component.AutobotMark
import dev.wckdboy.autobot.core.designsystem.component.HalftoneField
import dev.wckdboy.autobot.core.designsystem.component.PrimaryButton
import dev.wckdboy.autobot.core.designsystem.component.Route
import dev.wckdboy.autobot.core.designsystem.component.RouteTag
import dev.wckdboy.autobot.core.designsystem.component.SectionLabel

/** First-run welcome (mockup 01): halftone hero, the promise, three routes, set up. */
@Composable
fun WelcomeScreen(onSetUp: () -> Unit, onPairPc: () -> Unit, onAddApiKey: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        HalftoneField(Modifier.fillMaxWidth().height(380.dp), cell = 10.dp)
        Box(
            Modifier.fillMaxWidth().height(380.dp).background(
                Brush.verticalGradient(0.5f to Color.Transparent, 1f to MaterialTheme.colorScheme.background),
            ),
        )
        Row(Modifier.statusBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AutobotMark()
            Text("  AUTOBOT", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        }
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionLabel("01 — welcome")
            Text("Local or\ncloud? Both.", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onBackground)
            Text(
                "Small models run on this phone. Big ones run on your own machine. The cloud is optional, and you hold the keys.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RouteTag(Route.LOCAL_CPU)
                RouteTag(Route.PC_LAN)
                RouteTag(Route.CLOUD_API)
            }
            Spacer(Modifier.height(6.dp))
            PrimaryButton("Set up this phone", onSetUp, Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = onPairPc) { Text("PAIR A PC", style = MaterialTheme.typography.labelMedium) }
                TextButton(onClick = onAddApiKey) { Text("ADD AN API KEY", style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}
