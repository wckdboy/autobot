package dev.wckdboy.autobot.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.component.AutobotMark
import dev.wckdboy.autobot.core.designsystem.component.BusyLabel
import dev.wckdboy.autobot.core.designsystem.component.isLargeText
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.shared.panic.LockStatus
import dev.wckdboy.autobot.shared.panic.PanicCopy
import dev.wckdboy.autobot.shared.panic.PinRules
import kotlinx.coroutines.delay

/**
 * The lock screen with an app PIN: the entered digits as dots, a keypad of tonal round keys and
 * Unlock. The PIN is submitted only with Unlock (never on a length), so the length of either PIN
 * shows nowhere. [status] carries the warning before a wipe and the back-off; [message] the last
 * result ("Wrong PIN").
 *
 * The real PIN and the duress PIN take the same path through this screen: [busy] while the PIN
 * is checked, then the app opens (on the real or the decoy data) the same way.
 */
@Composable
fun PinLockScreen(
    status: LockStatus,
    message: String?,
    busy: Boolean,
    biometrics: Boolean,
    onSubmit: (String) -> Unit,
    onBiometrics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pin by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val waitUntil = remember(status) { System.currentTimeMillis() + status.waitMillis }
    val waiting = waitUntil > now
    LaunchedEffect(waitUntil) {
        while (System.currentTimeMillis() < waitUntil) {
            now = System.currentTimeMillis()
            delay(250)
        }
        now = System.currentTimeMillis()
    }
    // A new result clears what was typed.
    LaunchedEffect(status, message) { pin = "" }
    LaunchedEffect(biometrics) { if (biometrics) onBiometrics() }
    val enabled = !busy && !waiting

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AutobotMark(size = 32.dp)
            Text(PanicCopy.LOCKED, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(PanicCopy.ENTER_PIN, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Dots(pin.length)
            val line = when {
                waiting -> PanicCopy.tryAgainIn(waitUntil - now)
                message != null -> message
                else -> null
            }
            Text(
                line.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = LockMaxWidth).heightIn(min = 20.dp),
            )
            if (status.warn && status.remaining != null) {
                Text(
                    PanicCopy.attemptsLeft(status.remaining!!),
                    Modifier.widthIn(max = LockMaxWidth),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            Keypad(
                enabled = enabled,
                biometrics = biometrics && !busy,
                onDigit = { if (pin.length < PinRules.MAX_LENGTH) pin += it },
                onDelete = { pin = pin.dropLast(1) },
                onBiometrics = onBiometrics,
            )
            Button(
                onClick = { if (!busy && pin.isNotEmpty()) onSubmit(pin) },
                enabled = enabled && pin.isNotEmpty(),
                modifier = Modifier.padding(top = 8.dp).widthIn(max = LockMaxWidth).fillMaxWidth().heightIn(min = 56.dp),
            ) { BusyLabel(PanicCopy.UNLOCK, busy) }
        }
    }
}

@Composable
private fun Dots(count: Int) {
    // Never wider than the digits typed: a glance does not tell how long the PIN is.
    Row(
        Modifier.heightIn(min = 16.dp).semantics { contentDescription = "$count digits entered" },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count.coerceAtMost(PinRules.MAX_LENGTH)) {
            Box(Modifier.size(12.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        }
        if (count == 0) Box(Modifier.size(12.dp).border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), CircleShape))
    }
}

@Composable
private fun Keypad(enabled: Boolean, biometrics: Boolean, onDigit: (Char) -> Unit, onDelete: () -> Unit, onBiometrics: () -> Unit) {
    // A large font gets larger keys, so the digits keep their room.
    val key = if (isLargeText()) 80.dp else KEY
    val rows = listOf("123", "456", "789")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { row.forEach { d -> Key(d.toString(), key, enabled, onClick = { onDigit(d) }) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (biometrics) {
                TextButton(onClick = onBiometrics, modifier = Modifier.size(key).semantics { contentDescription = "Use biometrics" }) {
                    Text("Bio", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            } else {
                Spacer(Modifier.size(key))
            }
            Key("0", key, enabled, onClick = { onDigit('0') })
            TextButton(onClick = onDelete, enabled = enabled, modifier = Modifier.size(key).semantics { contentDescription = "Delete" }) {
                Text("⌫", style = MaterialTheme.typography.headlineSmall, maxLines = 1)
            }
        }
    }
}

private val KEY = 72.dp

/** A digit: a round tonal button. */
@Composable
private fun Key(label: String, size: androidx.compose.ui.unit.Dp, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        modifier = Modifier.size(size),
    ) {
        Text(label, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0C0F)
@Composable
private fun PinLockScreenPreview() {
    AutobotTheme {
        PinLockScreen(
            status = LockStatus(failures = 8, remaining = 2, warn = true, waitMillis = 0),
            message = PanicCopy.WRONG_PIN,
            busy = false,
            biometrics = false,
            onSubmit = {},
            onBiometrics = {},
        )
    }
}
