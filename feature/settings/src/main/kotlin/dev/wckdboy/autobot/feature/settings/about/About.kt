package dev.wckdboy.autobot.feature.settings.about

import androidx.compose.runtime.Immutable
import java.security.MessageDigest
import java.util.Locale

/** What the build knows about itself (from the app's BuildConfig). */
@Immutable
data class AboutInfo(
    val versionName: String,
    val versionCode: Long,
    /** `2026-10-16`, or blank when unknown. */
    val buildDate: String = "",
    /** A debug build (signed with a development key). */
    val debug: Boolean = false,
) {
    /** `1.6.4 (16)`. */
    val version: String get() = "$versionName ($versionCode)"
}

object AboutLinks {
    const val RELEASES = "https://github.com/wckdboy/autobot/releases"
    const val SOURCE = "https://github.com/wckdboy/autobot"

    /** Signer certificate SHA-256 of the official release APKs (also in each release's notes). */
    const val RELEASE_CERT_SHA256 = "9a1378acf9587885e7920355558c85a1ec0e89304f58c153feebf90fdee7f3f1"

    const val PRIVACY = "No analytics. No telemetry. Network only where you allow it."
}

/** How this APK's signer compares with the official release key. */
enum class CertMatch(val headline: String) {
    /** Signed with the release key: an official build. */
    OFFICIAL("Matches the official release key"),

    /** A debug build: signed with a development key, so it never matches. */
    DEBUG("Debug build: signed with a development key, so it differs"),

    /** Another key: an F-Droid, self-built or repackaged APK. */
    DIFFERENT("Signed with a different key (F-Droid, self-built or repackaged)"),

    /** The signer could not be read. */
    UNKNOWN("Could not read this app's signing certificate"),
}

/** Compares the running APK's signer [actual] (hex SHA-256, any case, colons allowed) with [expected]. */
fun certMatch(actual: String?, expected: String = AboutLinks.RELEASE_CERT_SHA256, debug: Boolean = false): CertMatch = when {
    actual == null -> CertMatch.UNKNOWN
    normalizeFingerprint(actual) == normalizeFingerprint(expected) -> CertMatch.OFFICIAL
    debug -> CertMatch.DEBUG
    else -> CertMatch.DIFFERENT
}

/** Lower-case hex without separators: `9A:13:…` and `9a13…` compare equal. */
fun normalizeFingerprint(fingerprint: String): String = fingerprint.filter { it.isLetterOrDigit() }.lowercase(Locale.ROOT)

/** SHA-256 of a DER-encoded certificate, as lower-case hex. */
fun sha256Hex(der: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }

/** `9a13 78ac f958 …`: groups of four, for reading aloud or comparing by eye. */
fun groupedFingerprint(hex: String): String = normalizeFingerprint(hex).chunked(4).joinToString(" ")

// ---------------------------------------------------------------------------------------------
// Release notes: a small Markdown subset (## headings, - bullets, paragraphs, **bold**, `code`).

/** A block of the release notes. */
@Immutable
sealed interface NoteBlock {
    val text: String

    data class Heading(override val text: String) : NoteBlock
    data class Bullet(override val text: String) : NoteBlock
    data class Paragraph(override val text: String) : NoteBlock
}

/** Blocks of [markdown]: `#`/`##` headings, `-`/`*` bullets, and paragraphs (consecutive lines joined). */
fun parseNotes(markdown: String): List<NoteBlock> {
    val blocks = mutableListOf<NoteBlock>()
    val paragraph = StringBuilder()
    fun flush() {
        if (paragraph.isNotBlank()) blocks += NoteBlock.Paragraph(paragraph.toString().trim())
        paragraph.clear()
    }
    for (raw in markdown.lines()) {
        val line = raw.trim()
        when {
            line.isEmpty() -> flush()
            line.startsWith("#") -> {
                flush()
                blocks += NoteBlock.Heading(line.trimStart('#').trim())
            }
            line.startsWith("- ") || line.startsWith("* ") -> {
                flush()
                blocks += NoteBlock.Bullet(line.drop(2).trim())
            }
            // A bullet's wrapped continuation line.
            raw.startsWith("  ") && blocks.lastOrNull() is NoteBlock.Bullet && paragraph.isEmpty() -> {
                val last = blocks.removeAt(blocks.lastIndex)
                blocks += NoteBlock.Bullet(last.text + " " + line)
            }
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(line)
            }
        }
    }
    flush()
    return blocks
}

/** A run of inline text: plain, **bold** or `code`. */
@Immutable
data class NoteSpan(val text: String, val bold: Boolean = false, val code: Boolean = false)

/** Splits [text] into plain, `**bold**` and `` `code` `` runs; unmatched markers stay as text. */
fun parseInline(text: String): List<NoteSpan> {
    val spans = mutableListOf<NoteSpan>()
    val plain = StringBuilder()
    var i = 0
    fun flushPlain() {
        if (plain.isNotEmpty()) spans += NoteSpan(plain.toString())
        plain.clear()
    }
    while (i < text.length) {
        if (text.startsWith("**", i)) {
            val end = text.indexOf("**", i + 2)
            if (end > i + 2) {
                flushPlain()
                spans += NoteSpan(text.substring(i + 2, end), bold = true)
                i = end + 2
                continue
            }
        } else if (text[i] == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i + 1) {
                flushPlain()
                spans += NoteSpan(text.substring(i + 1, end), code = true)
                i = end + 1
                continue
            }
        }
        plain.append(text[i])
        i++
    }
    flushPlain()
    return spans
}
