# Plural rules for values-ar

Extracted 2026-10-06 from values/strings.xml (1008 strings, 13 plural keys).

CLDR cardinal categories for ar: **zero, one, two, few, many, other**

Every plural key in the package must supply exactly these quantities:

- `logs_entries_deleted`: `zero`, `one`, `two`, `few`, `many`, `other`
- `settings_search_matches`: `zero`, `one`, `two`, `few`, `many`, `other`
- `transcription_partial`: `zero`, `one`, `two`, `few`, `many`, `other`
- `queued_count`: `zero`, `one`, `two`, `few`, `many`, `other`
- `timeout_minutes`: `zero`, `one`, `two`, `few`, `many`, `other`
- `performance_stats_samples_count`: `zero`, `one`, `two`, `few`, `many`, `other`
- `interrupted_runs_text`: `zero`, `one`, `two`, `few`, `many`, `other`
- `interrupted_runs_oom_text`: `zero`, `one`, `two`, `few`, `many`, `other`
- `model_info_languages_count`: `zero`, `one`, `two`, `few`, `many`, `other`
- `model_info_max_audio_seconds`: `zero`, `one`, `two`, `few`, `many`, `other`
- `conversation_group_count`: `zero`, `one`, `two`, `few`, `many`, `other`
- `subtitle_import_skipped_cues`: `zero`, `one`, `two`, `few`, `many`, `other`
- `folder_watch_period_value`: `zero`, `one`, `two`, `few`, `many`, `other`

Lint traps that can bite THIS locale:

- ar demands ALL SIX categories; a missing one fails MissingQuantity
- ar (like iw) MUST provide a 'two' form or MissingQuantity fails
