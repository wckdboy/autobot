package dev.wckdboy.autobot.providers.remote.vault

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wckdboy.autobot.shared.vault.VaultFileType
import dev.wckdboy.autobot.shared.vault.VaultText
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads files the user picked (Storage Access Framework) into [VaultText]: plain text and
 * Markdown as UTF-8, PDFs with the platform's own text extraction (Android 15+). Photos are not
 * read here (no on-device OCR without Play Services). Nothing is copied or uploaded: only the
 * extracted text goes into the encrypted vault.
 */
@Singleton
class VaultImporter @Inject constructor(@ApplicationContext private val context: Context) {

    sealed interface Read {
        class Ok(val text: VaultText) : Read
        class Skipped(val name: String, val reason: String) : Read
    }

    suspend fun read(uri: Uri): Read = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "document"
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        val type = typeOf(name, resolver.getType(uri))
            ?: return@withContext Read.Skipped(name, "unsupported file type (text, Markdown and PDF can be added)")
        if (type == VaultFileType.IMAGE) return@withContext Read.Skipped(name, "photos can't be read on Android yet (no on-device OCR)")
        try {
            when (type) {
                VaultFileType.PDF -> readPdf(uri, name, size)
                else -> {
                    if (size > MAX_TEXT_BYTES) return@withContext Read.Skipped(name, "larger than ${MAX_TEXT_BYTES / 1_000_000} MB")
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext Read.Skipped(name, "cannot be opened")
                    Read.Ok(VaultText(name, type, bytes.size.toLong(), listOf(bytes.toString(Charsets.UTF_8)), uri.toString()))
                }
            }
        } catch (e: SecurityException) {
            Read.Skipped(name, "permission denied")
        } catch (e: Exception) {
            Read.Skipped(name, e.message ?: "cannot be read")
        }
    }

    private fun readPdf(uri: Uri, name: String, size: Long): Read {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return Read.Skipped(name, "reading PDFs needs Android 15 or newer")
        }
        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return Read.Skipped(name, "cannot be opened")
        fd.use {
            PdfRenderer(it).use { renderer ->
                val pages = (0 until renderer.pageCount).map { i ->
                    renderer.openPage(i).use { page -> page.textContents.joinToString("\n") { t -> t.text } }
                }
                if (pages.all { p -> p.isBlank() }) return Read.Skipped(name, "no text layer (a scanned PDF)")
                return Read.Ok(VaultText(name, VaultFileType.PDF, size.coerceAtLeast(0), pages, uri.toString()))
            }
        }
    }

    companion object {
        const val MAX_TEXT_BYTES = 20_000_000L

        /** MIME types offered by the picker. */
        val MIME_TYPES = arrayOf("text/plain", "text/markdown", "text/x-markdown", "application/pdf", "text/*")

        fun typeOf(name: String, mime: String?): VaultFileType? {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                ext in setOf("md", "markdown") || mime == "text/markdown" || mime == "text/x-markdown" -> VaultFileType.MARKDOWN
                ext == "pdf" || mime == "application/pdf" -> VaultFileType.PDF
                mime?.startsWith("image/") == true -> VaultFileType.IMAGE
                ext in setOf("txt", "text", "csv", "log", "json", "org", "rst") || mime?.startsWith("text/") == true -> VaultFileType.TEXT
                else -> null
            }
        }
    }
}
