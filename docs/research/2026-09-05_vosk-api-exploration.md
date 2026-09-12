# Research Report: vosk-api as an ultra-lightweight tier for Anti-Vocale (TASK-452)

**Date**: 2026-09-05
**Depth**: exhaustive (primary sources: vosk-api repo at master/Maven 0.3.75, measured AAR binary, HuggingFace repos with sha256 verification, alphacephei.com, sherpa-onnx docs and CI; plus new empirical measurements with our own eval harness)
**Confidence**: HIGH overall (per-finding levels below)

## Executive summary

Do not integrate vosk-api, and no conversion work is needed either: the Vosk model line that matters is already running on our single sherpa-onnx stack. Three findings drive the verdict. First, the shipped Android runtime (`com.alphacephei:vosk-android:0.3.75`, measured from the Maven artifact) is a second, fully self-contained Kaldi native stack: one `libvosk.so` of about 10MB per ABI that statically bundles Kaldi nnet3 and OpenFst, plus a 2MB JNA dependency, and it contains no ONNX runtime at all, so it cannot even load the current Vosk 0.54/0.56 zipformer models. Second, the modern Vosk Russian models are icefall-trained zipformer2 models that alphacep exports to ONNX in the model repos themselves; our community "Russian (light)" entry is byte-identical to that export (sha256 verified on all four files this session), alphacep's own `decode-onnx.py` decodes them with `sherpa_onnx`, and sherpa-onnx documents and CI-repackages the same route officially. The re-export path is not a hack that might generalize; it is the vendor's and the runtime's shared design. Third, new same-harness measurements on the six-lecture Russian benchmark put Vosk 0.54 int8 at macro WER 15.07% (fixed 30s windows, 1s tail pad) against GigaAM v3's 11.17% under identical treatment, with the win direction matching the alphacephei table (GigaAM2 8.4-8.6 vs Vosk 0.54 10.7-11.0). Verdict: do nothing on vosk-api; optionally extend the catalog with the streaming Vosk variant through sherpa if progressive display at light size matters.

## New empirical results (this session, strongest evidence)

All artifacts under `/tmp/vosk-research` (session-local; the benchmark harness is `/tmp/gigaam-r2`, preserved from the GigaAM segmentation session).

**1. Byte-identity of the catalog entry with alphacep's official export.** Downloaded `am-onnx/*` and `lang/tokens.txt` from `alphacep/vosk-model-ru` and hashed them:

| file | sha256 | matches catalog pin in `app/src/main/assets/external-catalog/russian-small.json` |
|---|---|---|
| encoder.int8.onnx (70,876,638 B) | `eb6c12fb...09d9ec407` | yes |
| decoder.onnx (2,093,080 B) | `dcbe1ffa...72df12136` | yes |
| joiner.int8.onnx (259,417 B) | `93f2e1d1...6ba1fa407` | yes |
| tokens.txt (6,388 B) | `93bbbc0b...5a05e0fdf6` | yes |

The "Russian (light)" entry (69.85 MiB of int8 files) is exactly Vosk 0.54 "big" ru, and the csukuangfj repo it downloads from is a re-host of the identical bytes (its README says "Models in this directory are from https://huggingface.co/alphacep/vosk-model-ru/tree/main").

**2. Decode verification under our own sherpa-onnx (eval/.venv, sherpa 1.13.5).** On alphacep's bundled 7s `test.wav`:

