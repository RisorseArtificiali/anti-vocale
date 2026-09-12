# Research Report: "Local transcription" in the AyuGram ecosystem

**Date**: 2026-09-02
**Depth**: exhaustive
**Confidence**: HIGH (four independent evidence classes agree: source code, full git history, official docs, community reports)

## Executive Summary

AyuGram does not offer local voice-message transcription, anywhere in its ecosystem. What it ships is Telegram's stock server-side transcription (`messages.transcribeAudio`, premium-gated) with a client-side premium flag ("Local Telegram Premium") that makes the button appear on free accounts; the server then refuses, which users experience as an infinite spinner. The "local transcription" claim is a content-farm mutation of "Local Premium", reinforced by an unrelated iOS fork (ghostgram) whose GitHub description promises "free local voice transcription" that its code does not contain, and which SEO and TikTok surfaces conflate with AyuGram for iPhone, a product that does not exist.

## Findings

### 1. What AyuGram actually ships (code + history, verified by direct inspection)

- **AyuGram4A** (default branch `rewrite`, 2327 Java/Kotlin files): zero occurrences of whisper, ggml, onnx, sherpa, vosk, or any ASR integration. The only transcription code is stock Telegram's: `TranscribeButton`, `TL_updateTranscribeAudio` handling, `MessagesController.transcribeButtonPressed`. Full-history grep (unshallowed clone) returns exactly ONE matching commit, `4e8b73f3` "[chats] hide transcribe button, emoji status hint, increase speed button for non-prem users": the fork manages the button's VISIBILITY for non-premium users, which is the client half of the local-premium unlock. No JNI for speech, no model download, no weights.
- **AyuGramDesktop** (`AyuGram/AyuGramDesktop`, C++): only the stock `api_transcribes.cpp` path; no whisper/ASR anywhere in the tree or recent history.
- **AyuGramX** (base moeGramX/TGX, full tree materialized, 1122 Java/Kotlin files): no ASR libraries and not even transcription code.
- **AyuSync companion**: the server repo is not public; the 4A client side (`AyuSyncController`, `AyuSyncWebSocketClient`) synchronizes read states and message history over WebSocket and carries nothing transcription-related. Note: a sync server is by construction the opposite of local; any ASR there would be cloud ASR.

### 2. How the transcription actually behaves for users

Telegram's transcription runs on Telegram's servers and is premium-gated. AyuGram's "Local Telegram Premium" flips a client-side flag, so free accounts see the Transcribe button, but the server refuses the request. A user report documents the result verbatim: recognition spins forever ("Идёт бесконечное распознавание"), and the top community answer names the mechanism: AyuGram's built-in premium is visual, so the request fails server-side; the workaround given is to use the official client [1].

### 3. Provenance of the "local transcription" claim

Three reinforcing sources, all secondary:

1. **RU content farms compress "Local Telegram Premium" into transcription features.** A telegra.ph article listing what local premium unlocks includes "Расшифровка голосовых сообщений и кружков" (transcription of voice messages and round videos) twice [2]. An SEO download site states "Local Premium Emulation. It unlocks unlimited reactions and voice-to-text sans subscription" [3]. Neither mentions that the transcription is server-side and stops working.
2. **ghostgram, an unrelated iOS fork, claims the exact phrase and pollutes the search space.** Its GitHub description: "The ultimate Telegram fork for iOS with Anti-Delete, Ghost Mode, and free local voice transcription" [4]. Code inspection of the full tree shows ZERO speech code: the only "transcribe"/"speech" hits are stock telegram-ios resources (`Transcribe.tgs`, voice Lottie icons) and boringssl's `ssl_transcript.cc`; the fork's own code is a `GhostModeController.swift` plus icons. The promised feature does not exist in the repo.
3. **The iOS conflation.** AyuGram has no iOS version (official docs: "We have Android and Desktop versions" [5]; community answers confirm it cannot be installed on iOS [6]). TikTok's discovery space for ghostgram surfaces "AyuGram мод Telegram для iPhone, как скачать AyuGram на iPhone" as related queries [7]: the SEO layer treats ghostgram as "AyuGram for iPhone", transplanting ghostgram's marketing phrase onto the AyuGram name.

### 4. Adversarial pass (attempts to disprove the finding)

- Searched for forks/PRs adding whisper.cpp or any ASR to AyuGram4A or AyuGramDesktop: none (PR lists inspected; commit search zero).
- Searched for ANY Telegram iOS/Android fork with genuine on-device transcription (SFSpeechRecognizer, whisper): no real client implementation surfaced; all "local transcription" hits are bots, CLI tools, and self-hosted servers.
- Inspected the strongest candidate (ghostgram) at code level: failed verification, marketing only.
- GitHub code search returning 0 for AyuGram4A is NOT evidence (forks are poorly indexed); the clone-based grep is the evidence of record.

### 5. Relevance for Anti-Vocale

The user need behind the rumor is real: transcribe a voice message without sending it to anyone's cloud. No Telegram fork serves it; the official app's path is premium + cloud; the fork ecosystem's answer is a fake button. Anti-Vocale's share-target flow is currently the only genuinely local route on Android for Telegram voice messages (and the TASK-432 cap removal removes the main length limitation on it). A whisper.cpp-in-client design, the approach a fork would take, is one we evaluated and superseded with sherpa-onnx multi-model support.

## Confidence Assessment

- HIGH: no local ASR exists in any public AyuGram repo (code + full 4A history + tree scans); AyuGram transcription is Telegram's server API; the claim's propagation path runs through RU SEO articles and ghostgram's description.
- MEDIUM: docs.ayugram.one renders partially via scrape; no transcription claim was found in any reachable page, and the desktop feature list's "Voice & Round Video Messages Seeking" is a seekbar, but a fully-rendered crawl could in principle reveal a stray claim.
- UNVERIFIABLE: the private AyuSync server's internals (repo not public; by architecture it would be cloud, not local, so it cannot rescue the claim).

## Sources

1. https://otvet.mail.ru/question/241072825 : user report, infinite recognition spinner in AyuGram with premium "enabled"; top answer explains the visual-premium mechanism.
2. https://telegra.ph/CHto-delaet-lokalnyj-telegram-premium-Lokalnyj-Telegram-Premium-Raskryvaem-vse-vozmozhnosti-12-20 : RU article listing voice transcription among local-premium unlocks.
3. https://ayugrams.com/apk/ : SEO download site, "Local Premium Emulation... voice-to-text sans subscription".
4. https://github.com/ichmagmaus111/ghostgram : iOS fork whose description claims "free local voice transcription"; tree scan shows no speech code.
5. https://docs.ayugram.one/ and https://docs.ayugram.one/desktop/ : official docs, Android and Desktop only; no transcription feature documented.
6. https://otvet.mail.ru/question/242183032 : AyuGram cannot be installed on iOS.
7. https://www.tiktok.com/discover/Плагины-Для-Ghostgram-Айфон : discovery page conflating ghostgram with "AyuGram for iPhone".
8. Direct inspection (2026-09-01/02): sparse clones of AyuGram/AyuGram4A (full history), AyuGram/AyuGramDesktop, AyuGram/AyuGramX; GitHub trees API scan of ichmagmaus111/ghostgram.
