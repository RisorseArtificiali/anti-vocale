package com.antivocale.app.ui.tabs

import com.antivocale.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-689 pins: SETTINGS_SEARCH_CARDS is the ONE source both the match
 * count (and section visibility) and every SearchFilterRow gate read. These
 * tests pin the derived per-card vocabularies (byte-identical to the
 * TASK-542 count groups they replace, except where unification closed a
 * real gate-vs-count gap: refinement, speaker labels, speaker identities,
 * and the theme card's text size) and the condition flips, so a registry
 * edit that changes what the count line reports must consciously update
 * these expectations. The id-coverage pins make a gate written for a card
 * missing from the count side fail loudly here (settingsSearchGateTexts
 * would throw at runtime instead).
 */
class SettingsSearchRegistryTest {

    private fun state(
        isLlmBackend: Boolean = false,
        isModelLoaded: Boolean = false,
        gemmaConfigured: Boolean = true,
        punctuationPromptForced: Boolean = false,
        summarizeOn: Boolean = false,
        batteryExemptionOffered: Boolean = false,
        speakerIdEnabled: Boolean = false,
        transcriptionHintRes: Int? = null,
    ) = SettingsSearchState(
        isLlmBackend = isLlmBackend,
        isModelLoaded = isModelLoaded,
        gemmaConfigured = gemmaConfigured,
        punctuationPromptForced = punctuationPromptForced,
        summarizeOn = summarizeOn,
        batteryExemptionOffered = batteryExemptionOffered,
        speakerIdEnabled = speakerIdEnabled,
        transcriptionHintRes = transcriptionHintRes,
    )

    /** The same derivation SettingsTab runs, one level up (ids, not strings). */
    private fun visibleIds(section: SettingsSearchSection, state: SettingsSearchState): List<SettingsSearchId> =
        SETTINGS_SEARCH_CARDS
            .filter { card -> card.section == section && card.visible(state) }
            .map { card -> card.id }

    private fun res(id: SettingsSearchId, state: SettingsSearchState): List<Int> =
        SETTINGS_SEARCH_CARDS.first { card -> card.id == id }.res(state)

    @Test
    fun `every id has exactly one registry entry`() {
        assertEquals(SettingsSearchId.entries.size, SETTINGS_SEARCH_CARDS.size)
        assertEquals(
            SettingsSearchId.entries.size,
            SETTINGS_SEARCH_CARDS.map { card -> card.id }.distinct().size,
        )
    }

    @Test
    fun `every section has at least one card`() {
        SettingsSearchSection.entries.forEach { section ->
            assertEquals(true, SETTINGS_SEARCH_CARDS.any { card -> card.section == section })
        }
    }

    @Test
    fun `transcription vocabularies with gemma on a non-llm backend`() {
        // The old transcriptionSearchGroups, entry for entry, plus the
        // three cards the two-list convention had left uncounted.
        assertEquals(
            listOf(
                SettingsSearchId.ACTIVE_MODEL,
                SettingsSearchId.TRANSCRIPTION_LANGUAGE,
                SettingsSearchId.AUTO_COPY,
                SettingsSearchId.EXPORT_SETTINGS,
                SettingsSearchId.REFINEMENT,
                SettingsSearchId.SPEAKER_LABELS,
                SettingsSearchId.VAD,
                SettingsSearchId.PROGRESSIVE, SettingsSearchId.EARLY_PREVIEW,
                SettingsSearchId.PUNCTUATION_MODE,
                SettingsSearchId.SUMMARIZE,
                SettingsSearchId.SIGNATURE,
                SettingsSearchId.KEEP_ALIVE_TIMEOUT,
            ),
            visibleIds(SettingsSearchSection.TRANSCRIPTION, state()),
        )
    }

    @Test
    fun `transcription vocabularies without gemma`() {
        assertEquals(
            listOf(
                SettingsSearchId.ACTIVE_MODEL,
                SettingsSearchId.TRANSCRIPTION_LANGUAGE,
                SettingsSearchId.AUTO_COPY,
                SettingsSearchId.EXPORT_SETTINGS,
                SettingsSearchId.REFINEMENT,
                SettingsSearchId.SPEAKER_LABELS,
                SettingsSearchId.VAD,
                SettingsSearchId.PROGRESSIVE, SettingsSearchId.EARLY_PREVIEW,
                SettingsSearchId.SIGNATURE,
                SettingsSearchId.KEEP_ALIVE_TIMEOUT,
            ),
            visibleIds(SettingsSearchSection.TRANSCRIPTION, state(gemmaConfigured = false)),
        )
    }

    @Test
    fun `llm backend swaps the gemma-only rows for the llm-only rows`() {
        // Review F3: EXHAUSTIVE on the LLM path (the old spot checks left
        // 10 of 17 cards unpinned where the most conditionals flip; a wrong
        // visible on this path silently loses a card from search).
        assertEquals(
            listOf(
                SettingsSearchId.MODEL_STATUS,
                SettingsSearchId.ACTIVE_MODEL,
                SettingsSearchId.TRANSCRIPTION_LANGUAGE,
                SettingsSearchId.AUTO_COPY,
                SettingsSearchId.EXPORT_SETTINGS,
                SettingsSearchId.REFINEMENT,
                SettingsSearchId.SPEAKER_LABELS,
                // SPEAKER_IDENTITIES is absent: speakerIdEnabled defaults
                // false in this state (its own flip test covers it).
                SettingsSearchId.VAD,
                SettingsSearchId.PROGRESSIVE, SettingsSearchId.EARLY_PREVIEW,
                SettingsSearchId.SUMMARIZE,
                SettingsSearchId.SIGNATURE,
                // SUMMARY_PROMPT is absent too: summarizeOn defaults false
                // in this state (its flip test covers the pair together).
                SettingsSearchId.DEFAULT_PROMPT,
                SettingsSearchId.KEEP_ALIVE_TIMEOUT,
            ),
            visibleIds(SettingsSearchSection.TRANSCRIPTION, state(isLlmBackend = true, isModelLoaded = true)))
    }

    @Test
    fun `condition flags flip exactly their own cards`() {
        val base = visibleIds(SettingsSearchSection.TRANSCRIPTION, state())

        // Each flip adds exactly one card, in its tree position, leaving
        // every other card's presence untouched.
        fun assertSingleAddition(flipped: List<SettingsSearchId>, added: SettingsSearchId, after: SettingsSearchId) {
            assertEquals(base.size + 1, flipped.size)
            assertEquals(base, flipped.filter { it != added })
            // Review F5: a head-of-section addition gives indexOf == -1;
            // assert on position rather than crash opaquely.
            val at = flipped.indexOf(added)
            assertTrue("added card not found in flipped list", at >= 0)
            if (at > 0) assertEquals(after, flipped[at - 1])
        }
        assertSingleAddition(
            visibleIds(SettingsSearchSection.TRANSCRIPTION, state(punctuationPromptForced = true)),
            SettingsSearchId.PUNCTUATION_PROMPT, SettingsSearchId.PUNCTUATION_MODE,
        )
        assertSingleAddition(
            visibleIds(SettingsSearchSection.TRANSCRIPTION, state(summarizeOn = true)),
            SettingsSearchId.SUMMARY_PROMPT, SettingsSearchId.SUMMARIZE,
        )
        assertSingleAddition(
            visibleIds(SettingsSearchSection.TRANSCRIPTION, state(speakerIdEnabled = true)),
            SettingsSearchId.SPEAKER_IDENTITIES, SettingsSearchId.SPEAKER_LABELS,
        )
    }

    @Test
    fun `the model status vocabulary carries only the live title`() {
        assertEquals(
            listOf(R.string.model_not_loaded),
            res(SettingsSearchId.MODEL_STATUS, state(isLlmBackend = true, isModelLoaded = false)),
        )
        assertEquals(
            listOf(R.string.model_loaded),
            res(SettingsSearchId.MODEL_STATUS, state(isLlmBackend = true, isModelLoaded = true)),
        )
    }

    @Test
    fun `the transcription language vocabulary carries only the hint that renders`() {
        assertEquals(
            listOf(R.string.transcription_language_title),
            res(SettingsSearchId.TRANSCRIPTION_LANGUAGE, state()),
        )
        assertEquals(
            listOf(R.string.transcription_language_title, R.string.transcription_language_forced_hint),
            res(
                SettingsSearchId.TRANSCRIPTION_LANGUAGE,
                state(transcriptionHintRes = R.string.transcription_language_forced_hint),
            ),
        )
    }

    @Test
    fun `appearance vocabularies match the tree`() {
        assertEquals(
            listOf(
                SettingsSearchId.THEME,
                SettingsSearchId.APP_ICON,
                SettingsSearchId.APP_LANGUAGE,
                SettingsSearchId.SWIPE_ACTION,
                SettingsSearchId.CONVERSATION_GROUPING,
                SettingsSearchId.COMPACT_RESULT_ACTIONS,
                SettingsSearchId.TECHNICAL_DETAILS,
                SettingsSearchId.LANGUAGE_CHIP,
                SettingsSearchId.RETRANSCRIBE,
            ),
            visibleIds(SettingsSearchSection.APPEARANCE, state()),
        )
        // TASK-689: text size joined the theme vocabulary (TASK-576 had it
        // in the gate only; "text size" queries reported 0 matches).
        assertEquals(
            listOf(
                R.string.theme_title, R.string.theme_description,
                R.string.theme_mode_title, R.string.theme_mode_description,
                R.string.text_size_title, R.string.text_size_description,
            ),
            res(SettingsSearchId.THEME, state()),
        )
    }

    @Test
    fun `advanced vocabularies gain the battery card after a background kill`() {
        val base = visibleIds(SettingsSearchSection.ADVANCED, state())
        assertEquals(
            listOf(
                SettingsSearchId.HUGGINGFACE_AUTH,
                SettingsSearchId.THREAD_COUNT,
                SettingsSearchId.INFERENCE_PROVIDER,
                SettingsSearchId.SHARE_TARGETS,
                SettingsSearchId.SUBTITLE_TIMEOUT,
                SettingsSearchId.MEMORY_PROTECTION,
                SettingsSearchId.EXTERNAL_AUTOMATION,
                SettingsSearchId.AUTOMATION_GUIDE,
                SettingsSearchId.REMOTE_OFFLOAD,
                SettingsSearchId.PER_APP_SETTINGS,
                SettingsSearchId.PERFORMANCE_STATS,
                SettingsSearchId.MEMORY_DIAGNOSTICS,
            ),
            base,
        )
        assertEquals(
            listOf(SettingsSearchId.BATTERY_EXEMPTION) + base,
            visibleIds(SettingsSearchSection.ADVANCED, state(batteryExemptionOffered = true)),
        )
    }

    @Test
    fun `feedback is one entry carrying the whole card`() {
        assertEquals(
            listOf(SettingsSearchId.FEEDBACK),
            visibleIds(SettingsSearchSection.FEEDBACK, state()),
        )
        assertEquals(
            listOf(
                R.string.settings_feedback_send_title, R.string.settings_feedback_version_title,
                R.string.settings_feedback_license_title, R.string.settings_feedback_source_title,
                R.string.settings_feedback_translation_title,
                R.string.settings_replay_tour, R.string.settings_feedback_privacy_note,
                R.string.faq_section_title, R.string.faq_card_calls_title,
                R.string.faq_card_models_title, R.string.faq_card_queue_title,
                R.string.faq_card_results_title, R.string.faq_card_trouble_title,
                R.string.faq_full_link,
            ),
            res(SettingsSearchId.FEEDBACK, state()),
        )
    }
}
