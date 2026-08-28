# Localization glossaries for TASK-337/338/339 (de / ru / hi)

Source of truth for translations: AOSP Settings app localized strings, fetched live
2026-08-19 from android.googlesource.com (platform/packages/apps/Settings, main branch,
res/values-de / values-ru / values-hi strings.xml). This is the strongest available
authority for "what the Android platform itself uses" in these languages. Microsoft
Style Guides were used as cross-checks where noted; conflicts are flagged. Terms not
present verbatim in AOSP Settings (e.g. "Undo", "Download" as a standalone button)
are labeled accordingly. Per coordinator direction, AOSP/Android conventions take
PRECEDENCE: each glossary row carries a source column, [ANDROID] (verified against
AOSP translations / Android platform UI / Play Store wording) or [FALLBACK: MS/Apple]
(used only where Android does not clearly settle the term); platform disagreements are
noted in the row.

## 1. APP INVENTORY (generic terms from app/src/main/res/values/strings.xml)

~30 most generic keys (verified present in the file):

| Concept | String key(s) | en text |
|---|---|---|
| Settings | `settings_tab` | Settings |
| Cancel | `action_cancel`, `logs_cancel`, `benchmark_cancel`, `cancel_download` | Cancel / Cancel Download |
| Confirm/OK | `logs_confirm` | Confirm |
| Done | `action_understood`, `per_app_onboarding_got_it` | Understood / Got it |
| Save | `save`, `validate_and_save` | Save / Validate & Save |
| Delete | `delete`, `action_delete`, `logs_delete_entry` | Delete |
| Share | `share`, `share_transcription` | Share |
| Copy | `copy`, `copy_transcription` | Copy / Copy to Clipboard |
| Download | `download`, `cancel_download` | Download / Cancel Download |
| Retry | `retry`, `benchmark_retry`, `retranscribe` | Retry |
| Back | `back` (content description) | Back |
| Next/Prev | `chunk_nav_prev`, `chunk_nav_next` (arrow glyphs) | navigation arrows |
| Advanced | `settings_section_advanced`, `model_advanced_section` | Advanced |
| On/Off | `per_app_settings_auto_copy_on/off`, `share_on/off` | Auto-copy on / off |
| Search | `logs_search_placeholder`, `per_app_settings_search`, `lang_filter_search` | Search transcriptions... / Search apps... |
| Error | `status_error`, `logs_error_label` | Error |
| Loading | `status_loading`, `loading_model`, `per_app_settings_loading` | Loading Model... |
| Model | `model_tab`, `active_model` | Model |
| Transcription | `settings_section_transcription`, `clipboard_label_transcription` | Transcription |
| Notification | `notification_channel_*` family | (channel names) |
| Language | `language_title`, `transcription_language_title` | Language |
| Open | `notification_no_model_action` | Open app |
| Close | `per_app_settings_close`, `model_info_close` | Close |
| Send | `send_to_app` | Send to %1$s |
| Undo | `logs_undo` | Undo |
| Clear | `logs_clear`, `download_clear_partial`, `lang_filter_clear` | Clear |
| Yes/No | `label_yes_multimodal` / `label_no_text_only` | Yes (multimodal) / No (text only) |
| Update | `model_update_button`, `gemma_update_confirm_title` | Update |
| View | `view`, `use_model` | View / Use Model |
| Dismiss | `dismiss`, `action_dismiss` | Dismiss |
| Login/Logout | `login_with_huggingface`, `logout` | Login with HuggingFace / Logout |
| Show more/less | `show_more`, `show_less` | Show more / Show less |
| Select all / none | `logs_select_all`, `logs_deselect_all` | Select all / Deselect all |
| Today/Yesterday | `today`, `yesterday` | Today / Yesterday |
| Extract/Import | `extract_model`, `external_import` | Extract / Import |

NOT included (app-specific, human reviewers): model names (Parakeet/Whisper/Gemma...),
model descriptions, benchmark quality words, VAD wording, HuggingFace flow sentences,
example prompts, error sentences, per-app settings prose.

## 2. GLOSSARY - GERMAN (values-de/strings.xml)

