# Design study: basic vs advanced Settings (progressive disclosure of the grown surface)

**Date**: 2026-10-03
**Trigger**: maintainer request 2026-09-20 (TASK-588)
**Scope**: design note only; no implementation in this unit
**Confidence**: HIGH on the measured surface, MEDIUM on the behavioral verdict (device trial would settle the reveal-default question)

## The measured surface (SettingsTab.kt at HEAD, TASK-486 search index as the census)

37 searchable setting cards plus 7 sub-screens:

| Section | Cards |
|---|---|
| TRANSCRIPTION (18) | model status, active model, language, VAD, progressive display, early preview, refinement, punctuation mode, punctuation prompt, summarize, summary prompt, default prompt, diarization hub, auto-copy, export, signature, interrupted-run notifications, keep-alive |
| APPEARANCE (9) | theme, app icon, app language, swipe action, conversation grouping, compact result actions, technical details, language chip, retranscribe |
| ADVANCED (9) | battery exemption, performance hub, HuggingFace auth, per-app settings, share targets, subtitle timeout, automation hub, voice-note identity, share shortcut icons |
| FEEDBACK (1) | feedback |
| Sub-screens (7) | Automation, Export, Per-app, Performance, Prompts, Speaker, Launcher icon |

The wall the maintainer described is real but it is ALREADY STRUCTURED: the ADVANCED section exists, six heavy surfaces already live behind sub-page doors, and the pinned search bar (TASK-563) finds everything from one field. The 2026-09-20 device trial failure (the maintainer tripping on app-debug-vs-release and grouped History) is therefore not evidence that structure is missing; it is evidence that the TRANSCRIPTION section's 18 cards carry 10 low-frequency knobs at full visual weight.

## Options considered

1. **Mode toggle** (Settings > Appearance: Basic / Advanced). Rejected. It forks every surface into two states that both need device trials, both need 13-locale strings, and both can drift (the history of this repo's dual-state smells: the registry migrations existed precisely to kill "same fact, two homes"). A toggle also answers the wrong question: the maintainer who tripped is a power user; a mode that hides his options would have hurt him too.
2. **Two navigation shapes** (a simplified Home vs a full Settings). Rejected for the same drift reason, plus it doubles onboarding cost and GH #70's first-run favorites already cover the "simple front door" need without any fork.
3. **Progressive disclosure** (chosen). One surface, one truth per setting; low-frequency cards start collapsed behind a per-section reveal; search is unchanged and always finds everything, including collapsed cards.

## The verdict: progressive disclosure, de-emphasize not hide

- **The minimal TRANSCRIPTION set is 8 cards**: model status, active model, language, VAD, refinement, summarize, auto-copy, export. These are the knobs a new user touches in week one; everything else in the section is a tuning surface for a specific failure mode (progressive/early-preview for long files, prompts for Gemma refinement, signature/keep-alive/notifications for delivery edge cases).
- **Collapsed cards are visible as one summary line** ("Opzioni avanzate (10)") that expands in place. Never `gone`: a hidden setting is a support ticket; a de-emphasized one is a discoverable affordance. The count in the label is the honest "there is more here" signal.
- **Search is the universal escape hatch and stays untouched**: the TASK-486 index already flattens all 37 cards, and the TASK-563 pinned bar guarantees results stay on screen. Collapsed state must NOT filter search hits: a query match expands its section automatically (the one new behavior this design needs).
- **State is per-section and persisted** (a DataStore boolean per section, default collapsed for TRANSCRIPTION only; APPEARANCE/ADVANCED/FEEDBACK stay as-is: 9, 9 and 1 cards are not a wall).
- **Sub-screens stay doors**: Prompts, Speaker, Performance, Automation, Per-app, Export are already correctly tucked away; no change.
- **GH #70 interaction**: chiaracielo's first-run favorites slot naturally INTO this shape: the favorites ARE the 8-card basic set, personalized per user over time. The design gives #70 a landing surface instead of a new one.

## What this does NOT solve

The maintainer's actual 2026-09-20 trip (debug-vs-release app confusion) is a History/About labeling question, not a density question; TASK-588's evidence does not implicate it. Any implementation should keep that distinction honest in its motivation.

## Implementation task list (filed with this note)

1. DataStore key per-section collapsed state + the reveal row composable (the only new UI primitive; Material 3 expandable pattern, 13 locales for the two new strings).
2. TRANSCRIPTION section consumes it: the 10 low-frequency cards move under the reveal; search-match auto-expand; persisted state.
3. GH #70 favorites alignment check (do not implement #70 here; confirm the 8-card set can host it).

## Mockup

docs/mockups/settings-progressive-disclosure.html (rendered check attached in session).
