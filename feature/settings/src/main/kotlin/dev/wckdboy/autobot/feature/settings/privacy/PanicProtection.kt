package dev.wckdboy.autobot.feature.settings.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.component.NavRow
import dev.wckdboy.autobot.core.designsystem.component.NoPersonalizedLearning
import dev.wckdboy.autobot.core.designsystem.component.SwitchRow
import dev.wckdboy.autobot.shared.panic.LockRecord
import dev.wckdboy.autobot.shared.panic.PanicCopy
import dev.wckdboy.autobot.shared.panic.PinRules
import dev.wckdboy.autobot.shared.panic.UnlockPolicy
import java.util.Locale

/** The panic-protection part of a [LockRecord], as Settings shows it. No verifier leaves the lock. */
@Immutable
data class PanicUiState(
    val hasPin: Boolean = false,
    val biometrics: Boolean = true,
    val wipeAfter: Int = 0,
    val duress: Boolean = false,
    val wipeModels: Boolean = false,
) {
    companion object {
        fun of(record: LockRecord) = PanicUiState(
            hasPin = record.hasPin,
            biometrics = record.biometrics,
            wipeAfter = record.wipeAfter,
            duress = record.duress != null,
            wipeModels = record.wipeModels,
        )
    }
}

/** Which panic dialog Settings shows. */
enum class PanicDialog { PIN, WIPE, DURESS }

/** What the panic dialogs ask of the lock; each answer is null on success or the reason it failed. */
interface PanicActions {
    fun savePin(current: String?, pin: String, confirm: String, done: (String?) -> Unit)
    fun removePin(current: String, done: (String?) -> Unit)
    fun setWipeAfter(attempts: Int, current: String?, done: (String?) -> Unit)
    fun setDuress(current: String, duress: String, confirm: String, done: (String?) -> Unit)
    fun clearDuress()
}

/**
 * The rows under App lock: App PIN, then (with a PIN) biometrics, the wipe after failed unlocks,
 * the duress PIN and whether models go too. Each feature says what it destroys and that it is final.
 */
@Composable
fun PanicRows(state: PanicUiState, onOpen: (PanicDialog) -> Unit, onBiometrics: (Boolean) -> Unit, onWipeModels: (Boolean) -> Unit) {
    NavRow(PanicCopy.PIN_TITLE, { onOpen(PanicDialog.PIN) }, value = PanicCopy.onOff(state.hasPin), info = PanicCopy.PIN_INFO)
    if (!state.hasPin) return
    SwitchRow(PanicCopy.BIOMETRICS_TITLE, state.biometrics, onBiometrics, info = PanicCopy.BIOMETRICS_INFO)
    NavRow(
        PanicCopy.WIPE_TITLE,
        { onOpen(PanicDialog.WIPE) },
        value = PanicCopy.wipeValue(state.wipeAfter),
        info = "${PanicCopy.WIPE_INFO} ${PanicCopy.CANNOT_UNDO}",
    )
    NavRow(
        PanicCopy.DURESS_TITLE,
        { onOpen(PanicDialog.DURESS) },
        value = PanicCopy.onOff(state.duress),
        info = "${PanicCopy.DURESS_INFO} ${PanicCopy.CANNOT_UNDO}",
    )
    if (state.wipeAfter > 0 || state.duress) {
        SwitchRow(PanicCopy.MODELS_TITLE, state.wipeModels, onWipeModels, info = PanicCopy.MODELS_INFO)
    }
}

/** Shows [dialog] for [state]; [onDismiss] closes it (also after a change went through). */
@Composable
fun PanicDialogs(dialog: PanicDialog?, state: PanicUiState, actions: PanicActions, onDismiss: () -> Unit) {
    when (dialog) {
        PanicDialog.PIN -> PinDialog(state.hasPin, actions, onDismiss)
        PanicDialog.WIPE -> WipeDialog(state, actions, onDismiss)
        PanicDialog.DURESS -> DuressDialog(state, actions, onDismiss)
        null -> Unit
    }
}

