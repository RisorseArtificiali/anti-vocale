# UI String Guide: Terminology and Style

Coverage: `values-ru/strings.xml` in full; semantic edits mirrored
to all locales (`values`, de, es, fr, hi, it, pl, pt-rBR, tr, uk).
Commits: `8a3f8db` (value declutter), `e9b8496` (terms n stuff) + subsequent passes.

## Reasons for edits

1. **Data duplication.** Language names were manually maintained in 11 locales
(~62 entries per file), although the platform returns them via ICU. Replaced with
`util/LanguageNames.kt`; ~680 entries removed from resources.
2. **Terminological inconsistency.** After renaming tabs and changing the primary
term, files retained some of the old words ("Log cleared," "log entries," "enable background transcription").
3. **Descriptions duplicated titles.** In the settings, the card is rendered as
"bold title + gray description"; some descriptions verbatim
repeated the title ("Show the Share button" under the "Show the
Share button") or paraphrased it in the same words.
4. **Tone inconsistency.** Some lines were official-sounding ("ONNX inference backend"), while others were conversational with dashes ("More is faster, but it heats up the phone").

## New Terminology

| Before | After | Rule |
|------|--------|--------|
| Transcript | **Recognition** | Only for PROCESS: "Recognition in Progress", "Recognition Language" |
| Transcription | Removed | Anglicism, contradicted native speaker feedback (issue #36) |
| Recognition Result | **Text**, **Result**, **Record** | The result word depends on the context (see below) |
| Log | **History** | Tab name; in all derived strings |
| Recent Queries | **Recent Posts** | History entries are not "queries" |
| Share targets | **Models in the Share menu** | The term "share target" has been removed as a tracing |
| Transducer | **Transducer** | in Latin; the Cyrillic transliteration was read as "transudation" |
| Sberbank | Sber | Colloquial norm, only in ru |

### Process ≠ Result

The key distinction that led to this process:

- **Process** is always "recognition": statuses, services, settings.
("Recognition complete", "No recognition model", "Recognition in progress").
- **Result** is a specific word in context:
- actions with it — "text" ("Copy text", "Share text",
"Text copied", "Send text back");
- content label — "Result" (clipboard label);
- list items — "records" ("Search for records...", "Group records by
application", "Record fragment").

This eliminates the cumbersome "Report recognition" and "Send
recognition back to the original application" views.

## Formulation Principles

1. **Title — a short noun.** "Auto-copy," "Auto-upload,"
"Default query," "Auto-save to folder." No verbs or qualifiers,
which will be included in the description anyway.
2. **The description does not repeat the title.** It answers the question "what does this do /
when will it work" and does not use words from the title twice.
3. **Calm, complete syntax.** No dashes or appositives ("More is faster"),
no telegraphic truncations, no colloquialisms
("take a smaller model," "in a couple of seconds"). Settings are described the same way
as in the system Settings.
4. **One thought — one sentence.** A second sentence is only allowed for a
consequence or limitation ("...Larger values ​​speed up processing and increase
the load"). 5. **Examples are in parentheses, without unnecessary gender cases:** "(for example, Drive or
Syncthing)".

### Before / After

| Before | After |
|------|-------|
| Auto-copy result / Automatically copy recognition results to the clipboard. You can also copy them manually from a notification. | Auto-copy / Copy completed text to the clipboard. Manual copying from a notification is also available. |
| Auto-upload timeout / Automatically unload the model after a period of inactivity to free up memory. The model will be automatically loaded again if needed. | Auto-upload / The model is unloaded after inactivity and returned the next time it is used. |
| Report recognition | Report text error |
| Number of processor threads for recognition and voice activity detection. Larger values ​​increase the load on the processor but can speed up processing. | Number of processor threads for recognition. Larger values ​​speed up processing and increase the load. |
| Share Targets / Show each downloaded model as a separate item in the Share system menu. When transmitting audio through a specific model, it is used temporarily, only for this recognition. | Models in the Share menu / Each downloaded model is displayed as a separate item in the system menu. The selected model processes the audio. |

## Language Names: Endonym Policy

The `lang_*` strings have been removed; language names are taken from ICU (`util/LanguageNames.kt`)
and are always shown natively** — "Deutsch", "Русский", "हिन्दी" — regardless of the
interface language.