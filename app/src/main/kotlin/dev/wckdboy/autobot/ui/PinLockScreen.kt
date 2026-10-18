package dev.wckdboy.autobot.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.component.PrimaryButton
import dev.wckdboy.autobot.core.designsystem.component.SectionLabel
import dev.wckdboy.autobot.core.designsystem.component.inkGround
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.shared.panic.LockStatus
import dev.wckdboy.autobot.shared.panic.PanicCopy
import dev.wckdboy.autobot.shared.panic.PinRules
import kotlinx.coroutines.delay

/**
 * The lock screen with an app PIN: dots, a printed keypad and UNLOCK. The PIN is submitted only
 * with UNLOCK (never on a length), so the length of either PIN shows nowhere. [status] carries
 * the warning before a wipe and the back-off; [message] the last result ("Wrong PIN").
 *
 * The real PIN and the duress PIN take the same path through this screen: [busy] while the PIN
 * is checked, then the paper prints away.
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

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().inkGround().padding(horizontal = 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            SectionLabel(PanicCopy.LOCKED)
            Text(PanicCopy.ENTER_PIN, style = MaterialTheme.typography.headlineSmall)
            Dots(pin.length)
            val line = when {
                waiting -> PanicCopy.tryAgainIn(waitUntil - now)
                message != null -> message
                else -> null
            }
            Text(
                line.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.heightIn(min = 16.dp),
            )
            if (status.warn && status.remaining != null) {
                Text(
                    PanicCopy.attemptsLeft(status.remaining!!),
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
            PrimaryButton(
                PanicCopy.UNLOCK,
                onClick = { if (pin.isNotEmpty()) onSubmit(pin) },
                enabled = enabled && pin.isNotEmpty(),
                busy = busy,
                modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Dots(count: Int) {
    // Fixed width: the row never grows with the PIN, so a glance does not count digits beyond what is typed.
    Row(
        Modifier.height(16.dp).semantics { contentDescription = "$count digits entered" },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count.coerceAtMost(PinRules.MAX_LENGTH)) {
            Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        }
        if (count == 0) Box(Modifier.size(10.dp).border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), CircleShape))
    }
}

@Composable
private fun Keypad(enabled: Boolean, biometrics: Boolean, onDigit: (Char) -> Unit, onDelete: () -> Unit, onBiometrics: () -> Unit) {
    val rows = listOf("123", "456", "789")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { d -> Key(d.toString(), enabled, onClick = { onDigit(d) }) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (biometrics) {
                Key("", true, description = "Use biometrics", onClick = onBiometrics) {
                    Text("BIO", style = AutobotTheme.styles.micro, color = MaterialTheme.colorScheme.primary)
                }
            } else {
                Spacer(Modifier.size(KEY))
            }
            Key("0", enabled, onClick = { onDigit('0') })
            Key("⌫", enabled, description = "Delete", onClick = onDelete)
        }
    }
}

private val KEY = 64.dp

@Composable
private fun Key(
    label: String,
    enabled: Boolean,
    description: String? = null,
    onClick: () -> Unit,
    content: (@Composable () -> Unit)? = null,
) {
    Box(
        Modifier
            .size(KEY)
            .border(BorderStroke(1.dp, if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant), MaterialTheme.shapes.extraSmall)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (content != null) {
            content()
        } else {
            Text(
                label,
                style = MaterialTheme.typography.headlineSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
