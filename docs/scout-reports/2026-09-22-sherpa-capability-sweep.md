# sherpa-onnx capability sweep (TASK-590)

Date: 2026-09-22
Pin: v1.13.8 (srclib 11afbd0, matches `.sherpa-version`); master compared at 040afe3.
Method: subagent sweep with the check-the-real-artifact rule: the clone was checked
out at the v1.13.8 tag, and every "is it in our AAR" claim comes from the pinned
`app/libs/sherpa-onnx.aar` itself (classes.jar listing + `nm -D` on
`jni/arm64-v8a/libsherpa-onnx-jni.so`, 133 JNI natives; AAR strings confirm 1.13.8).
Spot-checked locally: the 19-model offline list, the AAR class presence (incl. the
OfflineDiacritization Kotlin-class absence), and the hotwords call site.

## Capability matrix (v1.13.8, AAR-verified)

| capability | in AAR | our status | gap value / why-not |
|---|---|---|---|
| Offline ASR, 19 model types (offline-model-config.h:30-49: transducer, paraformer, nemo_ctc, whisper, fire_red_asr, tdnn, zipformer_ctc, wenet_ctc, sense_voice, moonshine, dolphin, canary, cohere_transcribe, omnilingual, funasr_nano, medasr, fire_red_asr_ctc, qwen3_asr, telespeech_ctc) | yes | INTEGRATED (backbone) | n/a |
| Streaming ASR (6 types + parakeet-unified autodetected) | yes | INTEGRATED (Nemotron) | n/a |
| VAD (silero + ten-vad) | yes | INTEGRATED (silero; ten-vad class present but unused) | second VAD engine available if silero regresses |
| Speaker diarization (pyannote segmentation + fast clustering, per-segment confidence since 1.13.8) | yes | INTEGRATED (GH #83, SpeakerDiarizer) | n/a |
| Speaker embedding manager + SpeakerRecognition (enroll/verify) | yes (javap: add/search/verify) | ABSENT (embeddings only inside diarization) | label diarized turns with enrolled names; costs enrollment UX + voiceprint-privacy decision |
| Offline/online punctuation (CT-Transformer) | yes | ABSENT (we use the Gemma pass) | sherpa's is CJK/EN-focused, Italian quality unknown; our LLM pass is better positioned |
| ITN (ruleFsts/ruleFars + in-model flags) | yes (config fields) | partial (in-model sense-voice flag only) | low for Italian: rule graphs are zh/en, no Italian graph known |
| Homophone replacer | yes | ABSENT | Chinese-specific, off-mission |
| Arabic diacritization | natives yes, Kotlin class master-only | ABSENT | Arabic-only; Kotlin wrapper arrives with the next tag if ever needed |
| Keyword spotting | yes | ABSENT | wake-word style, not transcript style; off-mission |
| Audio tagging (zipformer, topK) | yes | ABSENT | pre-flag music/noise before ASR; accuracy on Telegram audio unknown |
| Spoken language ID (whisper impl) | yes | ABSENT | needs a resident whisper model just for LID; our model-side detect covers it |
| Speech denoising offline (gtcrn, dpdfnet) + online | yes | ABSENT | noisy voice messages are common; gain unmeasured on our set, artifacts could feed hallucination |
| Hotwords / contextual biasing (offline + online createStream(hotwords), hotwordsFile/Score) | yes | PLUMBING DELIBERATELY UNUSED (SherpaBackend.kt:709-711 passes "" with the abort-on-biasing comment) | HIGH value: contact names and jargon are the classic voice-message failure; needs a biasing list UI +, on streaming, modified_beam_search (master 3df7ada) |
| Streaming LM rescoring (OnlineLMConfig) | yes | ABSENT | needs per-language RNNLM; Italian availability unknown |
| CTC FST decoding (online; offline HLG is master-only, 15210f7) | online yes | ABSENT | grammar-constrained decoding ceiling is high (GigaAM-class) but needs k2 graph tooling per language |
| Provider configs (cpu/cuda/coreml/nnapi/trt/openvino; QnnConfig per-model on 4 model types; RKNN build flag) | yes | partial (we do our own NNAPI crash recovery, issue #26) | QNN is Qualcomm-only, RKNN Rockchip: wrong silicon for the RMX3853 (MediaTek) |
| TTS (vits, matcha, kokoro, zipvoice, kitten, pocket, supertonic) | yes (8 natives) | ABSENT | off-mission; the only AAR-size lever found (SHERPA_ONNX_ENABLE_TTS=OFF at build, worth quantifying only if size becomes a priority) |
| Two-pass streaming, AEC, source separation, whisper DTW | NOT_FOUND / not on the Android surface | absent | not library capabilities on Android |

## New on master since our pin (11afbd0..040afe3)

Android-relevant:
- 3a82dc5 moonshine v2 decoder mask fix (#3976, fixes our #3975; un-breaks moonshine v2 above ~9.2 s).
- 2edc882 byte-level-BPE character loss in whisper/cohere (#3965; FLEURS he_il 49.5 to 35.4 WER per commit).
- 1d04ac4 Canary derives lang2id from the model vocab instead of silently falling back to English (#3962).
- 15210f7 offline HLG CTC decoding reaches JNI/Kotlin (+ OfflineRecognizerResult.words).
- f007b55 OfflineDiacritization Kotlin wrapper.
- 3df7ada modified_beam_search + hotwords for streaming NeMo transducers (+158 lines in the nemo impl): prerequisite for hotword biasing on our Nemotron streaming backend.
- 9f474b8 docs: reproducible F-Droid AAR builds (process-relevant to our srclib pipeline).

## Top 5 integration candidates (value x cheapness)

1. **Bump the AAR to the next tag** to absorb 3a82dc5 + 2edc882 + 1d04ac4: three
   correctness fixes, one un-breaks a model family we already have configs for
   (moonshine at ModelFamilySupport.kt:831,836). Known four-sync-point procedure.
2. **Streaming hotwords on Nemotron** (3df7ada + existing fields): the classic
   voice-message failure (names, jargon); all plumbing exists at the call site that
   today passes "".
3. **Offline speech denoiser pre-pass (gtcrn)**: classes already in the AAR; needs a
   measured WER/VAD trial on our eval set before any claim.
4. **Speaker ID labels on diarized transcripts**: SpeakerRecognition +
   SpeakerEmbeddingManager are in the AAR and we already compute embeddings; the cost
   is enrollment UX plus a voiceprint-storage privacy decision.
5. **Offline HLG-constrained CTC decoding**: highest ceiling for domain terms on
   CTC models, but requires k2 graph supply per language; watch upstream for a ready
   Italian graph.

NOT_FOUND stated explicitly: two-pass streaming, AEC, offline HLG in the pinned AAR,
OfflineDiacritization Kotlin class in the pinned AAR (natives present), getEmbedding
in the AAR's SpeakerEmbeddingManager, per-model size/license statements in-tree
(library Apache-2.0 per LICENSE:2; individual model metadata UNKNOWN).
