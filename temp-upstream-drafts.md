# Upstream drafts v2 (k2-fsa/sherpa-onnx); corrected after code review; awaiting maintainer approval

## ISSUE

**Title:** Qwen3-ASR: with the default max_total_len=512, a 30-second clip silently loses generation budget (and longer clips lose audio)

**Body:**

Two defects share one cause: the default `max_total_len` of 512 is smaller
than one 30-second clip plus a reasonable generation budget.

At 13 audio tokens per second (per `FeatToAudioTokensLen`), a 30-second clip
is ~390 audio tokens plus the prompt scaffold (a handful of tokens). With the default
`max_new_tokens=128`, a correctly-chunked 30-second pass needs ~533 tokens;
the 512 budget lets generation run only ~107 of its 128 tokens, and the
break at `offline-recognizer-qwen3-asr-impl.cc` (`cur_len >= max_seq_len`)
exits the loop with NO log, so the transcript just ends early with no
indication why. For clips longer than ~38s fed as one stream, audio
placeholders are dropped as well (that path does log).

Measured on the official `sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25`
package, 11-clip Italian test set (WER, lower is better):

| regime | max_total_len=512 (default) | 1024 | 2048 |
|---|---|---|---|
| whole clips, one stream | 23.5% | 16.1% | 12.8% |
| 30s chunks | 14.9% | 13.3% | 13.3% |

The trade to weigh: on dynamic-dim exports like this one the KV cache is
allocated at `max_total_len` (the decoder's cache inputs carry a symbolic
dim), roughly 110 MiB per 512 positions at float32 with 28 layers, so
raising the default doubles the per-recognizer transient allocation.

I have a patch that (a) logs the generation break like the existing
max_new_tokens break, and (b) raises the default to 1024 with the memory
cost documented in the config header; I will open a PR.

## PR 1 (references the issue)

**Title:** qwen3-asr: log the max_total_len generation break; raise default 512 -> 1024

**Body:**

References #<issue-number>. Two changes:

1. The `cur_len >= max_seq_len` break inside the generation loop was silent,
   unlike the `max_new_tokens` break right below it. With the default
   budget a correctly-chunked 30-second clip (~390 audio tokens plus a handful of prompt tokens) got only ~107 of its default 128 generation tokens and the
   transcript ended early with no indication why. The break now logs the
   budget, the reached length, and the existing suggestions.

2. The default `max_total_len` rises from 512 to 1024: one 30-second clip
   plus generation headroom. The cost is documented in the config header:
   on dynamic-dim exports (the official 0.6B int8 among them) the KV cache
   is allocated at this size, roughly 110 MiB per 512 positions (float32,
   28 layers), so the default doubles the per-recognizer transient versus
   512.

Verified by building this tree and decoding the 11-clip Italian set with
PURE DEFAULTS against the v1.13.8 wheel: 23.5% -> 16.1% WER (whole clips,
one stream); with 30-second chunking, 14.9% -> 13.3%. Defaults updated in
every binding that carries one: csrc config header, python (pybind and
wrapper), c-api, cxx-api, kotlin-api, java-api.

## PR 2 (tokenizer path)

**Title:** qwen3-asr: accept the vocab.json file path as the tokenizer argument; fix the misleading load error

**Body:**

`QwenAsrTokenizer::Init` appends `/vocab.json` to its argument. Passing the
vocab.json file itself (a natural mistake: it is the file the loader reads)
made the loader look for `.../vocab.json/vocab.json` and fail with

    Failed to read vocab.json from: <the path the user passed>

which names a file that does exist, pointing the reader away from the real
problem.

Changes, in both the filesystem and the asset-manager `Init` overloads via
one shared `NormalizeTokenizerDir` helper: a trailing `/vocab.json` (with
something before it, so no length underflow on the bare name) maps back to
the parent directory; if the normalized directory does not contain
vocab.json but the literal path does (a directory legitimately NAMED
vocab.json), the literal path wins, so no previously-working input breaks.
The vocab load failure now names the path actually attempted and the
accepted argument forms. API doc comments that describe the argument as
the directory now mention the file form too.

Repro: `OfflineRecognizer.from_qwen3_asr(..., tokenizer=str(pkg/"tokenizer"/"vocab.json"))`
exits with the misleading message on v1.13.8; with this patch it loads and
decodes.

## GH reply drafts (2026-10-19, PARKED pending maintainer approval)

### Issue #160 (TranscribePsy-250M)

Thanks for the pointer. I verified the repos: a 254M EU-multilingual transducer distilled from Parakeet-TDT-0.6B-v3 is exactly the kind of candidate we evaluate for the small-model class, and Italian is covered. Two honest caveats before any promise: the repos ship NeMo artifacts only, so it needs a sherpa-onnx export first, and our process is eval-first, meaning it lands only if the Italian WER and the on-device speed beat what we already ship. Tracked internally now; the gguf sibling is out of scope for us (our runtime is ONNX/LiteRT based).

### Issue #161 (non-Gemma litertlm import)

