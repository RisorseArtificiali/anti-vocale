package com.antivocale.app.data

import org.json.JSONObject

/**
 * The bundled external-model catalog index (TASK-331 Task 13): a minimal list of
 * curated models (name, languages, family, entry-JSON URL) shipped as an asset at
 * assets/external-catalog/index.json. The import-from-catalog dialog browses it
 * by language; the query matcher below is its text-search surface and the
 * unit-test surface.
 *
 * The matcher and parsing are pure so both are unit-testable without Robolectric.
 */
object ExternalCatalog {

    private val WHITESPACE = Regex("\\s+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    data class CatalogEntry(
        val name: String,
        val languages: List<String>,
        val entryUrl: String,
        val family: ModelFamily,
    )

    /**
     * True when every whitespace-separated query token matches the entry: against
     * any language code (equal or prefix, case-insensitive, so "pt" finds "pt-BR")
     * or as a prefix of a whole word of the display name ("arabic" finds
     * "... Arabic ...", "whis" finds "Whisper"; "ar" finds Arabic only via its
     * language code and never via the "ar" inside "Canary"). A blank query
     * matches everything.
     */
    fun matchesQuery(name: String, languages: List<String>, query: String): Boolean {
        val tokens = query.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return true
        val words = name.split(NON_WORD).filter { it.isNotEmpty() }
        return tokens.all { token ->
            languages.any { it.equals(token, ignoreCase = true) || it.startsWith(token, ignoreCase = true) } ||
                words.any { it.startsWith(token, ignoreCase = true) }
        }
    }

    /**
     * TASK-401: partitions [entries] by whether they declare [language]. A blank
     * language matches everything (all entries in the first list). Used by the
     * URL dialog's language-driven discovery: matching entries first, the rest
     * rendered under the "other" separator.
     */
    fun partitionByLanguage(
        entries: List<CatalogEntry>,
        language: String,
    ): Pair<List<CatalogEntry>, List<CatalogEntry>> {
        if (language.isBlank()) return entries to emptyList()
        val code = language.lowercase()
        return entries.partition { e ->
            e.languages.any { it.equals(code, ignoreCase = true) || it.startsWith("$code-", ignoreCase = true) }
        }
    }

    /** Entries matching [query], in index order. */
    fun filter(entries: List<CatalogEntry>, query: String): List<CatalogEntry> =
        entries.filter { matchesQuery(it.name, it.languages, query) }

    /**
     * Parses the index JSON ({"entries": [...]}). Malformed entries and unknown
     * family strings are skipped: a catalog read must never crash the import
     * dialog. An entry without "family" defaults to TRANSDUCER, matching the
     * entry-JSON backward-compat rule.
     */
    fun parseIndex(text: String): List<CatalogEntry> {
        val arr = runCatching { JSONObject(text).optJSONArray("entries") }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val name = e.optString("name")
                val url = e.optString("entryUrl")
                if (name.isBlank() || url.isBlank()) continue
                val family = runCatching {
                    ModelFamily.valueOf(e.optString("family", ModelFamily.TRANSDUCER.name))
                }.getOrNull() ?: continue
                val langs = e.optJSONArray("languages")?.optStringList() ?: emptyList()
                add(CatalogEntry(
                    name = name,
                    languages = langs,
                    entryUrl = url,
                    family = family,
                ))
            }
        }
    }
}
