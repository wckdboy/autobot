package dev.wckdboy.autobot.engine.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.FloatBuffer
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The app removes ONNX Runtime's `TelemetryInitializer` content provider from its manifest (it would
 * phone home). Sessions must open without it: nothing in the session path depends on it having run.
 * Here no initializer runs at all, and a session opens through [KokoroOnnx]'s own constructor
 * (the same options as on the phone) and runs.
 *
 * On Android the remaining dependency is native: `libonnxruntime.so` only logs "Android telemetry
 * is unavailable because the 1DS Java HttpClient was not initialized" and carries on; neither the
 * JNI library nor the Java classes look the initializer or its HTTP client up.
 */
class OnnxWithoutTelemetryTest {
    private val dir = Files.createTempDirectory("onnx").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun aSessionOpensAndRunsWithoutTheTelemetryInitializer() {
        val model = File(dir, "identity.onnx").apply { writeBytes(identityModel()) }

        // The voice engine's session setup, as LocalVoice does it.
        KokoroOnnx(model, threads = 1).close()

        val env = OrtEnvironment.getEnvironment()
        env.createSession(model.path, OrtSession.SessionOptions()).use { session ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(0.25f, -1f)), longArrayOf(2)).use { x ->
                session.run(mapOf("x" to x)).use { out ->
                    val y = (out[0] as OnnxTensor).floatBuffer
                    assertEquals(2, y.remaining())
                    assertArrayEquals(floatArrayOf(0.25f, -1f), FloatArray(2).also { y.get(it) }, 0f)
                }
            }
        }
    }

    /** An ONNX model `y = Identity(x)` over a float[2], hand-encoded (protobuf) so the test needs no file. */
    private fun identityModel(): ByteArray {
        fun varint(out: ByteArrayOutputStream, value: Long) {
            var v = value
            while (v and 0x7fL.inv() != 0L) {
                out.write(((v and 0x7f) or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
        }
        fun field(field: Int, value: Long) = ByteArrayOutputStream().also { varint(it, (field shl 3).toLong()); varint(it, value) }.toByteArray()
        fun field(field: Int, bytes: ByteArray) = ByteArrayOutputStream().also {
            varint(it, ((field shl 3) or 2).toLong())
            varint(it, bytes.size.toLong())
            it.write(bytes)
        }.toByteArray()
        fun field(field: Int, text: String) = field(field, text.toByteArray())

        val dim = field(1, 2L) // dim_value
        val shape = field(1, dim) // TensorShapeProto.dim
        val tensor = field(1, 1L) + field(2, shape) // elem_type FLOAT, shape
        val type = field(1, tensor) // TypeProto.tensor_type
        fun valueInfo(name: String) = field(1, name) + field(2, type)
        val node = field(1, "x") + field(2, "y") + field(4, "Identity")
        val graph = field(1, node) + field(2, "g") + field(11, valueInfo("x")) + field(12, valueInfo("y"))
        val opset = field(1, "") + field(2, 13L)
        return field(1, 7L) + field(7, graph) + field(8, opset)
    }
}