The import half already works: Settings, external models, import from URL accepts any .litertlm asset (that shipped with the LiteRT-LM import lane), so you can pull a file from litert-community/Qwen3-ASR-1.7B today. The open question is the engine half: whether our LiteRT runtime initializes and transcribes with a non-Gemma build, and that Qwen3-ASR-1.7B repo is now the concrete probe on our list (the -15s build also raises a chunking question we will answer with the probe). If you already tried the import on a specific build, a comment saying which file you picked and what happened would save us a round trip.


## GH close drafts (2026-10-20, PARKED pending maintainer approval, issue-closure audit)

### #141 (Detection catch-up): close as completed

Shipped: when the app arms detection on a new WhatsApp counter folder, it now runs one catch-up scan after the folder goes quiet, so notes that landed before the watcher attached are still transcribed (device-verified). The sub-second race window is covered by unit tests. Shipping in the next release.

### #146 (French transcribed in English): close as completed

Parakeet auto-detects the language per segment and cannot be pinned; the app now shows a one-line advisory under the result when the transcript clearly reads in a language absent from your device locales, naming the Settings path to lock one (Whisper plus a language pin). The FAQ has the full story: https://github.com/RisorseArtificiali/anti-vocale/blob/main/FAQ.md

### #156 (Copy App Version to Clipboard): close as completed

Shipped: tapping the version row in Settings > About copies the full environment line (app version, Android, device, model) to the clipboard.

### #157 (Audio Playback & Process Optimization): close as completed

All three parts shipped: play/pause with 1.25x/1.5x speed in the expanded result row (audio focus handled), silence skipping gated on sentence-level cues, and a duplicate guard that recognizes an already-transcribed file at intake and offers to open the existing result. Note: playback needs the row's audio file, which the app keeps for about a day; silence skipping needs a model that emits token timestamps.

### #158 (Export/Import App Settings & Transcription History): close as completed

Shipped as "Backup and transfer" in Settings > Advanced: exports a zip with whitelisted settings (secrets and device-bound grants excluded by a test-enforced whitelist) plus the full history; import merges idempotently and settings apply without a restart. The pre-export dialog states plainly that history transcripts are plaintext in the archive.

---

## Draft: ask to Cactus Compute (needle engine on F-Droid; option G of the 2026-10-19 F-Droid research)
Status: DRAFT, awaiting maintainer approval. Target: Cactus Compute (GitHub discussions/email for cactus-compute/needle or the HF org). Language: English, first person singular from the maintainer.

Subject: Publishing the Android engine binaries through PyPI (or the C++ source)

Hi,

I maintain Anti-Vocale, an on-device transcription app for Android (github.com/RisorseArtificiali/anti-vocale). We integrated Whistle through the prebuilt Needle engines from Cactus-Compute/needle3 (arm64 and armv7 static libraries), and it ships on the Play Store and as direct APKs. Our JNI bridge is open source (Apache-2.0 like the engine); the integration notes are in the repo under TASK-853.

We also distribute through F-Droid, which builds everything from source and only accepts prebuilt binaries from specific channels. Hugging Face is not one of them, but PyPI wheels are, explicitly. Today our F-Droid build ships without the Whistle backend because of this.

Two asks, either of which solves it for us:

1. The small one: publish the Android platform artifacts (libneedle.a for arm64-v8a and armeabi-v7a, plus needle.h) inside a PyPI wheel, or a companion package such as cactus-needle-platform, tagged per platform. You already run PyPI for cactus-needle, so this would extend packaging you have. F-Droid's inclusion policy accepts "prebuilt FLOSS binaries from PyPI Wheels", so that channel would make the engine usable for every F-Droid app, not just ours.

2. The bigger one: publishing the engine's C++ source would let us build it from source exactly as we already do for sherpa-onnx (our F-Droid recipe compiles that engine via upstream's build scripts, pinned by commit). If the build from source is practical on your side, this is the cleanest outcome for distributions.

Happy to help with either: we already fetch the artifacts pinned by commit with sha256 verification, and we would move the fetches to the PyPI channel the day it exists.

Thanks for Whistle; at 17 MB with keyword biasing and word timestamps it is a remarkable piece of engineering.

Paolo

---

## dpakkia PR set: the four review comments (DRAFT, awaiting maintainer go; reviews verified 2026-10-21, both CI flakes rerun green)

### PR #167 (16 KB alignment) - approval comment
Verified your claim against our own arm64 APK before reading the patch: libwhistle_jni.so is indeed the only 4 KB-aligned library (llvm-readelf LOAD 0x1000; onnxruntime and litertlm are 0x4000). The fix is the documented one and it is harmless on 4 KB devices and 32-bit ABIs, including the stub build the F-Droid channel uses. Approving as-is; the CI failure on the first run was a timing flake in an unrelated JVM test (ModelViewModelBundleLinkTest polls with a 5 s real-time deadline), green on rerun and on our machines.

