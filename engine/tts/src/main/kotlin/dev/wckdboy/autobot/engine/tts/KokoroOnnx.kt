package dev.wckdboy.autobot.engine.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import dev.wckdboy.autobot.shared.tts.KokoroChunk
import dev.wckdboy.autobot.shared.tts.KokoroFrontend
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Kokoro-82M on ONNX Runtime (CPU execution provider; no Android types, so the desktop runtime
 * can run it in tests). One [run] turns one [KokoroChunk] into 24 kHz mono float PCM. Not
 * thread-safe by contract: [LocalVoice] serializes calls. [close] frees the session.
 */
class KokoroOnnx(model: File, threads: Int = defaultThreads(), xnnpack: Boolean = false) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = OrtSession.SessionOptions().use { options ->
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        options.setIntraOpNumThreads(threads)
        options.setInterOpNumThreads(1)
        if (xnnpack) options.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
        env.createSession(model.path, options)
    }

    /** Speech for [chunk] in [voice] (a pack from [readVoice]); [speed] 1 is natural, 0.5–2 sensible. */
    fun run(chunk: KokoroChunk, voice: FloatArray, speed: Float = 1f): FloatArray {
        require(voice.size == KokoroFrontend.VOICE_ROWS * KokoroFrontend.STYLE_DIM) { "not a Kokoro voice pack" }
        val ids = LongArray(chunk.ids.size + 2)
        chunk.ids.forEachIndexed { i, id -> ids[i + 1] = id.toLong() }
        val row = chunk.styleIndex * KokoroFrontend.STYLE_DIM
        val style = FloatBuffer.wrap(voice, row, KokoroFrontend.STYLE_DIM).slice()
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { input ->
            OnnxTensor.createTensor(env, style, longArrayOf(1, KokoroFrontend.STYLE_DIM.toLong())).use { styleTensor ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(speed)), longArrayOf(1)).use { speedTensor ->
                    session.run(mapOf("input_ids" to input, "style" to styleTensor, "speed" to speedTensor)).use { out ->
                        val wave = out[0] as OnnxTensor
                        val buffer = wave.floatBuffer
                        return FloatArray(buffer.remaining()).also { buffer.get(it) }
                    }
                }
            }
        }
    }

    override fun close() = session.close()

    companion object {
        /** The phone's performance cores, at most 4 (more threads stop paying off for an 82M model). */
        fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8).let { if (it >= 8) 4 else (it / 2).coerceAtLeast(1) }

        /** A voice pack (`<id>.bin`: 510 × 256 little-endian float32). */
        fun readVoice(file: File): FloatArray {
            val bytes = file.readBytes()
            require(bytes.size == KokoroFrontend.VOICE_ROWS * KokoroFrontend.STYLE_DIM * 4) { "${file.name} is not a Kokoro voice pack" }
            val floats = FloatArray(bytes.size / 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
            return floats
        }
    }
}
