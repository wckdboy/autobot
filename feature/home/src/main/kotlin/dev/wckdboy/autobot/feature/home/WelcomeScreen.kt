package dev.wckdboy.autobot.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.component.AutobotMark
import dev.wckdboy.autobot.core.designsystem.component.FormMaxWidth
import dev.wckdboy.autobot.core.designsystem.component.Wordmark

/**
 * First run: the large pixel a, the wordmark, one sentence and one button, with two quieter ways
 * in for someone who brings a PC or an API key (as on iOS). On a wide window the column keeps a
 * form's width, centred.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WelcomeScreen(onSetUp: () -> Unit, onPairPc: () -> Unit = {}, onAddApiKey: () -> Unit = {}) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            // Scrolls when a large font makes the column taller than the screen.
            Column(
                Modifier
                    .widthIn(max = FormMaxWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(24.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Spacer(Modifier.height(1.dp))
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    AutobotMark(size = 72.dp)
                    Wordmark()
                    Text(
                        "Private AI on your phone.",
                        Modifier.semantics { heading() },
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSetUp, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text("Set up this phone", style = MaterialTheme.typography.titleMedium)
                    }
                    // Side by side; one under the other when a large font leaves no room.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                        TextButton(onClick = onPairPc) { Text("Pair a PC") }
                        TextButton(onClick = onAddApiKey) { Text("Add an API key") }
                    }
                }
            }
        }
    }
}
