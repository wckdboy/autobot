package dev.wckdboy.autobot.engine.llama

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A model file that cannot load is named before llama.cpp tries it: gone, not GGUF, cut short, or not a chat model. */
class GgufCheckTest {
    private val dir = Files.createTempDirectory("gguf").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** A small but well-formed GGUF v3: [kvs] then two tensors, the last one's data at [lastOffset]. */
    private fun gguf(architecture: String = "llama", lastOffset: Long = 64, dataBytes: Int = 128, extraKvs: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        fun u32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun u64(v: Long) = out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        fun str(s: String) {
            val b = s.toByteArray()
            u64(b.size.toLong())
            out.write(b)
        }
        out.write("GGUF".toByteArray())
        u32(3)
        u64(2) // tensors
        u64(if (extraKvs) 4 else 1)
        str("general.architecture"); u32(8); str(architecture)
        if (extraKvs) {
            str("llama.context_length"); u32(4); u32(4096)
            str("tokenizer.ggml.tokens"); u32(9); u32(8); u64(3); str("a"); str("b"); str("<s>")
            str("tokenizer.ggml.scores"); u32(9); u32(6); u64(3); repeat(3) { u32(0) }
        }
        str("token_embd.weight"); u32(2); u64(4); u64(4); u32(0); u64(0)
        str("output.weight"); u32(2); u64(4); u64(4); u32(0); u64(lastOffset)
        while (out.size() % 32 != 0) out.write(0)
        out.write(ByteArray(dataBytes))
        return out.toByteArray()
    }

    private fun file(bytes: ByteArray) = File(dir, "m.gguf").apply { writeBytes(bytes) }

    @Test
    fun aGoodFilePassesAndNamesItsArchitecture() {
        val (header, problem) = GgufCheck.inspect(file(gguf()).path)
        assertNull(problem?.message, problem)
        assertEquals("llama", header!!.architecture)
    }

    @Test
    fun aMissingFileSaysToDownloadAgain() {
        val problem = GgufCheck.inspect(File(dir, "gone.gguf").path).second
        assertTrue(problem!!.message, problem.message.contains("missing") && problem.message.contains("download it again"))
    }

    @Test
    fun aFileThatIsNotGgufIsNamed() {
        val problem = GgufCheck.inspect(file("<!DOCTYPE html><html>rate limited</html>".padEnd(64).toByteArray()).path).second
        assertTrue(problem!!.message, problem.message.startsWith("not a GGUF model file"))
    }

    @Test
    fun aFileCutShortInItsHeaderOrItsDataIsIncomplete() {
        val good = gguf()
        val inHeader = GgufCheck.inspect(file(good.copyOfRange(0, 90)).path).second
        assertTrue(inHeader!!.message, inHeader.message.contains("incomplete"))
        // The header is whole, but the file stops before the last tensor's data.
        val inData = GgufCheck.inspect(file(gguf(lastOffset = 4096, dataBytes = 100)).path).second
        assertTrue(inData!!.message, inData.message.contains("incomplete"))
    }

    @Test
    fun aVisionProjectorIsNotTakenForAChatModel() {
        val problem = GgufCheck.inspect(file(gguf(architecture = "clip")).path).second
        assertNotNull(problem)
        assertTrue(problem!!.message, problem.message.contains("not a chat model"))
    }

    @Test
    fun anUnknownArchitectureIsLeftToLlamaCpp() {
        val (header, problem) = GgufCheck.inspect(file(gguf(architecture = "some-future-arch", extraKvs = false)).path)
        assertNull(problem)
        assertEquals("some-future-arch", header!!.architecture)
    }
}
