# Localization review follow-ups

Extracted from `<!-- review -->` comments in values-de/ru/hi after the first-pass merge (2026-08-19).
These are the strings a native speaker should double-check; the rest of the ~750 strings per language are standard translations using the AOSP glossary.

## DE (9 items)

- `transcription_partial`: "chunk(s)" has no clean German singular/plural-neutral form; phrased with plural
- `progressive_initial`: EN uses an em-dash here; replaced with comma per project rule
- `output_folder_clear`: "Leeren" is the glossary Clear term; "Ordner entfernen" may fit better here
- `vad_title`: store listing uses "Stille-Entfernung"; heading kept short with VAD in parentheses
- `example_prompt_translate_it`: intentionally left in Italian; it is an example prompt sent to the model for Italian output
- `per_app_settings_quick_share_back_title`: proper German would be "Zurückteilen"; kept literal "Schnell-Zurückteilen"? final choice below
- `performance_stats_fastest_badge`: plural-shaped; reads odd at 1 sample
- `lang_filter_label`: RESOLVED 2026-08-25, obsoleted by the PR #66 ICU switch: the lang_* name strings no longer exist (names come from the platform), and the label itself is translated chrome ("Nach Sprache filtern").
- `conversation_group_unknown`: plural-shaped

## RU (5 items)

- `backend_model_ready`: gender agreement assumes модель (feminine); for masculine model names this reads wrong. Consider "%1$s: готово".
- `settings_section_transcription`: RESOLVED by PR #66 (2026-08-25): neither glossary candidate survived; Dum4G's RU rework dropped the anglicism and uses "распознавание" for the process (see glossaries.md, superseded row, and the [style guide](https://gist.github.com/Dum4G/32157ff0a733881d63b8b312c7936584)).
- `example_prompt_transcribe`: prompts are sent to the LLM; used the imperative "ты"-style wording typical for LLM prompts. Review for tone. (Reworded in PR #66 to the распознавание-family verb; tone flag still open.)
- `performance_stats_samples_count`: "замер" chosen for "sample" (measurement); "образец" is the literal alternative.
- `lang_filter_label`: RESOLVED 2026-08-25, same as the DE item: lang_* names removed by the ICU switch, label is translated chrome.

## HI (10 items, from the translation agent's report; not embedded as XML comments)

- `settings_section_appearance`: दिखावट chosen; AOSP sometimes uses रंगरूप
- `transcription_partial_chip`: अधूरा (colloquial) vs अपूर्ण (formal)
- `per_app_settings_sound_chime`: छनक (onomatopoeic) vs चाइम (loanword)
- `swipe_action_reveal`: कार्रवाइयां दिखाएं vs विकल्प दिखाएं
- `logs_empty_hint`: long sentence, word order and register need checking
- `force_model_load`: ज़बरदस्ती is colloquial; बलपूर्वक is formal but stilted for UI
- `gemma_advanced_features_description`: "enrichment" translated as समृद्धि; may deserve the English loanword
- `example_prompt_formal`: "filler words / false starts" approximated as भराव शब्द और अधूरी शुरुआतें
- `vad_title`: "Strip Silence" as मौन हटाएं; AOSP-equivalent wording not verified
- `retranscribe_title`: "Transcribe with..." model-picker nuance is awkward in Hindi

Also worth a look: `conversation_grouping_title` and `share_back` (app-specific coinages, no platform precedent).

