# Testing SPI (debug builds)

The test SPI is a broadcast receiver that lets an agent, a script, or CI read and write the app's testable state directly: preferences, the active model, and the imported external-model records. It replaces the 30 to 40 adb UI-driving calls a device-test session otherwise burns on opening the app, navigating to Settings, dumping the UI tree, and tapping toggles (TASK-409). It is not a product feature and never ships to users.

## Security model

Three gates keep this out of the release attack surface:

1. **Debug-only registration.** The receiver is declared exclusively in the build-type overlay `app/src/debug/AndroidManifest.xml`. AGP merges that overlay only into the debug variants of both flavors (`playStoreDebug`, `fdroidDebug`); release merges read `main` plus the flavor source set, so `assemblePlayStoreRelease` and `assembleFdroidRelease` contain neither the receiver nor the `TEST_SPI` intent filter.
2. **Runtime guard.** `TestSpiReceiver` returns immediately unless `BuildConfig.DEBUG`, so even a hypothetical manual registration in a release build does nothing.
3. **No permission.** The broadcast is exported with no permission requirement. On a device running a debug build, any app could read or flip the app's preferences through it. That is acceptable only because debug builds are never distributed; it is exactly the trade you accept when you install a debug APK.

The op engine (`TestSpiOps`, in `app/src/main/java/com/antivocale/app/testing/`) is referenced only by the debug receiver, so R8 strips it from both minified release flavors; only debug builds carry its code.

This SPI is deliberately separate from the production exported receivers (`PROCESS_REQUEST`, `PRELOAD_MODEL`), whose security posture is reviewed in [`docs/research/2026-09-03_tasker-receiver-security-analysis.md`](research/2026-09-03_tasker-receiver-security-analysis.md). That memo also flags the anti-pattern this design avoids: `BenchmarkActivity` is described as "debug-only" but registered in the main manifest, so it ships in release builds with preference-clobbering extras. A debug-only component belongs in the debug source set, not in a comment.

## Action and extras

```text
Action: com.antivocale.app.TEST_SPI   (string extras, one op per broadcast)
  op     nav | get | set | records | identity_cache | import | notify_memory_error | clipboard | notifications | unload | voice_note_state | model_download | clear_orphaned | help   (missing or unknown op answers with help; nav and voice_note_grant_open are receiver-side, TASK-486/TASK-826)
  key    one of the set keys below    (op=set)
  value  the new value                (op=set)
  entry  catalog entry id             (op=set, only for key=sherpa_path)
```

`notify_memory_error` (TASK-625 trial tool, no extras) posts the production
memory-failure error notification through the same ResultNotificationFactory
builder both error surfaces use, so the Open-setting action can be exercised
on device without engineering a real out-of-memory failure.

`clipboard` (no extras) answers with the primary clip's label and text, so a
device trial verifies a copy exactly instead of pasting into a field as a
proxy. Android 10+ lets only the focused app read the clipboard: the app must
be foreground when the broadcast lands; after a notification-action copy,
`am start` the app first and then read. A read without focus is denied
silently and looks like `text: null` with a `note` naming the two possible
causes; treat that as not verified, not as a failed copy. Long clips are
capped at 64K chars (`textTruncated: true`).

