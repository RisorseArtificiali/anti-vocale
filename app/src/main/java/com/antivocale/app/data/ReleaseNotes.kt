package com.antivocale.app.data

import android.content.res.AssetManager

/**
 * TASK-929: the in-app changelog reads the SAME release notes the store
 * listing does. The source of record is docs/play-store/release-notes.xml,
 * byte-copied into assets (the drift test in ReleaseNotesTest fails when
 * the docs file changes without a re-copy).
 *
 * The file is pseudo-XML: one `<locale-tag>` line opens a block per locale
 * (`<en-US>`, `<it-IT>`, plain `<uk>`), and a block holds localized
 * "version header:" lines each followed by bullet lines. The headers are
 * LOCALE-LOCALIZED prose ("What's new in 1.15.0:", "Novità della versione
 * 1.15.0:", "1.15.0 版本更新：" with the full-width colon), so a section is
 * identified by containing the version name and ending in a colon (ASCII
 * or full-width), never by an English prefix.
 */
object ReleaseNotes {

    const val ASSET_PATH = "release-notes.xml"
    const val FALLBACK_TAG = "en-US"

    private val TAG_LINE = Regex("<([A-Za-z]{2}(?:-[A-Za-z]{2})?)>")

    private val BULLET_PREFIXES = listOf("•", "-")

    /** The parsed locale blocks, read once per process (the asset's bytes
     *  are fixed for the process lifetime; every re-call, rotation, and
     *  hub-card tap reuses this map). */
    private var cachedBlocks: Map<String, List<String>>? = null

    /** The current version's bullet lines for [inAppLanguage], empty when
     *  absent (the UI treats empty as "no notes"). The blank-language
     *  fallback to the system locale is OWNED here, not at the call sites:
     *  one rule, one entry point (the simplify review found three inline
     *  copies of the ifBlank expression otherwise). */
    fun notesFor(inAppLanguage: String, versionName: String, assets: AssetManager): List<String> {
        val locale = inAppLanguage.ifBlank { java.util.Locale.getDefault().toLanguageTag() }
        val blocks = cachedBlocks ?: run {
            val text = assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
            localeBlocks(text).also { cachedBlocks = it }
        }
        val tag = resolveTag(locale, blocks.keys) ?: FALLBACK_TAG
        return bulletsFor(blocks[tag] ?: emptyList(), versionName)
    }

    /** Pure core (JVM-tested): resolve the locale block, extract the
     *  version's bullets. */
    fun parse(text: String, locale: String, versionName: String): List<String> {
        val blocks = localeBlocks(text)
        val tag = resolveTag(locale, blocks.keys) ?: FALLBACK_TAG
        return bulletsFor(blocks[tag] ?: emptyList(), versionName)
    }

    /** Tag lines split the file; content before the first tag is dropped
     *  and lines are trimmed (the bullets carry no leading whitespace). */
    private fun localeBlocks(text: String): Map<String, List<String>> {
        val blocks = LinkedHashMap<String, MutableList<String>>()
        var current: MutableList<String>? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            val tag = TAG_LINE.matchEntire(line)?.groupValues?.getOrNull(1)
            if (tag != null) {
                current = blocks.getOrPut(tag) { mutableListOf() }
            } else if (current != null && line.isNotEmpty()) {
                current.add(line)
            }
        }
        return blocks
    }

    /**
     * Exact tag first ("uk" matches `<uk>`, "it-IT" matches `<it-IT>`),
     * then the language part ("it" matches `<it-IT>`); null sends the
     * caller to [FALLBACK_TAG]. Region nuance is deliberately not chased:
     * each language has exactly one block, so zh-TW and pt-PT land on the
     * only block their language has.
     */
    private fun resolveTag(locale: String, tags: Collection<String>): String? {
        val wanted = locale.trim().replace('_', '-').lowercase()
        if (wanted.isEmpty()) return null
        tags.firstOrNull { it.lowercase() == wanted }?.let { return it }
        val language = wanted.substringBefore('-')
        return tags.firstOrNull { it.substringBefore('-').lowercase() == language }
    }

    private fun isBullet(line: String) = BULLET_PREFIXES.any { line.startsWith(it) }

    private fun endsWithColon(line: String) =
        line.endsWith(":") || line.endsWith("：")

    /** A section header names the version and ends in a colon; a bullet is
     *  never a header even when it cites the version. */
    private fun isHeader(line: String, versionName: String) =
        !isBullet(line) && line.contains(versionName) && endsWithColon(line)

    /** The matching header opens the section; the next colon-ending
     *  non-bullet line (in practice the next version's header) or the
     *  block end closes it. Only bullet lines are collected; blank and
     *  stray prose lines are skipped without ending the section. */
    private fun bulletsFor(block: List<String>, versionName: String): List<String> {
        val bullets = mutableListOf<String>()
        var inSection = false
        for (line in block) {
            if (isHeader(line, versionName)) {
                inSection = true
                bullets.clear()
            } else if (inSection) {
                if (!isBullet(line) && endsWithColon(line)) break
                if (isBullet(line)) bullets.add(line)
            }
        }
        return bullets
    }
}
