package dev.wckdboy.autobot.shared.voice

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/**
 * Audio between Swift and the shared voice logic in one memcpy each way (Swift's `KotlinFloatArray`
 * is element-wise, far too slow for 16 000 samples a second). Samples are native-endian floats.
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
object VoiceAudio {
    /** Appends the float samples in [data] to [dictation]. */
    fun append(dictation: StreamingDictation, data: NSData) {
        val count = (data.length / 4u).toInt()
        if (count == 0) return
        val samples = FloatArray(count)
        samples.usePinned { memcpy(it.addressOf(0), data.bytes, (count * 4).toULong()) }
        dictation.append(samples, count)
    }

    /** [pass]'s samples as float data for the engine. */
    fun samples(pass: StreamingDictation.Pass): NSData {
        val samples = pass.samples
        if (samples.isEmpty()) return NSData()
        return samples.usePinned { NSData.create(bytes = it.addressOf(0), length = (samples.size * 4).toULong()) }
    }
}
