package dev.wckdboy.autobot.core.designsystem.component

import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PlatformImeOptions

/**
 * Asks the keyboard not to learn from what is typed (incognito keyboard mode).
 *
 * Compose's [KeyboardOptions] only exposes `privateImeOptions` (a free-form string IMEs may ignore),
 * not `EditorInfo.imeOptions` flags. To set the real `IME_FLAG_NO_PERSONALIZED_LEARNING` flag we
 * intercept the platform text input session and patch the [EditorInfo] before it reaches the IME.
 * [PrivateKeyboardOptions] additionally sets the private option for IMEs that look at it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NoPersonalizedLearning(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            val patched = object : PlatformTextInputMethodRequest by request {
                override fun createInputConnection(outAttributes: EditorInfo) =
                    request.createInputConnection(outAttributes).also {
                        outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    }
            }
            nextHandler.startInputMethod(patched)
        },
        content = content,
    )
}

/** Keyboard options for chat input: sentence caps + no-personalized-learning hint. */
val PrivateKeyboardOptions = KeyboardOptions(
    capitalization = KeyboardCapitalization.Sentences,
    platformImeOptions = PlatformImeOptions("flagNoPersonalizedLearning"),
)

/** Keyboard options for prompts, ids and URLs: no auto-caps, no autocorrect, no learning. */
val PromptKeyboardOptions = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    platformImeOptions = PlatformImeOptions("flagNoPersonalizedLearning"),
)

/** Keyboard options for secrets: no suggestions, no learning. */
val SecretKeyboardOptions = KeyboardOptions(
    keyboardType = KeyboardType.Password,
    autoCorrectEnabled = false,
    platformImeOptions = PlatformImeOptions("flagNoPersonalizedLearning"),
)
