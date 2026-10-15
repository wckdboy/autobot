package dev.wckdboy.autobot.engine.audiogen

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import dev.wckdboy.autobot.core.models.StableAudioCatalog
import dev.wckdboy.autobot.shared.audiogen.SecondsEmbedder
import dev.wckdboy.autobot.shared.audiogen.StableAudio
import dev.wckdboy.autobot.shared.audiogen.StableAudioModels
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Stable Audio Open Small's three graphs on ONNX Runtime (CPU execution provider; no Android types,
 * so the desktop runtime runs it in tests): T5 encoder, one DiT step, Oobleck decoder, each with its
 * external `<graph>.data` weights next to it in [folder], plus the `seconds_total` embedder in
 * Kotlin. Sessions open on first use; with [lowMemory] each is closed as soon as its stage is done
 * and ONNX Runtime's arena is off, so only one is resident at a time. Measured on the desktop runtime
 * (M-series, 8 threads): peak resident ~3.4 GB with everything kept, ~2.8 GB with [lowMemory] — most of it
 * the decoder's activations for 11.9 s of 128-channel audio; the DiT is ~350 MB of 8-bit weights.
 * Not thread-safe by contract: [LocalAudioGen] serializes calls.
 */
class StableAudioOnnx(
    private val folder: File,
    private val threads: Int = defaultThreads(),
    private val xnnpack: Boolean = false,
    private val lowMemory: Boolean = false,
) : StableAudioModels, AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val seconds: SecondsEmbedder = SecondsEmbedder.load(File(folder, StableAudioCatalog.SECONDS_EMBEDDER).path)
    private var t5: OrtSession? = null
    private var dit: OrtSession? = null
    private var decoder: OrtSession? = null

    private fun open(name: String): OrtSession {
        val file = File(folder, name)
        require(file.isFile) { "$name is missing" }
        return OrtSession.SessionOptions().use { options ->
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            options.setIntraOpNumThreads(threads)
            options.setInterOpNumThreads(1)
            if (lowMemory) {
                options.setCPUArenaAllocator(false)
                options.setMemoryPatternOptimization(false)
            }
            if (xnnpack) options.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
            // The graphs reference their weights by relative path: load from the file, not bytes.
            env.createSession(file.path, options)
        }
    }

    override fun encodeText(ids: IntArray, mask: IntArray): FloatArray {
        val session = t5 ?: open(StableAudioCatalog.ONNX_TEXT_ENCODER).also { t5 = it }
        val shape = longArrayOf(1, ids.size.toLong())
        val out = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { ids[it].toLong() }), shape).use { input ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(mask.size) { mask[it].toLong() }), shape).use { attention ->
                session.run(mapOf("input_ids" to input, "attention_mask" to attention)).use { floats(it[0] as OnnxTensor) }
            }
        }
        if (lowMemory) t5 = session.closeAndNull()
        return out
    }

    override fun embedSeconds(seconds: Double): FloatArray = this.seconds.embed(seconds)

    override fun velocity(latent: FloatArray, t: Float, cross: FloatArray, global: FloatArray): FloatArray {
        val session = dit ?: open(StableAudioCatalog.ONNX_DIT).also { dit = it }
        return tensor(latent, StableAudio.LATENT_CHANNELS, StableAudio.LATENT_LENGTH).use { x ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(t)), longArrayOf(1)).use { tt ->
                tensor(cross, StableAudio.COND_TOKENS, StableAudio.COND_DIM).use { c ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(global), longArrayOf(1, StableAudio.COND_DIM.toLong())).use { g ->
                        session.run(mapOf("x" to x, "t" to tt, "cross_attn_cond" to c, "global_cond" to g)).use { floats(it[0] as OnnxTensor) }
                    }
                }
            }
        }
    }

    override fun decode(latent: FloatArray): FloatArray {
        if (lowMemory) dit = dit?.closeAndNull()
        val session = decoder ?: open(StableAudioCatalog.ONNX_DECODER).also { decoder = it }
        val out = tensor(latent, StableAudio.LATENT_CHANNELS, StableAudio.LATENT_LENGTH).use { x ->
            session.run(mapOf("latent" to x)).use { floats(it[0] as OnnxTensor) }
        }
        if (lowMemory) decoder = session.closeAndNull()
        return out
    }

    /** Frees every session (the next call reopens what it needs). */
    override fun close() {
        t5 = t5?.closeAndNull()
        dit = dit?.closeAndNull()
        decoder = decoder?.closeAndNull()
    }

    private fun tensor(values: FloatArray, rows: Int, cols: Int): OnnxTensor =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(values), longArrayOf(1, rows.toLong(), cols.toLong()))

    private fun floats(t: OnnxTensor): FloatArray {
        val b = t.floatBuffer
        return FloatArray(b.remaining()).also { b.get(it) }
    }

    private fun OrtSession.closeAndNull(): OrtSession? {
        close()
        return null
    }

    companion object {
        /** All performance cores (the DiT is compute-bound): 6 of 8, at most 8. */
        fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().let { n -> if (n >= 8) (n * 3 / 4).coerceAtMost(8) else n.coerceAtLeast(1) }

        /** Whether [folder] holds a complete ONNX bundle. */
        fun installed(folder: File): Boolean = listOf(
            StableAudioCatalog.ONNX_TEXT_ENCODER, StableAudioCatalog.ONNX_DIT, StableAudioCatalog.ONNX_DECODER,
            StableAudioCatalog.SECONDS_EMBEDDER, StableAudioCatalog.TOKENIZER,
        ).all { File(folder, it).isFile }
    }
}
