package com.antivocale.app.ui.tabs

import com.antivocale.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * TASK-731: the sub-group membership the tree's SettingsGroupLabel
     * renders. A card moving between groups (or into no group) must
     * consciously update this map; a wrong member ships a header over
     * cards it does not own. The registry sits in tree order, so the
     * contiguity and single-section pins keep every cluster under one
     * header in one section.
     */
    @Test
    fun `group membership matches the tree clusters`() {
        val members = mapOf(
            SettingsSearchGroup.DECODING to listOf(
                SettingsSearchId.VAD, SettingsSearchId.PROGRESSIVE,
                SettingsSearchId.EARLY_PREVIEW, SettingsSearchId.REFINEMENT),
            SettingsSearchGroup.GEMMA_TEXT to listOf(
                SettingsSearchId.PUNCTUATION_MODE, SettingsSearchId.PUNCTUATION_PROMPT,
                SettingsSearchId.SUMMARIZE, SettingsSearchId.SUMMARY_PROMPT,
                SettingsSearchId.DEFAULT_PROMPT),
            SettingsSearchGroup.OUTPUT to listOf(
                SettingsSearchId.AUTO_COPY, SettingsSearchId.EXPORT_SETTINGS, SettingsSearchId.SIGNATURE),
            SettingsSearchGroup.LOOK_AND_FEEL to listOf(
                SettingsSearchId.THEME, SettingsSearchId.APP_ICON, SettingsSearchId.APP_LANGUAGE),
            SettingsSearchGroup.HISTORY to listOf(
                SettingsSearchId.SWIPE_ACTION, SettingsSearchId.CONVERSATION_GROUPING,
                SettingsSearchId.COMPACT_RESULT_ACTIONS, SettingsSearchId.TECHNICAL_DETAILS,
                SettingsSearchId.LANGUAGE_CHIP, SettingsSearchId.RETRANSCRIBE),
            SettingsSearchGroup.INTEGRATIONS to listOf(
                SettingsSearchId.SHARE_TARGETS, SettingsSearchId.SUBTITLE_TIMEOUT, SettingsSearchId.AUTOMATION_HUB,
                SettingsSearchId.VOICE_NOTE_IDENTITY, SettingsSearchId.SHARE_SHORTCUT_ICONS),
        )
        val registryOrder = SETTINGS_SEARCH_CARDS.map { it.id }
        SettingsSearchGroup.entries.forEach { g ->
            val grouped = SETTINGS_SEARCH_CARDS.filter { it.group == g }
            assertEquals("group $g membership", members.getValue(g), grouped.map { it.id })
            assertEquals("group $g stays in one section", 1, grouped.map { it.section }.distinct().size)
            val positions = members.getValue(g).map { registryOrder.indexOf(it) }
            assertEquals("group $g is one contiguous tree run", positions.max() - positions.min(), positions.size - 1)
        }
    }

    /**
     * TASK-731: group labels enter search ONLY through membership: no
     * card's own vocabulary contains a group label, so a query naming a
     * group matches exactly the cards its registry membership groups.
     */
    @Test
    fun `group labels enter search only through membership`() {
        val labels = SettingsSearchGroup.entries.map { it.labelRes }.toSet()
        SETTINGS_SEARCH_CARDS.forEach { card ->
            assertTrue(
                "card ${card.id} lists a group label in its own res()",
                card.res(state()).none { it in labels },
            )
        }
    }

    /**
     * TASK-731: the orphan-header net, on the real derivation the tree
     * renders. GEMMA_TEXT is the one group whose members are all gated;
     * its header must disappear exactly when every member does.
     */
    @Test
    fun `the gemma group header hides when all its members do`() {
        assertEquals(false, groupHasVisibleMember(SettingsSearchGroup.GEMMA_TEXT, state(gemmaConfigured = false)))
        assertEquals(true, groupHasVisibleMember(SettingsSearchGroup.GEMMA_TEXT, state(gemmaConfigured = true)))
        assertEquals(true, groupHasVisibleMember(SettingsSearchGroup.GEMMA_TEXT, state(isLlmBackend = true, isModelLoaded = true)))
    }

    @Test
    fun `transcription vocabularies with gemma on a non-llm backend`() {
        // The 2026-10-01 sub-grouping order: model cluster, decoding and
        // preview, Gemma text processing, speakers, output, then the
        // service rows (the registry order IS the tree order; TASK-689).
        assertEquals(
            listOf(
                SettingsSearchId.ACTIVE_MODEL,
                SettingsSearchId.TRANSCRIPTION_LANGUAGE,
                SettingsSearchId.VAD,
                SettingsSearchId.PROGRESSIVE, SettingsSearchId.EARLY_PREVIEW,
                SettingsSearchId.REFINEMENT,
                SettingsSearchId.PUNCTUATION_MODE,
                SettingsSearchId.SUMMARIZE,
                SettingsSearchId.DIARIZATION_HUB,
                SettingsSearchId.AUTO_COPY,
                SettingsSearchId.EXPORT_SETTINGS,
                SettingsSearchId.SIGNATURE,
                SettingsSearchId.INTERRUPTED_RUN_NOTIFICATIONS,
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
                SettingsSearchId.VAD,
                SettingsSearchId.PROGRESSIVE, SettingsSearchId.EARLY_PREVIEW,
                SettingsSearchId.REFINEMENT,
                SettingsSearchId.DIARIZATION_HUB,
                SettingsSearchId.AUTO_COPY,
                SettingsSearchId.EXPORT_SETTINGS,
                SettingsSearchId.SIGNATURE,
                SettingsSearchId.INTERRUPTED_RUN_NOTIFICATIONS,
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
                SettingsSearchId.VAD,
                SettingsSearchId.PROGRESSIVE, SettingsSearchId.EARLY_PREVIEW,
                SettingsSearchId.REFINEMENT,
                SettingsSearchId.SUMMARIZE,
                // (the identities card lives on the subpage now; its
                //  vocabulary rides the hub, state-gated.)
                SettingsSearchId.DEFAULT_PROMPT,
                SettingsSearchId.DIARIZATION_HUB,
                SettingsSearchId.AUTO_COPY,
                SettingsSearchId.EXPORT_SETTINGS,
                SettingsSearchId.SIGNATURE,
                SettingsSearchId.INTERRUPTED_RUN_NOTIFICATIONS,
                // SUMMARY_PROMPT is absent too: summarizeOn defaults false
                // in this state (its flip test covers the pair together).
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
        // 2026-09-30 regroup: the identities card moved to the diarization
        // subpage; the gate flip changes the hub's res() (the union test),
        // not the card set, so no addition here anymore.
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
        // The 2026-10-01 sub-grouping order: the kill-recovery offer, the
        // ungrouped head (performance, HuggingFace auth, per-app), then the
        // terminal Integrations cluster (share targets, subtitle choice,
        // automation).
        val base = visibleIds(SettingsSearchSection.ADVANCED, state())
        assertEquals(
            listOf(
                SettingsSearchId.PERFORMANCE_HUB,
                SettingsSearchId.HUGGINGFACE_AUTH,
                SettingsSearchId.PER_APP_SETTINGS,
                SettingsSearchId.SHARE_TARGETS,
                SettingsSearchId.SUBTITLE_TIMEOUT,
                SettingsSearchId.AUTOMATION_HUB,
                SettingsSearchId.VOICE_NOTE_IDENTITY,
                SettingsSearchId.SHARE_SHORTCUT_ICONS,
            ),
            base,
        )
        assertEquals(
            listOf(SettingsSearchId.BATTERY_EXEMPTION) + base,
            visibleIds(SettingsSearchSection.ADVANCED, state(batteryExemptionOffered = true)),
        )
    }

    @Test
    fun `the diarization hub carries the children's vocabulary, state-gated`() {
        val hub = SETTINGS_SEARCH_CARDS.first { it.id == SettingsSearchId.DIARIZATION_HUB }
        val base = hub.res(state())
        assertTrue(base.contains(R.string.speaker_labels_title))
        assertTrue(base.contains(R.string.speaker_settings_title))
        val gated = hub.res(state(speakerIdEnabled = true))
        assertTrue(gated.contains(R.string.speaker_id_title))
        assertFalse("gate off must not carry the identities vocabulary", base.contains(R.string.speaker_id_title))
    }

    @Test
    fun `the automation hub carries all three children's vocabulary`() {
        val hub = SETTINGS_SEARCH_CARDS.first { it.id == SettingsSearchId.AUTOMATION_HUB }
        val vocab = hub.res(state())
        assertTrue(vocab.contains(R.string.external_automation_title))
        assertTrue(vocab.contains(R.string.automation_guide_title))
        assertTrue(vocab.contains(R.string.remote_offload_title))
    }

    @Test
    fun `the performance hub carries all five children's vocabulary`() {
        val hub = SETTINGS_SEARCH_CARDS.first { it.id == SettingsSearchId.PERFORMANCE_HUB }
        val vocab = hub.res(state())
        assertTrue(vocab.contains(R.string.thread_count_title))
        assertTrue(vocab.contains(R.string.inference_provider_title))
        assertTrue(vocab.contains(R.string.memory_protection))
        assertTrue(vocab.contains(R.string.performance_stats_title))
        assertTrue(vocab.contains(R.string.memory_diagnostics_title))
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
