package dev.wckdboy.autobot.feature.settings.about

import dev.wckdboy.autobot.feature.settings.aboutRowTitle
import org.junit.Assert.assertEquals
import org.junit.Test

/** About's version line, the signing-key check and the release-notes reader. */
class AboutTest {
    private val official = AboutLinks.RELEASE_CERT_SHA256

    @Test
    fun versionReadsNameAndBuildNumber() {
        assertEquals("1.6.4 (16)", AboutInfo("1.6.4", 16).version)
        assertEquals("Autobot 1.6.4 (16)", aboutRowTitle("1.6.4", 16))
    }

    @Test
    fun theReleaseKeyMatchesInAnyNotation() {
        assertEquals(CertMatch.OFFICIAL, certMatch(official))
        assertEquals(CertMatch.OFFICIAL, certMatch(official.uppercase()))
        assertEquals(CertMatch.OFFICIAL, certMatch(official.chunked(2).joinToString(":")))
        // A debug build that somehow carries the release key is still the release key.
        assertEquals(CertMatch.OFFICIAL, certMatch(official, debug = true))
    }

    @Test
    fun otherKeysSayWhy() {
        val other = "0".repeat(64)
        assertEquals(CertMatch.DEBUG, certMatch(other, debug = true))
        assertEquals(CertMatch.DIFFERENT, certMatch(other, debug = false))
        assertEquals(CertMatch.UNKNOWN, certMatch(null))
    }

    @Test
    fun fingerprintsAreHashedAndGrouped() {
        // SHA-256 of the empty input.
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(ByteArray(0)))
        assertEquals("9a13 78ac f958", groupedFingerprint(official).take(14))
        assertEquals(64 + 15, groupedFingerprint(official).length)
    }

    @Test
    fun releaseNotesParse() {
        val notes = parseNotes(
            """
            A calmer Android app: much less text,
            fewer taps.

            ## Less text, fewer taps (Android)
            - **Settings** is grouped. Rows show `Direct`.
            - Second bullet
              wraps here.

            iOS: TestFlight build 1.6.4.
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                NoteBlock.Paragraph("A calmer Android app: much less text, fewer taps."),
                NoteBlock.Heading("Less text, fewer taps (Android)"),
                NoteBlock.Bullet("**Settings** is grouped. Rows show `Direct`."),
                NoteBlock.Bullet("Second bullet wraps here."),
                NoteBlock.Paragraph("iOS: TestFlight build 1.6.4."),
            ),
            notes,
        )
    }

    @Test
    fun inlineBoldAndCode() {
        assertEquals(
            listOf(NoteSpan("Settings", bold = true), NoteSpan(" is "), NoteSpan("Direct", code = true), NoteSpan(".")),
            parseInline("**Settings** is `Direct`."),
        )
        assertEquals("unmatched markers stay text", listOf(NoteSpan("2 ** 3 and `x")), parseInline("2 ** 3 and `x"))
    }

    @Test
    fun theBundledNotesParse() {
        // The file release.sh updates, packaged as the `whats-new.md` asset.
        val file = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .map { java.io.File(it, "shared/release/whats-new.md") }
            .firstOrNull { it.isFile } ?: return
        val blocks = parseNotes(file.readText())
        assert(blocks.any { it is NoteBlock.Heading }) { "no headings in ${file.path}" }
        assert(blocks.none { it.text.isBlank() })
    }
}
