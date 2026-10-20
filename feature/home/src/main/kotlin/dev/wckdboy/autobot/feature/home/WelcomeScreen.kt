package dev.wckdboy.autobot.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.component.ActionText
import dev.wckdboy.autobot.core.designsystem.component.AutobotMark
import dev.wckdboy.autobot.core.designsystem.component.PrimaryButton
import dev.wckdboy.autobot.core.designsystem.component.Wordmark

/**
 * First run: the large pixel a, the wordmark, one sentence and one button, with two quiet ways
 * in for someone who brings a PC or an API key (as on iOS).
 */
@Composable
fun WelcomeScreen(onSetUp: () -> Unit, onPairPc: () -> Unit = {}, onAddApiKey: () -> Unit = {}) {
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Scrolls when a large font makes the column taller than the screen.
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Spacer(Modifier.height(1.dp))
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                AutobotMark(size = 72.dp)
                Wordmark()
                Text(
                    "Private AI on your phone.",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PrimaryButton("Set up this phone", onSetUp, Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onPairPc) { ActionText("Pair a PC", MaterialTheme.colorScheme.onSurfaceVariant) }
                    TextButton(onClick = onAddApiKey) { ActionText("Add an API key", MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}