| model (int8 files, am-onnx) | sherpa API | result |
|---|---|---|
| vosk-model-ru 0.54 big (73.2MB) | OfflineRecognizer | "родион потапыч высчитывал каждый новый вершок углубления и давно определил про себя" (clean) |
| vosk-model-ru 0.54 big | OnlineRecognizer | rejected at native init: `online-zipformer2-transducer-model.cc: 'encoder_dims' does not exist in the metadata` (it is an offline export) |
| vosk-model-streaming-ru 0.56 (72.5MB) | OnlineRecognizer | decodes; drops the trailing "про себя" (streaming tail; the app's `tailPadSeconds` flag exists for this class of issue) |
| vosk-model-small-streaming-ru 0.54 (27.8MB) | OnlineRecognizer | decodes; same trailing-word loss |

The app side matches: `russian-small.json` has no `"streaming"` key, so `ExternalModelRecord.streaming` defaults false and `ExternalSherpaBackend` builds the OfflineRecognizer (the TASK-368 online path is gated on `record.streaming`; the Kroko Spanish/German entries set it true). The current configuration is correct, not a bug.

**3. Six-lecture Russian benchmark, Vosk 0.54 int8 vs GigaAM v3.** Same harness, refs, and normalization as the 2026-09-05 GigaAM segmentation report (`/tmp/gigaam-r2/run_seg.py`; normalizer plus rapidfuzz Levenshtein WER), fixed 30s windows, greedy, 6 threads, desktop. Script: `/tmp/vosk-research/run_vosk_ru.py`.

| lecture | Vosk 0.54, no pad | Vosk 0.54, 1s pad | GigaAM v3 fixed30 (1s pad, prior session) | GigaAM v3 fixed25 |
|---|---|---|---|---|
| zaliznyak (philology, reverb) | 15.95 | 15.01 | 17.77 | 16.61 |
| harvard (philosophy) | 3.73 | 3.87 | 2.60 | 2.96 |
| savvateev (mathematics) | 24.06 | 23.31 | 16.02 | 14.63 |
| zhirinovsky (politics) | 12.19 | 12.14 | 7.85 | 7.68 |
| lankov (history) | 19.27 | 19.13 | 8.99 | 9.17 |
| kolodezev (ML) | 17.58 | 16.93 | 13.79 | 13.36 |
| **macro WER** | **15.46** | **15.07** | **11.17** | **10.74** |

Desktop RTF 0.042-0.051 unpadded (20-24x realtime; the padded pass ran 0.06-0.11 on a contended machine). Findings: GigaAM v3 keeps a clear ~4-point macro lead under identical segmentation treatment; the 1s tail pad is worth about 0.4 points for Vosk, so the catalog entry's no-pad default is leaving a small quality margin on the table; Vosk wins exactly one file, the reverb-heavy zaliznyak, consistent with the prior session's finding that short acoustic context is what hurts that file under GigaAM.

**4. AAR and .so measurements (the real artifacts).**

| artifact | measurement |
|---|---|
| vosk-android-0.3.75.aar (Maven Central) | 13,472,638 B total |
| libvosk.so arm64-v8a | 10,042,800 B |
| libvosk.so armeabi-v7a | 8,985,628 B |
| libvosk.so x86 / x86_64 | 10,397,552 / 10,335,120 B |
| classes.jar | 17 class files, 33,986 B |
| dynamic deps (readelf) | liblog, libm, libdl, libc only |
| strings scan | full Kaldi nnet3 + OpenFst embedded (build paths `.../android/lib/build/kaldi_arm64-v8a/...`); zero occurrences of "onnx" |
| POM extra dependency | net.java.dev.jna:jna:5.18.1 (2,002,994 B jar) |
| our sherpa-onnx.aar (app/libs) | 49,095,090 B; arm64 on-device native total 31.3MB across 4 .so (onnxruntime 21.7MB + c-api 4.5 + cxx-api 0.4 + jni 4.8) |

## Findings by research question

### 1. Runtime footprint of the vosk-api Android integration

- The integration is one prebuilt AAR with a single `libvosk.so` per ABI and a thin JNA-based Java layer: `org.vosk` (LibVosk, LogLevel, Model, Recognizer, SpeakerModel, TextProcessor) plus `org.vosk.android` (SpeechService for the microphone, SpeechStreamService for streams, StorageService to unpack a model from assets, RecognitionListener). Recognizer's surface is `acceptWaveForm` in byte/short/float flavors returning JSON strings from `getResult`/`getPartialResult`/`getFinalResult`, with `setGrammar`, `setMaxAlternatives`, `setWords`, endpointer tuning, and speaker-model hooks. HIGH (read from classes.jar and master source).
- The .so is fully static apart from system libs and carries the complete Kaldi+OpenFst stack; nothing is shared with our onnxruntime-based sherpa stack. Marginal cost alongside sherpa: about 10MB native per ABI plus the JNA jar, plus a second set of R8 keep rules (`org.vosk.**`, JNA reflection) and a second backend to keep alive. HIGH.
- Model format on disk, two generations. The Kaldi generation (what the Android runtime loads; documented layout on the models page): `am/final.mdl`, `conf/mfcc.conf` + `model.conf`, `graph/HCLG.fst` or `HCLr`+`Gr`, `ivector/`, optional `rescore/` and `rnnlm/`. The zipformer generation (HF repos): `am/jit_script.pt` (TorchScript), `am-onnx/*.onnx` (fp32 and int8), `lang/{bpe.model, tokens.txt}`, `lm/{2gram.fst.txt, epoch-99.pt}`. The Android runtime as shipped has no code path for the second generation (model.h is pure Kaldi nnet3; zero onnx strings in the binary). HIGH for the binary claim; the desktop wheels' onnx status was not checked and is irrelevant for Android.
- Official memory guidance: "Small model typically is around 50Mb in size and requires about 300Mb of memory in runtime" (models page; Kaldi-era smalls). HIGH as a quote, MEDIUM as a predictor for the zipformer generation, which we run today through sherpa at ordinary int8 zipformer costs.
- Version state: latest GitHub release tag is v0.3.50 (2024-04-22); Maven latest is 0.3.75 (2025-12-08), matching `version = '0.3.75'` in master's android/build.gradle. HIGH.

### 2. Can Vosk models run on sherpa-onnx instead

- Yes for the entire current generation, by design rather than by conversion. Every alphacep ru repo on HF publishes the ONNX exports itself (all four ru models carry encoder/decoder/joiner in fp32 and int8; the small-streaming one adds chunk64 variants), the READMEs say "Zipformer2 model trained with k2-fsa/icefall", and alphacep's own `decode-onnx.py` runs them with `sherpa_onnx.OfflineRecognizer`. There is nothing to convert: the export is the icefall-standard one sherpa already loads. HIGH.
- The csukuangfj route is the official sherpa route, not a community workaround. The sherpa docs page for zipformer transducer models states "This model is from https://huggingface.co/alphacep/vosk-model-ru/tree/main" and links the export workflow `k2-fsa/sherpa-onnx/.github/workflows/export-russian-onnx-models.yaml` (verified present; it literally curls alphacep's files and repackages them, README included). The docs list the 2024-09-18 package, the 2025-04-20 refresh (what our catalog pins), and the small variant; the streaming docs document the same route for `sherpa-onnx-streaming-zipformer-bn-vosk-2026-02-09` from `alphacep/vosk-model-small-streaming-bn`, so the pipeline is current as of February 2026 and generalizes across languages where alphacep ships am-onnx. HIGH.
- Coverage of the Vosk 0.54 65M model: complete and byte-identical (sha256 table above), and empirically decodable with our pinned sherpa version. The streaming siblings also load: vosk-model-streaming-ru 0.56 (72.5MB int8) and vosk-model-small-streaming-ru (27.8MB int8) both decode under OnlineRecognizer. HIGH.
- What does NOT generalize: the old Kaldi-graph models (vosk-model-small-ru-0.22 and the rest of the models-page list). Those are nnet3+HCLG artifacts with no ONNX export and no sherpa decoder; they run only on the vosk runtime. Since the old small ru is also the weakest current option (22.7-32.0 WER on the open sets in the old table), this is no loss. HIGH.
- Current alphacep ru lineup, all Apache 2.0, all ONNX-exported: big offline 0.54 (73.2MB int8, model card CV ru 6.1), big streaming 0.56 (72.5MB int8, card CV 11.3), small offline (26.7MB int8, card CV 9.8), small streaming 0.54 (27.8MB int8, card CV 11.3). HIGH.

### 3. Quality reality of the alphacephei numbers

- What was measured: the 2025 edition of the alphacep Russian ASR comparison (Nickolay Shmyrev, the Vosk author; post updated through 2025-09-14), WER over 11 Russian test sets. Column set and full table (scraped verbatim; set names translated in parentheses):

| test set | Vosk 0.54 | Vosk 0.54 LODR | GigaAM2 RNNT | GigaAM2 CTC+LM | Vosk Small Streaming 0.54 | Whisper large v3 | Whisper v3 Turbo |
|---|---|---|---|---|---|---|---|
| Аудиокниги АЦ (AC audiobooks) | 1.2 | 1.3 | 4.4 | 3.4 | 4.1 | 5.8 | 6.5 |
| Ru Librispeech | 9.4 | 9.0 | 5.2 | 4.4 | 14.4 | 9.5 | 9.7 |
| CommonVoice 12.0 | 6.1 | 5.6 | 2.6 | 2.9 | 11.2 | 5.5 | 6.2 |
| Golos Crowd | 3.1 | 3.0 | 2.5 | 2.2 | 5.5 | 14.7 | 14.5 |
| Golos Farfield | 6.2 | 5.9 | 4.4 | 4.1 | 10.1 | 17.6 | 18.7 |
| Sova устройства (Sova devices) | 11.6 | 11.4 | 5.6 | 8.3 | 14.7 | 15.9 | 16.0 |
| Телевещание (TV broadcast) | 16.6 | 16.2 | 14.4 | 13.8 | 19.8 | 17.9 | 18.2 |
| Медицина (medicine) | 15.6 | 15.4 | 10.9 | 9.8 | 17.9 | 13.8 | 13.7 |
| Команды Яндекса (Yandex commands) | 4.4 | 4.3 | 1.9 | 3.4 | 7.1 | 18.6 | 21.8 |
| Звонки заказы (order calls) | 20.0 | 18.8 | 15.5 | 13.7 | 27.9 | 23.7 | 24.8 |
| Звонки поддержка (support calls) | 12.9 | 12.6 | 14.2 | 12.4 | 16.8 | 26.8 | 27.5 |
| **average** | **11.02** | **10.69** | **8.64** | **8.42** | **14.67** | **16.21** | **16.84** |

  Caveats: the table compares GigaAM2 (v2), not our GigaAM v3; the audiobooks set is alphacep's own training domain, so Vosk's 1.2 there is an in-domain number; the page is maintained by the Vosk author (self-reported). "LODR" is not explained anywhere on the page; its meaning is unverified (it tracks Vosk 0.54 minus 0.2-0.4 everywhere, so it is a decode variant, not a different model). HIGH for the numbers as published, MEDIUM for cross-benchmark transfer.
- Voice-message-length evidence: no published Russian eval at voice-message length comparing Vosk and GigaAM exists that I could find. Nearest proxies in the table are the short-utterance and conversational sets, where GigaAM2 leads Vosk 0.54 by roughly 2 to 6 points (Golos Crowd 2.2-2.5 vs 3.1; Sova devices 5.6-8.3 vs 11.6; Yandex commands 1.9-3.4 vs 4.4; order calls 13.7-15.5 vs 20.0). Our own new measurement above is the direct domain answer: GigaAM v3 leads Vosk 0.54 by 3.9 points macro on lecture Russian under identical treatment, and Vosk's one win is the reverb-heavy file. HIGH for our data, MEDIUM for extrapolation to short voice messages.

### 4. Product fit and verdict

- What vosk-api would add over what we already ship: nothing on models. The Android vosk runtime is Kaldi-only, so integrating it would give access to the older, weaker Kaldi model line (small-ru-0.22 at 45MB, 22.7-32.0 WER on open sets), not to the 0.54/0.56 zipformers, which we already run through sherpa at byte-identical quality. The vosk-only extras (grammar reconfiguration on lookahead models, speaker identification, Kaldi-era small models in other languages) do not map to our voice-message transcription use case. The cost is real: about 12MB per ABI of second native stack plus JNA, a second JNI surface to keep under R8, and a second engine in the backend registry. HIGH.
- What the existing entry lacks that is worth considering, all through the existing stack: (a) progressive streaming display for ru at light size, via the verified vosk-model-streaming-ru 0.56 int8 (72.5MB, `"streaming": true` catalog entry, OnlineRecognizer path already built for Kroko/Nemotron; needs the `tailPadSeconds` flag validated, the 7s probe dropped the final words); (b) an ultra-light 27.8MB tier via small-streaming-ru at a known quality cost (14.67 vs 11.02 average on the alphacep table, 11.3 vs 6.1 model-card CV); (c) about 0.4 WER points on the current entry by adding a 1s tail pad (measured 15.07 vs 15.46 macro). All three are catalog-JSON changes, not code. MEDIUM-HIGH.
- **Verdict: do nothing on vosk-api. Do not integrate it as a second native stack. The conversion/re-export route is already shipped, official on both sides, and verified byte-identical.** Extend the catalog only if the streaming light tier is wanted.

## What we should do next (decision package)

1. No vosk-api integration. Close the question; the model line it would uniquely unlock (Kaldi-era models) is strictly worse than what we ship and would cost about 12MB per ABI plus a second native stack.
2. Optional, one JSON file each, no code: a "Russian (light, streaming)" catalog entry from `alphacep/vosk-model-streaming-ru` am-onnx int8 (72.5MB) with `"streaming": true` and a validated tail pad; and/or a 27.8MB small-streaming tier, labeled with its quality tradeoff.
3. Optional quality tweak to the existing entry: `tailPadSeconds: 1.0` (measured 0.4-point macro gain, same mechanism as the Parakeet/GigaAM/Nemotron flags). Device verification required before shipping, as with any catalog change.
4. Keep GigaAM v3 e2e_rnnt as the Russian quality recommendation; Vosk 0.54 stays the storage-light alternative (4.4x smaller, 3.9 points worse on our benchmark).
5. Model-scout watch item: alphacep bumps the ru line quietly on HF (0.54 big offline, 0.56 streaming at check time); the sherpa export workflow picks them up within months. A periodic hash check of the am-onnx dirs covers it.

## Confidence assessment

- HIGH: AAR/.so measurements and their Kaldi-only conclusion (binary inspected, strings and readelf, zero onnx); sha256 byte-identity of the catalog entry; decode verification of all four model classes under sherpa 1.13.5; the six-lecture Vosk benchmark (same harness, deterministic greedy, two tail-pad variants); alphacep/sherpa documentation of the export route; the alphacephei table contents; Java API surface.
- MEDIUM: transfer of lecture-domain gaps to voice-message-length audio (no direct published eval); the tail-pad gain generalizing to other audio; RAM behavior of the zipformer generation on low-end devices (not measured this session).
- LOW/UNVERIFIED: the meaning of the "LODR" column; whether any desktop vosk build embeds onnxruntime (irrelevant to the Android verdict, not checked); on-device RTF for the 65M int8 (desktop RTF 0.05 suggests comfortable headroom, unmeasured on the RMX3853).

## Sources

1. vosk-api repo (master): https://github.com/alphacep/vosk-api (src/model.h Kaldi-only loader; android/lib sources; android/build.gradle version 0.3.75; CMakeLists links kaldi-online2/fstngram)
2. vosk-android 0.3.75 AAR and POM (measured): https://repo1.maven.org/maven2/com/alphacephei/vosk-android/0.3.75/ ; version index https://repo1.maven.org/maven2/com/alphacephei/vosk-android/maven-metadata.xml
3. vosk-api releases/tags: https://github.com/alphacep/vosk-api/releases (v0.3.50, 2024-04-22)
4. Vosk models page (sizes, WERs, Kaldi model structure, RAM note): https://alphacephei.com/vosk/models
5. Vosk Android docs: https://alphacephei.com/vosk/android
6. alphacep/vosk-model-ru (Vosk 0.54 big, ONNX + decode-onnx.py using sherpa_onnx): https://huggingface.co/alphacep/vosk-model-ru
7. alphacep/vosk-model-streaming-ru (0.56 streaming): https://huggingface.co/alphacep/vosk-model-streaming-ru
8. alphacep/vosk-model-small-ru and alphacep/vosk-model-small-streaming-ru: https://huggingface.co/alphacep/vosk-model-small-ru , https://huggingface.co/alphacep/vosk-model-small-streaming-ru
9. alphacephei Russian ASR benchmark 2025 (11-set table, updated 2025-09-14): https://alphacephei.com/nsh/2025/04/18/russian-models.html
10. csukuangfj re-host (byte-identical; README credits alphacep): https://huggingface.co/csukuangfj/sherpa-onnx-zipformer-ru-int8-2025-04-20
11. sherpa-onnx docs, offline zipformer transducers (ru entry credits alphacep + export workflow; bn streaming entry): https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/zipformer-transducer-models.html , https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html
12. sherpa-onnx export workflow: https://github.com/k2-fsa/sherpa-onnx/blob/master/.github/workflows/export-russian-onnx-models.yaml
13. Local: `app/src/main/assets/external-catalog/russian-small.json` (sha256 pins), `app/src/main/java/com/antivocale/app/transcription/ExternalSherpaBackend.kt` (TASK-368 online gate), `app/libs/sherpa-onnx.aar` (size baseline), `eval/.venv` (sherpa-onnx 1.13.5), and the prior session's harness `/tmp/gigaam-r2/run_seg.py`; new scripts and raw outputs in `/tmp/vosk-research/` (`run_vosk_ru.py`, `results_vosk_ru.json`, hashes and downloaded artifacts).