### PR #166 (one notification, one note) - approval + nits
Approved. The consumed-set is correctly scoped: same monitor as the rest of the cache, pruning keeps the set co-bounded with entries, and the automatic route is the only place the one-notification-one-note rule actually holds (the share route keeping forPackage is right). I verified the tests pin the behavior for real: applied your patch, 13/13 pass; on unpatched main both behavioral tests fail with exactly the wrong-label outcomes you are fixing. Two nits, neither blocking:
- VoiceNoteSenderResolution.kt:120: filtering inWindow through unconsumedForPackage makes the empty-candidates log at :103 absorb the "consumed by an earlier note" case into the TASK-833 no-candidates class, exactly in the bursty scenario this PR addresses. Suggested: keep the forPackage-based in-window list for the count and log "no unconsumed candidates in window (N consumed by earlier notes)" when consumption, not absence, emptied it.
- The check-then-mark between unconsumedForPackage and markConsumed brackets probeDurationSeconds (file IO). Unreachable today because the only caller runs inside the coordinator's single collector job, but worth one KDoc sentence pinning that serialization assumption, or make markConsumed return Boolean and treat a lost claim as no-label.

### PR #165 (result notification for automatic notes) - approval + minors
Approved. The reserved-id contract, the SubtitleFormatter pair, the requester ordering, and the Realme action caps all check out; the nav round-trip discipline on the two new spec fields is the house pattern done right. Two minors:
- InferenceService onSuccess still carries the TASK-820 comment claiming "the RESULT NOTIFICATION stays share-only (TASK-688's design)" and the save-failure comment now mismatches the !postsResult condition; reword both to the postsResult vocabulary.
- ResultNotificationFactory uses the fully-qualified AppInfoUtils name although it is already imported; the short form.
Two observations, no action requested in this PR: a burst of N notes now posts N PRIORITY_HIGH heads-ups (shares never burst; I want to see it on my device's shade before deciding whether the automatic origin wants a quieter priority), and README:289/FAQ:89 describe the sender label as a History fact while it now also titles the notification, worth a one-line touch-up whenever docs are next open.

### PR #164 (WhatsApp Business) - approval with one should-fix
This is strong work and I am approving it with one fix I would like folded in before merge, plus smaller items. F1 (the should-fix): PerAppPreferencesManager.getDefaultPreferences (app/src/main/java/com/antivocale/app/data/PerAppPreferencesManager.kt:162-168) switches on exact package equality, so com.whatsapp.w4b falls to default() and an auto-transcribed Business note loses auto-copy and the one-tap send-back that WhatsApp notes get (ResultNotificationFactory gates the Send-to action on prefs.quickShareBack), contradicting the README parity claim. The KnownApp table already maps w4b into the WhatsApp family, so this is the one straggler dispatch site: a "com.whatsapp.w4b" -> whatsappDefaults() mapping plus a pin test closes it. Smaller items: F2 the hub summary strings are still WhatsApp-specific while the row now ORs both sources (genericize summary_on/off across locales, or derive from active sources); F3 three English placeholder strings shipped inside values-hi/nl/zh (voice_note_business_folder_grant_rejected x2, voice_note_business_detection_description); F4 the per-source RecheckOnResume both poke all coordinators (hoist the probe); F5 the SPI grant ack and outcome log do not name the slot (add the source token; dual-slot device trials need it); F6 VoiceNoteTreeGrant.EXPECTED_FOLDER_NAME lost its last production reader (pin the enum value in the test and delete the constant). Also attach the backlog task anchor to the CLAUDE.md paragraph before merge, matching its siblings. For the record: the first CI run's failure was a Maven Central download flake (kotlinx-coroutines-test jar), green on rerun; nothing in your change. The VoiceNoteSource enum carrying the whole per-client difference, the byte-identity pins for pre-Business ids, and the cross-client grant validation are exactly how I would have shaped it.

## 2026-10-26 drafts (awaiting approval; NOT posted)

### #161 (srappan, non-Gemma litertlm imports)
Thanks for the request. I looked into what it would take. The transcription pipeline feeds audio to the model container itself, and the non-Gemma .litertlm bundles on litert-community (Qwen, Phi, Llama) are text-only chat models with no audio adapter inside, so the engine has no audio path to feed regardless of session settings. The one audio bundle there today (Qwen3-TTS) is text-to-speech, the wrong direction for us, and it targets the desktop omni_runner rather than the on-device engine. Closing for now; if an audio-input non-Gemma .litertlm ever lands on litert-community I will gladly revisit.

### #162 (furiop, built-in recorder with live transcription)
Thanks, and also for the chunked-prototype notes you shared earlier. Today the app only transcribes existing audio (shares, detected voice notes, Tasker, files), so a built-in recorder would be a new surface: microphone permission, a foreground capture service, and a recording screen ahead of the existing enqueue pipeline. True live transcription while recording is a second and larger decision, since it needs the streaming model lane (Nemotron) or chunked decoding of a growing file. Both are on my radar with rough sizing; I cannot promise a timeline yet.