Verified against AOSP Settings values-de (fetched 2026-08-19).

| en term | our string key | canonical translation | source | source note |
|---|---|---|---|---|
| Settings | `settings_tab` | Einstellungen | [ANDROID] | AOSP settings_label; universal across all platforms |
| Cancel | `action_cancel` | Abbrechen | [ANDROID] | AOSP cancel |
| Confirm/OK | `logs_confirm` | Bestätigen (OK = "OK") | [ANDROID] | AOSP okay = "OK"; Confirm is Bestätigen (MS style guide agrees) |
| Save | `save` | Speichern | [ANDROID] | AOSP save |
| Delete | `delete` | Löschen | [ANDROID] | AOSP delete |
| Share | `share` | Teilen | [ANDROID] | AOSP share. CONFLICT: Microsoft uses "Freigeben" for file-sharing contexts; Android and modern consumer UI use "Teilen". Pick Teilen. |
| Copy | `copy` | Kopieren | [ANDROID] | platform convention (MS agrees) |
| Copy to clipboard | `copy_transcription` | In die Zwischenablage kopieren | [FALLBACK: MS/Apple] | standard platform phrase |
| Download | `download` | Herunterladen | [ANDROID] | AOSP/Play Store convention (not in Settings verbatim; cross-checked MS guide) |
| Retry | `retry` | Erneut versuchen | [ANDROID] | AOSP retry = "Noch mal versuchen"; the shorter "Erneut versuchen" is the common app convention (Play/Chrome). Prefer it for button brevity. |
| Back | `back` | Zurück | [ANDROID] | universal |
| Advanced | `settings_section_advanced` | Erweitert | [ANDROID] | AOSP uses "Erweiterte ..." in compound titles |
| Error | `status_error` | Fehler | [ANDROID] | universal |
| Loading... | `status_loading` | Wird geladen... | [ANDROID] | platform convention |
| Model | `model_tab` | Modell | [FALLBACK: MS/Apple] | standard noun; German capitalizes nouns |
| Transcription | `settings_section_transcription` | Transkription | [FALLBACK: MS/Apple] | standard |
| Notification | `notification_channel_*` | Benachrichtigung | [ANDROID] | AOSP notification_* strings |
| Language | `language_title` | Sprache | [ANDROID] | AOSP language_picker_title "Sprachen" |
| Open app | `notification_no_model_action` | App öffnen | [FALLBACK: MS/Apple] | standard verb-second order for buttons |
| Close | `per_app_settings_close` | Schließen | [ANDROID] | universal |
| Send to %s | `send_to_app` | An %1$s senden | [ANDROID] | Android share-sheet pattern |
| Undo | `logs_undo` | Rückgängig | [ANDROID] | universal (not in AOSP Settings verbatim; cross-checked MS/Chrome) |
| Clear | `logs_clear` | Leeren | [ANDROID] | AOSP clear_cache = "Cache leeren". Use "Leeren" for the logs list. |
| Yes / No | `label_yes_multimodal` | Ja / Nein | [ANDROID] | AOSP yes/no |
| Update | `model_update_button` | Aktualisieren | [ANDROID] | platform convention |
| View | `view` | Anzeigen | [ANDROID] | the more common Android label (Ansehen also seen) |
| Dismiss | `dismiss` | Ausblenden | [ANDROID] | notification Dismiss = "Ausblenden" in AOSP notifications |
| Logout | `logout` | Abmelden | [ANDROID] | universal |
| Show more / less | `show_more` | Mehr anzeigen / Weniger anzeigen | [ANDROID] | platform convention |
| Select all / Deselect | `logs_select_all` | Alle auswählen / Auswahl aufheben | [ANDROID] | platform convention |
| Search | `logs_search_placeholder` | Suchen | [ANDROID] | AOSP search_settings = "Suche"; verb "Suchen" for placeholder |
| Today / Yesterday | `today` | Heute / Gestern | [ANDROID] | universal |
| Got it | `per_app_onboarding_got_it` | Verstanden | [FALLBACK: MS/Apple] | matches our `action_understood` |