| Op | Extras | Response |
|---|---|---|
| `get` | none | JSON object: `vadEnabled`, `freezeProtectionEnabled` (GH #67 scoped: the opt-in keepalive toggle), `progressiveEnabled`, `punctuationMode`, `punctuationPrompt`, `keepAliveTimeoutMinutes`, `subtitleChoiceTimeoutMinutes`, `threadCount`, `inferenceProvider`, `transcriptionLanguage`, `transcriptionBackend`, `activeModelPath` (saved path of the current backend: the record's `dir` for `external:` ids, the generic preference for `llm`, the keyed sherpa preference for catalog ids), `paths` mapping every catalog id plus `llm` to its saved path (or `null`), plus the remaining user preferences: `summarizeEnabled`, `keywordBiasing` (TASK-858: the whistle terms), `summaryPrompt`, `autoCopyEnabled`, `memoryProtection`, `compactResultActions`, `voiceNoteIdentityEnabled` (TASK-735 identity listener gate), `voiceNoteDetectionEnabled` (TASK-271 detection gate), `voiceNoteTreeUri` (TASK-271: the SAF tree grant URI on the WhatsApp Voice Notes folder, or `null`), `advancedSharingEnabled`, `advancedShareFormats`, `showRetranscribeButton`, `groupLogsByConversation`, `showTechnicalDetails`, `vadAdvisoryDismissed`, `swipeActionMode`, `themePreference`, `themeMode`, `defaultPrompt`, `outputFolderUri` (or `null`), `transcriptExportFormat`, `externalCatalogUrl`, `measuredModelMemory` (TASK-575: read-only key=runs join of the measured load footprints), `demotedBackends` (TASK-675: read-only JSON array of backend ids demoted for silent decodes; a manual re-selection clears an entry), `textScalePreference`, `refinementEnabled`, `speakerLabelsEnabled`, `modelFilterLanguage` (TASK-685: the Models-filter favorite, or `null` when untouched), `scheduledFoldersJson` (TASK-741: the folder-watch records + snapshots, raw JSON or `null`; the device trial seeds folders without UI driving) |
| `set` | `key`, `value`, plus `entry` for `sherpa_path` | confirmation JSON echoing `key`/`value` (`entry` too when used), or an error object with `error` and the full `supportedKeys` list. Keys include `scheduled_folders` (TASK-741: replaces the whole folder-watch JSON for scripted seeding; the tree grant still needs one real picker pass) |
| `nav` | `dest` | JSON ack echoing the destination, or an error naming the valid tokens. Starts the app and routes to the destination: `tab:history`, `tab:models`, `tab:settings`, `settings:<section>` (transcription, appearance, advanced, feedback: expands and scrolls), `settings:<subpage>` (icon_picker, prompt, per_app, export, speaker, performance, automation, voice_notes), `models:import` (opens the community-catalog import dialog), `models:gemma` (TASK-365: the Gemma sub-page is gone; the token leaves the curated view if needed and SCROLLS the main Models list to the folded entry), `settings:<row>` (memory_protection: routes to the performance page with the focus flash). TASK-815: `op=nav` accepts an optional `scroll` extra (`top` | `bottom` | signed viewport count like +2 / -1) when the dest owns a scroll target: settings:automation, settings:performance, settings:speaker, settings:prompt, settings:icon_picker, settings:export, and settings:voice_notes (models:gemma's scroll target died with the Gemma sub-page; the token reveals the entry with no scroll extra, TASK-365). Anything else answers the error contract naming the scrollable set. One broadcast replaces the swipe-and-dump slog through Settings (TASK-486). |
| `import` | `url`, `family` (optional), `model_type` (optional) | JSON with the imported `record` (same shape as `records` elements, plus the derived `backendId`); runs the same url-classifying import the dialog uses (catalog-entry JSON or HuggingFace repo url). Added for the TASK-550 device pass so imports need no UI driving. `family` (TASK-618) optionally overrides the import family by ModelFamily name (URL imports are detect-then-tell: the chooser belongs to the dialog UI, the headless equivalent is this extra); unknown names are rejected without importing. `model_type` carries the CTC subtype the dialog's selector owns (`nemo_ctc`/`zipformer_ctc`/`omnilingual_ctc`/`paraformer`; family=CTC cannot succeed without it) |
| `records` | none | JSON array of the imported external models; each element is the record's persisted JSON plus the derived `backendId`. All records are listed, including dangling ones whose directory no longer exists, because dangling state is precisely what a debugging session needs to see |
| `notify_memory_error` | none | JSON `{"op":"notify_memory_error","posted":<notificationId>}`; posts the production memory-failure notification (TASK-625 trial tool, see the paragraph above) |
| `clipboard` | none | JSON `{"op":"clipboard","label":<string or null>,"text":<string or null>}` reading the primary clip (see the paragraph above) |
| `notifications` | none | JSON `{"op":"notifications","count":N,"items":[{id,channel,actions,title,text,subText,bigText}]}` listing THIS app's active notifications (read content here, reserve the shade for visual checks) |
| `unload` | none | JSON `{"op":"unload","unloaded":true}`; runs the production `TranscriptionBackendManager.unloadAll` (the exact call the UI Unload buttons make), so a mid-generation unload can be fired on device without a UI tap (TASK-791 drain-release proof). Logical unload: as the manager's own KDoc states, native memory frees only when an in-flight call drains, so do not read resident memory right after |
| `model_download` | `entry` (catalog id), `value` (variant name) | JSON `{"op":"model_download","dispatched":"<entry>/<variant>",...}` (TASK-365): dispatches a catalog model download through the same ExtractionService intent the UI confirm dialog builds; progress rides the service notification and the Models-tab state. Error arms: no app context, blank params, unknown entry/variant. |
| `clear_orphaned` | `entry` (catalog id), `value` (variant name) | JSON `{"op":"clear_orphaned","deleted":<bool>,"variant":"<entry>/<variant>"}` (TASK-870's lesson): deletes a variant's model directory directly - the same filesystem effect as the UI's Orphaned/Clear affordance (the Models tab re-derives its state on the next sweep). deleted=true is the dir-gone postcondition (also true when the dir did not exist; false is near-unreachable). Error arms: no app context, blank params, unknown entry/variant. |
| `voice_note_state` | none | JSON `{"op":"voice_note_state","enabled":<bool>,"treeUri":<string or null>,"audioPermissionGranted":<bool or null>,"grantHeld":<bool or null>,"armed":<bool or null>,"willArm":<bool or null>}` (TASK-826): the detection coordinator's conjunction read mechanically. `grantHeld` is the LIVE persisted-grant probe (a saved URI whose grant died reads false, which is what disarms detection), `armed` is the coordinator's subscription state right now, `gateState` is the coordinator's desired-gate state (Inactive/PermissionMissing/GrantMissing/Active; armed=false with gateState=Active means a subscribe failed: a catchable divergence), and `willArm` is the FULL arm conjunction evaluated server-side, platform support included (on Android 10 `willArm` is correctly false even with everything else granted; `audioPermissionGranted` is the permission probe alone); a script asserts `armed == willArm` after a beat. Context-derived fields are `null` under the unit fakes; on device they are always booleans |
| `voice_note_grant_open` | none | JSON `{"op":"voice_note_grant_open","launched":true,...}` (TASK-826): opens the SAME system folder picker the Settings grant card uses (initial URI included) and runs the shared acceptance policy (take, validate, release-old-slot, save) on the picked folder; the outcome lands on the `TestSpi` logcat channel as `{"op":"voice_note_grant_open","result":"saved <uri>"}` (or `canceled` / `take_failed` / `wrong_folder` / `error` for an acceptance or picker failure). The USE-THIS-FOLDER tap in DocumentsUI is the one manual gesture; the persisted grant survives reinstalls, so this is once per device. Receiver-side like `nav` |
| `help` | none | the op list, the set keys, the usage line, and the `PROCESS_REQUEST` pointer |

Set keys and value formats:

| Key | Writes | Value |
|---|---|---|
| `vad` | `saveVadEnabled` | `true` or `false` (strict; anything else is an error) |
| `freeze_protection` | `saveFreezeProtectionEnabled` | `true` or `false` (strict); the GH #67 scoped silent-keepalive toggle, DEFAULT `false` |
| `refinement_enabled` | `saveRefinementEnabled` | `true` or `false` (GH #43 two-pass toggle) |
| `speaker_labels_enabled` | `saveSpeakerLabelsEnabled` | `true` or `false` (GH #83 speaker labeling toggle) |
| `speaker_id_enabled` | `saveSpeakerIdEnabled` | `true` or `false` (TASK-670 named speaker labels; default off, the privacy gate) |
| `voice_note_identity_enabled` | `saveVoiceNoteIdentityEnabled` | `true` or `false` (TASK-735 voice-note identity listener gate; default off) |
| `voice_note_detection_enabled` | `saveVoiceNoteDetectionEnabled` | `true` or `false` (TASK-271 voice-note detection gate; default off; the runtime read-media permission is the second gate, so a trial also grants it on device) |
| `refinement_model` | `saveRefinementModelBackendId` | a known backend id or blank (TASK-740; blank = inherit the active backend for the two-pass refine phase) |
| `punctuation` | `savePunctuationMode` | `off`, `auto`, `always`, `conservative` (TASK-666; the dropdown offers off/always/conservative, SPI also accepts the legacy auto; anything else is rejected) |
| `punctuation_prompt` | `savePunctuationPrompt` | free text, 500-char cap; blank = the localized built-in prompt |
| `keep_alive` | `saveKeepAliveTimeout` | positive integer (minutes); 0/negative rejected (would silently fall back to the default) |
| `keyword_biasing` | `saveKeywordBiasing` | free text, newline-separated whistle terms; blank clears (off); capped at PROMPT_CAP (TASK-858) |
| `subtitle_timeout` | `saveSubtitleChoiceTimeoutMinutes` | positive integer (minutes); TASK-515: the subtitles-or-transcribe choice timeout |
| `progressive` | `saveProgressiveTranscription` | `true` or `false` (strict); gates the interim chunk notifications and the chunk nav (added after the 2026-09-04 session burned six UI taps on this toggle) |
| `early_preview` | `saveEarlyPreviewEnabled` | `true` or `false` (strict); TASK-186 early preview of the first pipeline chunk's head (default off) |
| `interrupted_run_notifications` | `saveInterruptedRunNotifications` | `true` or `false`; TASK-684 the generic-interrupted summary notification (default on; the suspended class always notifies) |
| `threads` | `saveThreadCount` | integer (for example `4`) |
| `provider` | `saveInferenceProvider` | `auto`, `nnapi`, `cpu` (the settings dropdown's exact set; anything else is rejected because the app would silently run it as CPU) |
| `backend` | `saveTranscriptionBackend` | a catalog id (`sherpa-onnx`, `whisper`, `qwen3-asr`, `nemotron-streaming`, `gigaam`), `llm`, or `external:<record id>`; unknown ids are rejected without writing |
| `advanced_sharing` | `saveAdvancedSharingEnabled` | `true` or `false` (strict) |
| `advanced_share_formats` | `saveAdvancedShareFormats` | `true` or `false` (TASK-800: the advanced Share… format-picker button on result rows; default off) |
| `auto_copy` | `saveAutoCopyEnabled` | `true` or `false` (strict) |
| `signature_enabled` | `saveSignatureEnabled` | true/false |
| `signature_text` | `saveSignatureText` | free text (blank = localized default at assembly) |
| `signature_position` | `saveSignaturePosition` | `prepend` or `append` |
| `compact_result_actions` | `saveCompactResultActions` | `true` or `false` (strict) |
| `language_chip` | `saveLanguageChipEnabled` | `true` or `false` (strict); TASK-546: gates the detected-language chip on results |
| `memory_protection` | `saveMemoryProtection` | `true` or `false` (strict) |
| `external_automation` | `saveExternalAutomationEnabled` | `true` or `false` (strict); TASK-274: consent gate for the exported automation receivers (Tasker surface) |
| `open_transcribe` | `saveOpenTranscribeEnabled` | `true` or `false` (strict); TASK-785: Open Transcribe provider gate; the BridgeApplication collector flips the manifest component off any write |
| `remote_enabled` | `saveRemoteOmnivoiceEnabled` | `true` or `false` (strict); TASK-681: LAN-offload consent gate; disabling while it is the selected backend resets the backend to the default |
| `remote_endpoint` | `saveRemoteOmnivoiceEndpoint` | OmniVoice base URL, e.g. `http://192.168.1.10:3900`; TASK-681 |
| `remote_api_key` | `saveRemoteOmnivoiceApiKey` | the server's OMNIVOICE_API_KEY; TASK-681 |
| `remote_model` | `saveRemoteOmnivoiceModel` | pass-through model name the server resolves (default `whisper-1`); TASK-681 |
| `group_logs` | `saveGroupLogsByConversation` | `true` or `false` (strict) |
| `technical_details` | `saveShowTechnicalDetails` | `true` or `false` (strict); TASK-616: gates the technical processing-context line on transcript entries |
| `show_retranscribe` | `saveShowRetranscribeButton` | `true` or `false` (strict) |
| `theme` | `saveThemePreference` | `DEFAULT`, `WHATSAPP`, or `TELEGRAM` (ThemeType names) |
| `theme_mode` | `saveThemeMode` | `SYSTEM`, `DARK`, or `LIGHT` (ThemeMode names) |
| `text_scale` | `saveTextScale` | `SYSTEM`, `SMALL`, `LARGE`, or `XLARGE` (TextScale names) |
| `onboarding` | `saveOnboardingCompleted` | `true` or `false` (strict); TASK-491: `false` re-arms the welcome tour |
| `vad_advisory` | `saveVadAdvisoryDismissed` | `true` or `false` (strict); polarity is inverted: `true` = dismissed |
| `transcript_export_format` | `saveTranscriptExportFormat` | `TXT`, `TXT_TIMED`, `SRT`, or `VTT` (GH #92; the settings dropdown's exact set, anything else rejected) |
| `language` | `saveTranscriptionLanguage` | BCP-47 tag, `system`, or `auto`; written as given, no validation (the Settings picker normally constrains this to the catalog's languages) |
| `model_filter_language` | `saveModelFilterLanguage` | ISO 639-1 code (the Models-tab filter dropdown's entries), or blank to record an explicit clear; TASK-685: the first-run seed target, and the untouched (`null`) state is reachable only by a fresh DataStore (the seed's own scene) |
| `model_path` | `saveModelPath` | path (llm backend's model file) |
| `sherpa_path` | `saveSherpaModelPath(entry, path)` | path, with `entry=<catalog id>` naming which backend's keyed preference is written |
| `summarize` | `saveSummarizeEnabled` | `true` or `false` (strict); the opt-in summary pass over long transcripts (added after the 2026-09-08 memory-measurement session found the SPI could not reach it) |
| `summary_prompt` | `saveSummaryPrompt` | free text, 500-char cap; blank = the built-in two-to-three-sentence prompt |
| `swipe_action` | `saveSwipeActionMode` | `REVEAL` or `IMMEDIATE_DELETE` (the Logs settings dropdown's exact set) |
| `default_prompt` | `saveDefaultPrompt` | free text, 500-char cap (like `punctuation_prompt`); the Tasker default prompt |
| `external_catalog_url` | `saveExternalCatalogUrl` | URL of the community-catalog JSON source | (TASK-649: a dropped/empty value CLEARS the key; the shell drops empty --es extras)
| `output_folder` | `saveOutputFolderUri` | SAF tree URI; a blank value clears the preference back to unset (`null`). Only URIs the app was granted through the SAF picker work: the SPI stores the string but cannot take the persistable URI grant, so a never-granted tree fails silently at the next auto-save. Set folders that came out of a real picker session |
| `scheduled_folders` | `saveScheduledFoldersJson` | TASK-741: the folder-watch records + snapshots as one raw JSON (blank clears). Seeding can stage the store, but the tree grant cannot be conferred by the SPI: a seeded uri without a persisted permission is scan-abandoned by design, so the trial still needs one real picker pass |

Paths are written as given and not validated against the filesystem. A test that writes a bogus path and then transcribes will fail at model load; set paths that came out of `op=get` or `op=records`, or a real download directory.

Deliberately NOT reachable through the SPI (kept out on purpose): `partialTranscription*` (transient crash-recovery state) and benchmark results (their own domain, reset from the UI); legacy one-shot markers (`externalMigrationDone`, `customTransducer*`); the TASK-640 crash marker (`pendingBackendLoad`: write-path only, armed around native loads); `externalModelsJson` (read via `op=records`, written by the import pipeline).

## Ready-to-paste: navigation

```bash
# straight to the launcher-icon picker (one broadcast, then one dump):
adb shell am broadcast --allow-background-activity-starts \
  -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver \
  -a com.antivocale.app.TEST_SPI --es op nav --es dest settings:icon_picker
# straight to the community-catalog import dialog:
adb shell am broadcast --allow-background-activity-starts \
  -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver \
  -a com.antivocale.app.TEST_SPI --es op nav --es dest models:import
# to the Appearance section (expands + scrolls):
adb shell am broadcast --allow-background-activity-starts \
  -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver \
  -a com.antivocale.app.TEST_SPI --es op nav --es dest settings:appearance
```

One nav at a time: await each broadcast's ack before the next (the handoff is
conflating; a second token before the UI consumed the first is lost). The
`--allow-background-activity-starts` flag matters when the app is not already
foregrounded: without it Android may silently block the activity start while
the ack still says success.

Field notes (2026-10-19, device session): the flag covers `nav` but NOT
`voice_note_grant_open` end to end: the grant flow starts a second activity
(the debug grant activity) which then starts the DocumentsUI picker, and that
second hop falls outside the broadcast's temporary allowlist when the app is
backgrounded. Foreground the app first (`adb shell am start
com.antivocale.app.debug/com.antivocale.app.MainActivity`), then send the op.
The same field session hit two adjacent traps: a stale foregrounded window
from a previous test (the DocumentsUI picker left open) hides the navigated
subpage without any error (force-stop `com.google.android.documentsui` and
re-nav), and a locked screen turns every dump into keyguard noise (raise
`screen_off_timeout` via adb for the session, restore 30000 at the end).

## Reading responses

Every response is a single line of JSON, delivered on two channels:

1. **resultData, the primary channel (device-verified 2026-09-03, Realme RMX3853 / Android 16):** with the explicit-component form below, the JSON comes back INLINE in the `am broadcast` output itself (`Broadcast completed: result=-1, data="{...}"`). One call, no second round-trip.
2. **logcat, the fallback channel.** Every response is also `Log.i("TestSpi", json)`: `adb shell logcat -d -s TestSpi:I`.

## Always use the explicit component (-n), and launch once after install

Two delivery rules learned on the real device (2026-09-03):

- **Implicit shell broadcasts are NOT delivered on this device.** A bare `am broadcast -a com.antivocale.app.TEST_SPI` completes silently with `result=0` and the receiver never runs; the same broadcast with `-n` is delivered. Verified as device behavior, not SPI behavior: an implicit `PROCESS_REQUEST` (a receiver that has worked for months) was also silently dropped in the same session, while its explicit form ran. Prefix every SPI call with the component:

```bash
adb shell am broadcast -a com.antivocale.app.TEST_SPI \
  -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver --es op get
```

- **After a fresh install the app sits in the stopped state and receives nothing**, even with `-n`. Launch it once (`am start -n com.antivocale.app.debug/com.antivocale.app.MainActivity`) before the first SPI call of a session.

Debug builds install as `com.antivocale.app.debug` (the `applicationIdSuffix`), alongside the user's Play Store app; the `TEST_SPI` action exists only in the debug install.

## Ready-to-paste commands

Read the whole testable state in one call:

```bash
adb shell am broadcast -a com.antivocale.app.TEST_SPI -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver --es op get
```

Toggle VAD and the progressive display (the classic multi-tap time sinks):

```bash
adb shell am broadcast -a com.antivocale.app.TEST_SPI -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver --es op set --es key vad --es value true
adb shell am broadcast -a com.antivocale.app.TEST_SPI -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver --es op set --es key vad --es value false
adb shell am broadcast -a com.antivocale.app.TEST_SPI -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver --es op set --es key progressive --es value true
adb shell am broadcast -a com.antivocale.app.TEST_SPI -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver --es op set --es key progressive --es value false
```

Switch the active backend and language:

```bash
adb shell am broadcast -a com.antivocale.app.TEST_SPI -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver --es op set --es key backend --es value whisper
adb shell am broadcast -a com.antivocale.app.TEST_SPI -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver --es op set --es key language --es value it
```

Threads and inference provider:

```bash
adb shell am broadcast -a com.antivocale.app.TEST_SPI -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver --es op set --es key threads --es value 4
adb shell am broadcast -a com.antivocale.app.TEST_SPI -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver --es op set --es key provider --es value nnapi
```

Saved model paths (llm uses the generic preference; sherpa catalog backends need `entry`). TASK-626 caveat: writing a `sherpa_path` whose directory does not exist while the backend is warm forces a reload at the NEXT transcription, and the loader persists the auto-resolved variant over the written value; a get right after the transcription can read a path the test never wrote. Set valid directories, or read the preference before transcribing.

```bash
adb shell am broadcast -a com.antivocale.app.TEST_SPI -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver --es op set --es key model_path --es value /data/local/tmp/gemma.taskml
adb shell am broadcast -a com.antivocale.app.TEST_SPI -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver --es op set --es key sherpa_path --es entry sherpa-onnx --es value /storage/emulated/0/Android/data/com.antivocale.app.debug/files/models/sherpa-onnx
```

Imported external models, and the help text:

```bash
adb shell am broadcast -a com.antivocale.app.TEST_SPI -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver --es op records
adb shell am broadcast -a com.antivocale.app.TEST_SPI -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver --es op help
```

## Ready-to-paste: the voice-note detection trial (TASK-826)

The whole trial is scriptable end to end except ONE gesture: the DocumentsUI
USE-THIS-FOLDER confirmation (the OS requires it; after it the persisted
grant survives reinstalls, so it is once per device).

```bash
# 1. Arm the gates: detection preference on, runtime audio permission granted
#    (the permission dialog is the app's own runtime ask; grant once via
#    Settings or the trial's UI step), then read the conjunction:
adb shell am broadcast -a com.antivocale.app.TEST_SPI -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver --es op set --es key voice_note_detection_enabled --es value true
adb shell am broadcast -a com.antivocale.app.TEST_SPI -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver --es op voice_note_state
#    -> grantHeld false, armed false, willArm false: the tree grant is missing.

# 2. Open the SAME folder picker the Settings grant card uses (initial URI
#    points at the WhatsApp Voice Notes folder), then tap USE THIS FOLDER:
adb shell am broadcast -a com.antivocale.app.TEST_SPI -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver --es op voice_note_grant_open
adb shell logcat -s TestSpi:I -d | tail -2
#    -> {"op":"voice_note_grant_open","result":"saved <tree uri>"}

# 3. Assert the reconcile outcome mechanically (armed == willArm):
adb shell am broadcast -a com.antivocale.app.TEST_SPI -n com.antivocale.app.debug/com.antivocale.app.receiver.TestSpiReceiver --es op voice_note_state
#    -> grantHeld true, armed true, willArm true
```

The detection TRIGGER (replacing the removed offer probe): the detector
subscribes a FileObserver on the granted tree and fires on CLOSE_WRITE or
MOVED_TO of a file named `PTT-*.opus` under a numeric counter directory.
A synthetic note pushed through the shell lands the same events a real
WhatsApp download does (writes through FUSE arrive as CLOSE_WRITE):

```bash
adb push note.opus "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp Voice Notes/20991231/PTT-20991231-WA0001.opus"
```

If the push is refused on a locked-down device, a real note (any voice
message sent to the installed WhatsApp) is the fallback trigger; the
transcription result then appears in History and `op=notifications`.
Removing the synthetic note re-arms nothing (detection stays subscribed);
`voice_note_state` stays the source of truth.

## Triggering transcription

Transcription is deliberately not an op of this SPI: it already has a production broadcast, `TaskerRequestReceiver` on `com.antivocale.app.PROCESS_REQUEST`:

```bash
adb shell am broadcast -a com.antivocale.app.PROCESS_REQUEST \
  -n com.antivocale.app.debug/com.antivocale.app.receiver.TaskerRequestReceiver \
  --es request_type audio \
  --es file_path /data/local/tmp/test_voice.wav \
  --es task_id spi-test \
  --es backend_id whisper
```

`backend_id` is optional (one-shot override; defaults to the saved preference). The transcript comes back on the `net.dinglisch.android.tasker.ACTION_TASKER_INTENT` reply broadcast and in the result notification. The path must be readable by the app's uid: the app declares no storage permissions (removed with `READ_MEDIA_AUDIO` in commit `b54303d`), so shared-storage paths like WhatsApp's media directory are unreadable and fail in preprocessing. Push audio into the app's own sandbox instead (the recipe the device playbook already uses):

```bash
adb push test_voice.wav /data/local/tmp/test_voice.wav
adb shell run-as com.antivocale.app.debug cp /data/local/tmp/test_voice.wav files/shared_audio/test_voice.wav
adb shell am broadcast -a com.antivocale.app.PROCESS_REQUEST \
  -n com.antivocale.app.debug/com.antivocale.app.receiver.TaskerRequestReceiver \
  --es request_type audio \
  --es file_path /data/user/0/com.antivocale.app.debug/files/shared_audio/test_voice.wav \
  --es task_id spi-test
```

The staged path must live under the app's `files/shared_audio/` (or `cache/`):
the receiver's TASK-274 path allowlist canonicalizes `file_path` and rejects
everything else with `Rejected file_path outside the allowlist` in logcat
(rejected 2026-10-17 on device with a `files/`-root path; the shared_audio
staging above is the working recipe).

The readable-set analysis is in the security memo linked above.
