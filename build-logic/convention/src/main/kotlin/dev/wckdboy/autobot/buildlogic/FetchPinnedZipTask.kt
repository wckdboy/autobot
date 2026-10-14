package dev.wckdboy.autobot.buildlogic

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Downloads a zip and extracts exactly the entries listed in [sha256], each verified against its
 * pinned SHA-256; anything else in the zip is ignored and any mismatch fails the build. Used for
 * the llama.cpp Hexagon libraries that CI builds with the Linux-only Hexagon SDK.
 */
@DisableCachingByDefault(because = "Downloads from the network; the output directory is the cache")
abstract class FetchPinnedZipTask : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    /** Entry base name → expected lowercase hex SHA-256. */
    @get:Input
    abstract val sha256: MapProperty<String, String>

    /** Directory (relative to [outputDir]) the entries are written to, e.g. `arm64-v8a`. */
    @get:Input
    abstract val into: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val pins = sha256.get()
        val out = outputDir.get().asFile.resolve(into.get()).apply { mkdirs() }
        if (pins.all { (name, hash) -> out.resolve(name).let { it.isFile && digest(it.readBytes()) == hash } }) return

        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        val response = client.send(HttpRequest.newBuilder(URI.create(url.get())).build(), HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) throw IOException("HTTP ${response.statusCode()} for ${url.get()}")

        val found = HashSet<String>()
        ZipInputStream(response.body()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                val name = entry.name.substringAfterLast('/')
                val expected = pins[name] ?: return@forEach
                val bytes = zip.readBytes()
                val actual = digest(bytes)
                if (actual != expected) throw IOException("SHA-256 mismatch for $name: expected $expected, got $actual")
                out.resolve(name).writeBytes(bytes)
                found += name
            }
        }
        val missing = pins.keys - found
        if (missing.isNotEmpty()) throw IOException("Missing from ${url.get()}: $missing")
        logger.lifecycle("Fetched ${found.size} verified files from ${url.get()} into $out")
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