## 3. GLOSSARY - RUSSIAN (values-ru/strings.xml)

Verified against AOSP Settings values-ru.

| en term | our string key | canonical translation | source | source note |
|---|---|---|---|---|
| Settings | `settings_tab` | Настройки | [ANDROID] | AOSP settings_label |
| Cancel | `action_cancel` | Отмена | [ANDROID] | AOSP cancel |
| Confirm/OK | `logs_confirm` | Подтвердить (OK = "ОК") | [ANDROID] | AOSP okay = "ОК" |
| Save | `save` | Сохранить | [ANDROID] | AOSP save |
| Delete | `delete` | Удалить | [ANDROID] | AOSP delete |
| Share | `share` | Поделиться | [ANDROID] | AOSP share |
| Copy | `copy` | Копировать | [ANDROID] | platform convention, MS agrees |
| Copy to clipboard | `copy_transcription` | Скопировать в буфер обмена | [FALLBACK: MS/Apple] | standard phrase |
| Download | `download` | Скачать | [ANDROID] | Play Store / Android convention. CONFLICT: MS style guide prefers "Загрузить"; consumer Android UI uses "Скачать". Pick Скачать. |
| Retry | `retry` | Повторить попытку | [ANDROID] | AOSP retry; shorter "Повторить" acceptable for buttons |
| Back | `back` | Назад | [ANDROID] | universal |
| Advanced | `settings_section_advanced` | Дополнительно (section) / Расширенные настройки | [ANDROID] | AOSP uses "Расширенные" in compound titles; standalone section commonly "Дополнительно" |
| Error | `status_error` | Ошибка | [ANDROID] | universal |
| Loading... | `status_loading` | Загрузка... | [ANDROID] | platform convention |
| Model | `model_tab` | Модель | [FALLBACK: MS/Apple] | standard |
| Transcription | `settings_section_transcription` | Распознавание | [SUPERSEDED by PR #66] | Original pick was "Транскрипция" (AOSP-canonical), first replaced by "расшифровка" on 2026-08-20 per issue #36 (commit 562a179), then by Dum4G's RU rework in PR #66 (2026-08-25): the anglicism was dropped; the PROCESS is always "распознавание" (statuses, services, settings), and the RESULT is context-specific ("текст" for actions, "результат" for the clipboard label, "записи" for history items). Full reasoning in Dum4G's [strings style guide](https://gist.github.com/Dum4G/32157ff0a733881d63b8b312c7936584). |
| Notification | `notification_channel_*` | Уведомление / Уведомления | [ANDROID] | AOSP notification_* |
| Language | `language_title` | Язык | [ANDROID] | AOSP language_picker_title "Языки" |
| Open app | `notification_no_model_action` | Открыть приложение | [FALLBACK: MS/Apple] | standard |
| Close | `per_app_settings_close` | Закрыть | [ANDROID] | universal |
| Send to %s | `send_to_app` | Отправить в %1$s | [ANDROID] | Android share-sheet pattern |
| Undo | `logs_undo` | Отменить | [ANDROID] | universal |
| Clear | `logs_clear` | Очистить | [ANDROID] | AOSP clear_cache = "Очистить кеш" |
| Yes / No | `label_yes_multimodal` | Да / Нет | [ANDROID] | AOSP yes/no |
| Update | `model_update_button` | Обновить | [ANDROID] | platform convention |
| View | `view` | Показать | [FALLBACK: MS/Apple] | "Показать" for buttons; "Просмотр" for noun labels |
| Dismiss | `dismiss` | Отклонить | [ANDROID] | AOSP notification dismiss convention |
| Logout | `logout` | Выйти | [ANDROID] | universal |
| Show more / less | `show_more` | Показать больше / Показать меньше | [ANDROID] | platform convention |
| Select all / Deselect | `logs_select_all` | Выбрать все / Снять выделение | [ANDROID] | platform convention |
| Search | `logs_search_placeholder` | Поиск | [ANDROID] | AOSP search_settings = "Поиск" |
| Today / Yesterday | `today` | Сегодня / Вчера | [ANDROID] | universal |
| Got it | `per_app_onboarding_got_it` | Понятно | [FALLBACK: MS/Apple] | common Android onboarding wording |

## 4. GLOSSARY - HINDI (values-hi/strings.xml)

Verified against AOSP Settings values-hi. Hindi keeps many English loanwords in
Devanagari transliteration (सेटिंग, डाउनलोड, शेयर, स्टोरेज); this IS the platform
convention, not a fallback.

| en term | our string key | canonical translation | source | source note |
|---|---|---|---|---|
| Settings | `settings_tab` | सेटिंग | [ANDROID] | AOSP settings_label (loanword is canonical) |
| Cancel | `action_cancel` | रद्द करें | [ANDROID] | CONFLICT WITHIN AOSP: generic `cancel` = "रहने दें", but the dialog cancel button = "रद्द करें". Use "रद्द करें" for our cancel actions (Cancel Download etc.); it is clearer. |
| Confirm/OK | `logs_confirm` | पुष्टि करें (OK = "ठीक है") | [ANDROID] | AOSP okay = "ठीक है" |
| Save | `save` | सेव करें | [ANDROID] | AOSP save (loanword canonical) |
| Delete | `delete` | मिटाएं | [ANDROID] | AOSP delete |
| Share | `share` | शेयर करें | [ANDROID] | AOSP share (loanword canonical; "साझा करें" is the formal government variant, do NOT use it, platforms don't) |
| Copy | `copy` | कॉपी करें | [ANDROID] | platform convention |
| Copy to clipboard | `copy_transcription` | क्लिपबोर्ड पर कॉपी करें | [FALLBACK: MS/Apple] | standard phrase |
| Download | `download` | डाउनलोड करें | [ANDROID] | Play Store convention (loanword canonical) |
| Retry | `retry` | फिर से कोशिश करें | [ANDROID] | AOSP retry |
| Back | `back` | वापस | [ANDROID] | universal |
| Advanced | `settings_section_advanced` | अतिरिक्त सेटिंग | [ANDROID] | AOSP Hindi style; "उन्नत" is MS-style. Flag for reviewer. |
| Error | `status_error` | गड़बड़ी | [ANDROID] | AOSP Hindi uses "गड़बड़ी" for errors (friendlier than formal "त्रुटि") |
| Loading... | `status_loading` | लोड हो रहा है... | [ANDROID] | platform convention |
| Model | `model_tab` | मॉडल | [FALLBACK: MS/Apple] | loanword |
| Transcription | `settings_section_transcription` | ट्रांसक्रिप्शन | [ANDROID] | CONFLICT: AOSP uses "लिप्यंतरण" for speech transcription; the loanword is common in tech UI. Recommend the loanword for consistency; flag for reviewer. |
| Notification | `notification_channel_*` | सूचना / सूचनाएं | [ANDROID] | AOSP notification_* = "सूचना" (NOT नोटिफ़िकेशन) |
| Language | `language_title` | भाषा | [ANDROID] | AOSP language_picker_title "भाषाएं" |
| Open app | `notification_no_model_action` | ऐप खोलें | [FALLBACK: MS/Apple] | platform pattern (ऐप, not एप) |
| Close | `per_app_settings_close` | बंद करें | [ANDROID] | universal |
| Send to %s | `send_to_app` | %1$s को भेजें | [FALLBACK: MS/Apple] | postposition "को" after the placeholder |
| Undo | `logs_undo` | पूर्ववत करें | [ANDROID] | platform convention (Chrome uses this) |
| Clear | `logs_clear` | साफ़ करें | [ANDROID] | AOSP clear_uri = "ऐक्सेस साफ़ करें"; note the nukta on फ़ |
| Yes / No | `label_yes_multimodal` | हां / नहीं | [ANDROID] | AOSP yes/no |
| Update | `model_update_button` | अपडेट करें | [ANDROID] | loanword canonical |
| View | `view` | देखें | [ANDROID] | universal |
| Dismiss | `dismiss` | खारिज करें | [ANDROID] | AOSP notification dismiss convention |
| Logout | `logout` | लॉग आउट करें | [FALLBACK: MS/Apple] | loanword |
| Show more / less | `show_more` | ज़्यादा दिखाएं / कम दिखाएं | [ANDROID] | AOSP wording (note ज़ with nukta) |
| Select all / Deselect | `logs_select_all` | सभी चुनें / सभी को चुनने से हटाएं | [ANDROID] | platform convention |
| Search | `logs_search_placeholder` | खोजें | [ANDROID] | AOSP search_settings = "खोजें" |
| Today / Yesterday | `today` | आज / कल | [FALLBACK: MS/Apple] | "कल" covers both yesterday and tomorrow; it is the standard label |
| Got it | `per_app_onboarding_got_it` | समझ गया | [FALLBACK: MS/Apple] | common Android onboarding wording |

## 5. NOTES - Android strings.xml escaping and locale gotchas

General (applies to values-de/, values-ru/, values-hi/):
- Apostrophes: any ' in a string must be escaped \' or the whole string wrapped in
  double quotes. Our EN source already does this (setup_step1, dialog_delete_message,
  unsupported_audio_format, subtitle_fallback_status). German rarely needs it;
  Hindi/Russian essentially never (Hindi nukta characters are combining marks, not
  apostrophes; no escaping needed, just don't strip them).
- Preserve ALL format placeholders exactly: %1$s, %1$d, %1$.1f, %1$.0f, %%. Positional
  numbering must stay identical across translations; word order will move them and that
  is fine (that is why they are positional). Strings with literal % such as
  model_info_best_for_whisper_small ("4.3%% WER") must keep the doubled %%.
- Keep &amp; entities where the EN source has them (validate_and_save, preload_title).
- Ellipsis: literal "..." or the single character ... are both valid; match values-it
  style for consistency.

German (de):
- Nouns capitalized (Modell, Einstellungen, Fehler).
- CONFLICT, address form: Android/Google German uses informal "du"; Microsoft uses
  "Sie". Pick AOSP informal "du" for platform consistency and flag it for reviewers.
- Typographic quotes in prose: German low-high quotes ("..."), but straight quotes are
  acceptable in XML strings.

Russian (ru):
- Typography: use guillemets for quoted text, not straight quotes.
- PLURALS: Russian has 3 plural categories (one/few/many, plus other for fractions).
  Our strings.xml has ZERO <plurals> blocks (verified by grep), but it has plural-shaped
  strings that will read wrong in Russian if translated naively:
  logs_entries_deleted ("%d entries deleted"), model_info_languages_count ("%d languages"),
  model_info_max_audio_seconds ("%d seconds"), conversation_group_count ("%1$d messages"),
  timeout_minutes, performance_stats_samples_count, queued_count.
  RECOMMENDATION: convert these to <plurals> (at least for ru) or phrase them to avoid
  the plural (e.g. "Удалено записей: %d"). Flag in the tasks.

Hindi (hi):
- Hindi has a single plural form, so <plurals> are not grammatically required; the
  plural-shaped strings above still need natural phrasing (e.g. "%d संदेश").
- Use nukta forms consistently for loanwords: फ़ (फ़ाइल), ज़्यादा, डिफ़ॉल्ट. AOSP Hindi
  consistently uses nukta; missing nukta reads as a typo.
- Devanagari danda (।) is the sentence-ending punctuation for full sentences, not the
  Latin period. Buttons and labels carry no punctuation.
- AOSP Hindi uses "ऐप" for app (not एप), "सेटिंग" (not सेटिंग्स).

Cross-cutting:
- values-it/ already exists; follow the same structure (full translated copy of
  strings.xml, partial overrides are legal but a full copy keeps diffs obvious).
- Leave UNTRANSLATED: app_name, model names (parakeet_title etc.), token_placeholder
  ("hf_..."), chunk_nav_prev/next glyphs, download_status_file_progress ("%1$d/%2$d"),
  speed_comparison size/speed values.
- lang_* entries: in each new locale file, language names stay in their native form
  (German = "Deutsch", Russian = "Русский", Hindi = "हिन्दी"; Italian stays "Italiano").
