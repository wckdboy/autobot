package dev.wckdboy.autobot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme

/**
 * Shown instead of the app when the encrypted database cannot be opened (its Keystore key was
 * lost, or the file is damaged), rather than crashing on every screen. Resetting starts an empty
 * database; downloaded models and the settings are kept.
 */
@Composable
fun DatabaseErrorScreen(reason: String, resetting: Boolean, onReset: () -> Unit, modifier: Modifier = Modifier) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
            Text("Can't open your data", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                "Autobot's encrypted database can't be read on this phone. $reason.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                "Resetting starts over with an empty database: chats, providers and the gallery list are lost. Downloaded models and settings stay.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = { confirm = true }, enabled = !resetting) { Text(if (resetting) "Resetting…" else "Reset database") }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Reset the database?") },
            text = { Text("Everything stored in it is deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onReset()
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DatabaseErrorScreenPreview() {
    AutobotTheme { DatabaseErrorScreen("Its key is no longer in this phone's keystore", resetting = false, onReset = {}) }
}
