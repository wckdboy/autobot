package dev.wckdboy.autobot.core.designsystem.component

import java.util.Locale

/**
 * Sentence case for a section label or caption, never upper case (APP-UI v4 §2): the first letter
 * is capitalized when it comes before any digit (`"recent runs"` → `"Recent runs"`,
 * `"~0 / 75 tokens"` unchanged), and the few abbreviations a label carries read as such
 * (`"models · api & lan"` → `"Models · API & LAN"`). Words that are names in lowercase
 * (`txt2img`) and anything already capitalized are left as they are.
 */
fun labelCase(text: String): String {
    if (text.isEmpty()) return text
    val words = WORD.replace(text) { m -> ABBREVIATIONS[m.value] ?: m.value }
    val first = words.indexOfFirst { it.isLetterOrDigit() }
    if (first < 0 || !words[first].isLetter() || !words[first].isLowerCase()) return words
    // The first word as written up to a space: a host, path, handle or id is left as it is.
    val token = words.substring(first).takeWhile { !it.isWhitespace() }
    val word = token.takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
    if (word.any { it.isDigit() } || word in KEEP_LOWER || token.drop(word.length).let { rest -> rest.startsWith(".") && rest.length > 1 || rest.startsWith("/") || rest.startsWith("@") || rest.startsWith("_") }) return words
    return words.substring(0, first) + words[first].titlecase(Locale.ROOT) + words.substring(first + 1)
}

private val WORD = Regex("(?<![\\w./-])[a-z][a-z0-9+-]*(?![\\w/.-])")

/** Abbreviations, as whole words, in their written form. */
private val ABBREVIATIONS = mapOf(
    "api" to "API", "apis" to "APIs", "lan" to "LAN", "pc" to "PC", "pcs" to "PCs",
    "npu" to "NPU", "gpu" to "GPU", "cpu" to "CPU", "usd" to "USD", "cfg" to "CFG",
    "lora" to "LoRA", "loras" to "LoRAs", "apk" to "APK", "mcp" to "MCP", "http" to "HTTP",
    "sha-256" to "SHA-256", "ram" to "RAM", "url" to "URL", "vae" to "VAE", "comfyui" to "ComfyUI",
    "controlnet" to "ControlNet", "aicore" to "AICore", "ai" to "AI", "pin" to "PIN",
)

/** Lowercase names that start a label as they are. */
private val KEEP_LOWER = setOf("txt2img", "img2img", "autobot", "invincible")
