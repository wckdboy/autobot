package dev.wckdboy.autobot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.R
import dev.wckdboy.autobot.core.designsystem.component.AutobotMark
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme

/** The lock screens' content stops growing at this width on a tablet or an unfolded phone. */
internal val LockMaxWidth = 360.dp

/**
 * Shown instead of all app content while locked (the system's biometrics or device credential).
 * Prompts automatically on first display.
 */
@Composable
fun LockScreen(onUnlock: () -> Unit, message: String?, modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) { onUnlock() }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AutobotMark(size = 40.dp)
            Text(
                stringResource(R.string.locked_title),
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.unlock_subtitle),
                Modifier.widthIn(max = LockMaxWidth),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            message?.let {
                Text(it, Modifier.widthIn(max = LockMaxWidth), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            Button(onClick = onUnlock, modifier = Modifier.widthIn(max = LockMaxWidth).fillMaxWidth()) { Text(stringResource(R.string.unlock)) }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LockScreenPreview() {
    AutobotTheme { LockScreen(onUnlock = {}, message = null) }
}
