package com.antivocale.app.data

import com.antivocale.app.testing.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * TASK-929: the release-notes parser. The sample mirrors the real file's
 * shape (localized headers per block, both bullet forms, blank separators);
 * the real-file test parses the DOCS file itself, so a listing-format
 * change that the parser cannot read fails here and not on a device.
 */
class ReleaseNotesTest {

    private val sample = """
        <en-US>
        What's new in 2.0.0:
        • first bullet
        - dash bullet
        What's new in 1.9.0:
        • older bullet

        What's new in 1.8.0:
        • oldest bullet
        </en-US>

        <it-IT>
        Novità della versione 2.0.0:
        • primo punto
        Novità della versione 1.9.0:
        • vecchio punto
        </it-IT>

        <uk>
        Що нового у версії 2.0.0:
        • перший пункт
        </uk>

        <fr-FR>
        Nouveautés de la version 2.0.0 :
        • premier point
        </fr-FR>

        <zh-CN>
        2.0.0 版本更新：
        • 第一点
        </zh-CN>
    """.trimIndent()

    @Test
    fun `the version section keeps its bullets in order, both bullet forms`() {
        assertEquals(
            listOf("• first bullet", "- dash bullet"),
            ReleaseNotes.parse(sample, "en-US", "2.0.0"),
        )
    }

    @Test
    fun `the section stops at the next version's header`() {
        assertEquals(
            listOf("• older bullet"),
            ReleaseNotes.parse(sample, "en-US", "1.9.0"),
        )
    }

    @Test
    fun `a two-letter device locale reaches its xx-XX block`() {
        assertEquals(
            listOf("• primo punto"),
            ReleaseNotes.parse(sample, "it", "2.0.0"),
        )
    }

    @Test
    fun `a plain two-letter tag matches directly`() {
        assertEquals(
            listOf("• перший пункт"),
            ReleaseNotes.parse(sample, "uk", "2.0.0"),
        )
    }

    @Test
    fun `a full tag matches exactly`() {
        assertEquals(
            listOf("• primo punto"),
            ReleaseNotes.parse(sample, "it-IT", "2.0.0"),
        )
    }

    @Test
    fun `an unknown locale falls back to en-US`() {
        assertEquals(
            listOf("• first bullet", "- dash bullet"),
            ReleaseNotes.parse(sample, "de-AT", "2.0.0"),
        )
    }

    @Test
    fun `a version the locale block lacks returns empty (no notes)`() {
        assertTrue(ReleaseNotes.parse(sample, "it", "9.9.9").isEmpty())
    }

    @Test
    fun `a trailing-space header still opens the section`() {
        assertEquals(
            listOf("• premier point"),
            ReleaseNotes.parse(sample, "fr", "2.0.0"),
        )
    }

    @Test
    fun `a full-width colon header still opens the section`() {
        assertEquals(
            listOf("• 第一点"),
            ReleaseNotes.parse(sample, "zh", "2.0.0"),
        )
    }

    @Test
    fun `the locale match is case-insensitive`() {
        assertEquals(
            listOf("• перший пункт"),
            ReleaseNotes.parse(sample, "UK", "2.0.0"),
        )
    }

    /** The live docs file, parsed through the same core the asset path
     *  uses: the newest en-US section extracts, and the it block (whose
     *  header is localized prose) resolves for the same version. */
    @Test
    fun `the real docs release notes parse for their newest version`() {
        val text = RepoFiles.readText("docs/play-store/release-notes.xml")
        val firstHeader = text.lineSequence().first { it.startsWith("What's new in ") }
        val version = firstHeader.removePrefix("What's new in ").removeSuffix(":")
        val en = ReleaseNotes.parse(text, "en-US", version)
        assertTrue("en-US section for $version should have bullets", en.isNotEmpty())
        assertTrue(en.all { it.startsWith("•") || it.startsWith("-") })
        val it = ReleaseNotes.parse(text, "it", version)
        assertTrue("it-IT section for $version should have bullets", it.isNotEmpty())
    }

    /** The drift tripwire: assets/release-notes.xml is a BYTE COPY of
     *  docs/play-store/release-notes.xml. When this fails you edited the
     *  docs file; re-copy it over the asset. */
    @Test
    fun `assets release-notes is a byte copy of docs play-store release-notes (re-copy after editing the docs file)`() {
        val docs = RepoFiles.resolve("docs/play-store/release-notes.xml")
        val asset = RepoFiles.resolve("src/main/assets/release-notes.xml")
        val digest = MessageDigest.getInstance("SHA-256")
        val docsHash = digest.digest(docs.readBytes())
        val assetHash = MessageDigest.getInstance("SHA-256").digest(asset.readBytes())
        assertTrue(
            "app/src/main/assets/release-notes.xml drifted from " +
                "docs/play-store/release-notes.xml; re-copy the docs file over the asset",
            docsHash.contentEquals(assetHash),
        )
    }
}
