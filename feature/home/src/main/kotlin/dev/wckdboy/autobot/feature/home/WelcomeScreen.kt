package dev.wckdboy.autobot.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import dev.wckdboy.autobot.core.designsystem.component.Wordmark
import dev.wckdboy.autobot.core.designsystem.component.QuietField
import dev.wckdboy.autobot.core.designsystem.component.PrimaryButton
import dev.wckdboy.autobot.core.designsystem.component.Route
import dev.wckdboy.autobot.core.designsystem.component.RouteTag
import dev.wckdboy.autobot.core.designsystem.component.isLargeText
import dev.wckdboy.autobot.core.designsystem.component.SectionLabel
import dev.wckdboy.autobot.core.designsystem.component.ambientTouch
import dev.wckdboy.autobot.core.designsystem.component.rememberInkAmbient

/** First-run welcome (mockup 01): halftone hero, the promise, three routes, set up. */
@Composable
fun WelcomeScreen(onSetUp: () -> Unit, onPairPc: () -> Unit, onAddApiKey: () -> Unit) {
    // The hero is living paper: it drifts at the ambient pace until left alone for a minute.
    val ambient = rememberInkAmbient()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).ambientTouch(ambient)) {
        val fade = Brush.verticalGradient(0.5f to Color.Transparent, 1f to MaterialTheme.colorScheme.background)
        // A large font fills the screen with the promise: the hero becomes a band above it that
        // scrolls with it, so no text sits on the densest dots.
        val large = isLargeText()
        if (!large) {
            QuietField(Modifier.fillMaxWidth().height(380.dp), cell = 10.dp, ambient = ambient)
            Box(Modifier.fillMaxWidth().height(380.dp).background(fade))
        }
        // Scrolls when a large font makes the promise taller than the screen; otherwise it sits at the bottom as before.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(Modifier.fillMaxWidth().then(if (large) Modifier.height(200.dp) else Modifier)) {
                    if (large) {
                        QuietField(Modifier.matchParentSize(), cell = 10.dp, ambient = ambient)
                        Box(Modifier.matchParentSize().background(fade))
                    }
                    Wordmark(modifier = Modifier.padding(16.dp))
                }
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    SectionLabel("welcome")
                    Text("Local or cloud? Both.", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        "Small models run on this phone. Big ones run on your own machine. The cloud is optional, and you hold the keys.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // The three routes wrap to a second line when a large font leaves no room for them side by side.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        RouteTag(Route.LOCAL_CPU)
                        RouteTag(Route.PC_LAN)
                        RouteTag(Route.CLOUD_API)
                    }
                    Spacer(Modifier.height(6.dp))
                    PrimaryButton("Set up this phone", onSetUp, Modifier.fillMaxWidth())
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = onPairPc) { Text("Pair a PC", style = MaterialTheme.typography.labelMedium) }
                        TextButton(onClick = onAddApiKey) { Text("Add an API key", style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
        }
    }
}
