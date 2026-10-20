package dev.wckdboy.autobot.shared.invincible

import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Photos sent to the node's agent in a turn: downscaled on the phone to [MAX_SIDE] on the longest
 * side, re-encoded as JPEG (which drops EXIF and location), and sent beside the text as
 * OpenAI-style parts, `{"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,…"}}`.
 */
object NodeImages {
    /** The longest side sent, in pixels (what the node and the large providers scale to anyway). */
    const val MAX_SIDE = 1568

    /** JPEG quality the phones encode with. */
    const val QUALITY = 85

    /** Photos in one message at most. */
    const val MAX_PHOTOS = 4

    /** The node's error when the model cannot see images (400). */
    const val CANNOT_SEE = "model_cannot_see_images"

    const val CANNOT_SEE_MESSAGE = "This bot's model can't see images. Your text is still in the box; send it without the photo, or pick a model that sees images on the node."

    /** [width] × [height] scaled to fit [MAX_SIDE] on its longest side (never up). */
    fun fit(width: Int, height: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
        val longest = max(width, height)
        if (width <= 0 || height <= 0 || longest <= maxSide) return width.coerceAtLeast(0) to height.coerceAtLeast(0)
        val scale = maxSide.toDouble() / longest
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }

    /**
     * A turn's body: `{"text", "bot"?, "images"?: [image_url parts]}`. A node from before photos
     * takes the text and leaves the rest.
     */
    fun turnBody(text: String, bot: String?, images: List<String>): String = buildJsonObject {
        put("text", text)
        if (bot != null) put("bot", bot)
        if (images.isNotEmpty()) {
            putJsonArray("images") {
                images.forEach { data ->
                    addJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") { put("url", "data:image/jpeg;base64,$data") }
                    }
                }
            }
        }
    }.toString()

    /** "1 photo", "2 photos": a user message's images in the transcript. */
    fun label(count: Int): String = if (count == 1) "1 photo" else "$count photos"

}