@Composable
private fun PinDialog(hasPin: Boolean, actions: PanicActions, onDismiss: () -> Unit) {
    var removing by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val done: (String?) -> Unit = { problem ->
        busy = false
        if (problem == null) onDismiss() else error = problem
    }
    val title = when {
        removing -> PanicCopy.REMOVE_PIN
        hasPin -> PanicCopy.CHANGE_PIN
        else -> PanicCopy.SET_PIN
    }
    PanicAlert(
        title = title,
        confirm = title,
        destructive = removing,
        enabled = !busy && if (removing) current.isNotEmpty() else (pin.isNotEmpty() && confirm.isNotEmpty() && (!hasPin || current.isNotEmpty())),
        onConfirm = {
            busy = true
            error = null
            if (removing) actions.removePin(current, done) else actions.savePin(current.takeIf { hasPin }, pin, confirm, done)
        },
        onDismiss = onDismiss,
    ) {
        if (removing) Text(PanicCopy.REMOVE_PIN_BODY)
        if (hasPin) PinField(PanicCopy.CURRENT_PIN, current) { current = it }
        if (!removing) {
            PinField(PanicCopy.NEW_PIN, pin) { pin = it }
            PinField(PanicCopy.CONFIRM_PIN, confirm) { confirm = it }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (hasPin && !removing) {
            TextButton(onClick = {
                removing = true
                error = null
            }) { Text(PanicCopy.REMOVE_PIN.uppercase(Locale.ROOT), color = MaterialTheme.colorScheme.error) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WipeDialog(state: PanicUiState, actions: PanicActions, onDismiss: () -> Unit) {
    var chosen by remember { mutableIntStateOf(if (state.wipeAfter > 0) state.wipeAfter else UnlockPolicy.WipeChoices[1]) }
    var current by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val turningOff = chosen == 0
    val done: (String?) -> Unit = { problem ->
        busy = false
        if (problem == null) onDismiss() else error = problem
    }
    PanicAlert(
        title = if (turningOff) PanicCopy.WIPE_TITLE else PanicCopy.wipeConfirmTitle(chosen),
        confirm = if (turningOff) PanicCopy.TURN_OFF else PanicCopy.TURN_ON,
        destructive = !turningOff,
        enabled = !busy && chosen != state.wipeAfter && (turningOff || current.isNotEmpty()),
        onConfirm = {
            busy = true
            error = null
            actions.setWipeAfter(chosen, current.takeUnless { turningOff }, done)
        },
        onDismiss = onDismiss,
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf(0) + UnlockPolicy.WipeChoices).forEach { n ->
                FilterChip(selected = chosen == n, onClick = { chosen = n }, label = { Text(if (n == 0) PanicCopy.wipeValue(0) else "$n") })
            }
        }
        if (!turningOff) {
            Text(PanicCopy.wipeConfirmBody(chosen, state.wipeModels))
            PinField(PanicCopy.CURRENT_PIN, current) { current = it }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun DuressDialog(state: PanicUiState, actions: PanicActions, onDismiss: () -> Unit) {
    if (state.duress) {
        PanicAlert(
            title = PanicCopy.DURESS_TITLE,
            confirm = PanicCopy.TURN_OFF,
            destructive = false,
            enabled = true,
            onConfirm = {
                actions.clearDuress()
                onDismiss()
            },
            onDismiss = onDismiss,
        ) { Text(PanicCopy.DURESS_INFO) }
        return
    }
    var current by remember { mutableStateOf("") }
    var duress by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    PanicAlert(
        title = PanicCopy.DURESS_CONFIRM_TITLE,
        confirm = PanicCopy.TURN_ON,
        destructive = true,
        enabled = !busy && current.isNotEmpty() && duress.isNotEmpty() && confirm.isNotEmpty(),
        onConfirm = {
            busy = true
            error = null
            actions.setDuress(current, duress, confirm) { problem ->
                busy = false
                if (problem == null) onDismiss() else error = problem
            }
        },
        onDismiss = onDismiss,
    ) {
        Text(PanicCopy.duressConfirmBody(state.wipeModels))
        PinField(PanicCopy.CURRENT_PIN, current) { current = it }
        PinField(PanicCopy.DURESS_PIN, duress) { duress = it }
        PinField(PanicCopy.CONFIRM_DURESS_PIN, confirm) { confirm = it }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun PanicAlert(
    title: String,
    confirm: String,
    destructive: Boolean,
    enabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = enabled) {
                Text(
                    confirm.uppercase(Locale.ROOT),
                    color = when {
                        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                        destructive -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(PanicCopy.CANCEL.uppercase(Locale.ROOT)) } },
    )
}

/** A masked digits-only field; the keyboard learns nothing from it. */
@Composable
private fun PinField(label: String, value: String, onChange: (String) -> Unit) {
    NoPersonalizedLearning {
        OutlinedTextField(
            value = value,
            onValueChange = { typed -> onChange(typed.filter { it in '0'..'9' }.take(PinRules.MAX_LENGTH)) },
            singleLine = true,
            label = { Text(label) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, autoCorrectEnabled = false),
        )
    }
}
