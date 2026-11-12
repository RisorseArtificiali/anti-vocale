package com.antivocale.app.transcription

/**
 * TASK-993: named presets over the Gemma summary pass. The stored preset
 * KEY is the single source of truth: it decides which instruction runs
 * ([SummaryPolicy.instructionLadder]) and what the Settings picker shows.
 * A stored custom prompt is DORMANT while a non-custom preset is active
 * (switching back to Custom restores it; choosing a preset never deletes
 * user text). Legacy pre-preset users (a prompt with no preset key) are
 * promoted to CUSTOM by the one-shot [com.antivocale.app.data.PreferencesManager.reconcileLegacySummaryPrompt]
 * migration, not by a runtime arbitration rule. The preset's INSTRUCTION
 * text is locale-resolved at generation time, so it never freezes the
 * locale into storage and never collides with the custom-vs-built-in
 * comparison the retry ladder depends on.
 */
enum class SummaryPreset(val key: String) {
    /** The built-in two-to-three-sentence recap (the pre-preset behavior). */
    SUMMARY("summary"),
    ACTION_ITEMS("action_items"),
    MEETING_MINUTES("meeting_minutes"),
    /** The free-text prompt field; blank falls back to the built-in. */
    CUSTOM("custom");

    companion object {
        fun fromKey(key: String?): SummaryPreset =
            entries.firstOrNull { it.key == key } ?: SUMMARY
    }
}
