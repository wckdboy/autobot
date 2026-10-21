package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** A masked field never gets a keyboard that shows the secret in its suggestion strip. */
class FieldKeyboardTest {
    private val masked = PasswordVisualTransformation()

    @Test
    fun aMaskedFieldGetsAPasswordKeyboard() {
        // The pairing code (a URL keyboard) and the Studio auth value (the code keyboard).
        val uri = fieldKeyboardOptions(KeyboardOptions(keyboardType = KeyboardType.Uri), mono = true, visual = masked)
        assertEquals(KeyboardType.Password, uri.keyboardType)
        assertFalse(uri.autoCorrectEnabled == true)
        val code = fieldKeyboardOptions(null, mono = true, visual = masked)
        assertEquals(KeyboardType.Password, code.keyboardType)
        // Still no personalized learning.
        assertEquals(PromptKeyboardOptions.platformImeOptions, code.platformImeOptions)
    }

    @Test
    fun aPinKeepsItsDigitsKeyboard() {
        val pin = fieldKeyboardOptions(KeyboardOptions(keyboardType = KeyboardType.NumberPassword), mono = false, visual = masked)
        assertEquals(KeyboardType.NumberPassword, pin.keyboardType)
    }

    @Test
    fun anOpenFieldKeepsItsKeyboard() {
        assertEquals(PrivateKeyboardOptions, fieldKeyboardOptions(null, mono = false, visual = VisualTransformation.None))
        val url = KeyboardOptions(keyboardType = KeyboardType.Uri)
        assertEquals(url, fieldKeyboardOptions(url, mono = true, visual = VisualTransformation.None))
    }
}
