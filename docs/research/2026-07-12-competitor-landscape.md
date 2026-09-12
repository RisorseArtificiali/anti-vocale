# Competitor Landscape — Voice-Message Transcription (Android)

Research date: 2026-07-12. Verification labels ([single-source], NOT_FOUND) travel with the data.

## Bottom line

WhatsApp's native transcription is on-device and free, but on Android covers only EN/PT/ES/RU/HI — **not Italian** (iOS has Italian since launch via Siri stack). Telegram has no free on-device option (cloud, 2 msgs/week free). No researched competitor offers model choice, video-file transcription, or a genuinely offline+no-data-sharing architecture at scale. Anti-Vocale has a real, defensible niche.

## Native platform features

| Feature | On-device? | Languages | Limits |
|---|---|---|---|
| WhatsApp Voice Transcripts | Yes (per WhatsApp) | Android: EN, PT, ES, RU, HI — no Italian (3+ sources; one source adds Arabic [single-source discrepancy]). iOS: ~20 incl. IT | Off by default; recipient-only; fails often on forwarded notes; absent on Web/Desktop |
| Telegram voice-to-text | No — cloud (described as Google speech [single-source, low-confidence]) | multi | Free: 2/week; Premium ~$4.99/mo |
| Google Recorder | Live: on-device 7 langs (incl. IT); "transcribe again": cloud 42 langs | — | Pixel-exclusive; not a share-target for voice notes |
| Carrier voicemail-to-text | Cloud | EN/ES only | Carrier-dependent |

Sources: blog.whatsapp.com/introducing-voice-message-transcripts (2024-11); wabetainfo.com (2024-11-24); androidpolice.com; telegram.org/faq_premium; play.google.com listing for Google Recorder.

## Direct competitors

| App | Offline? | Price | Installs | Trigger |
|---|---|---|---|---|
| Transcriber for WhatsApp (`it.mirko.transcriber`, Mirko Dimartino, IT) | Ambiguous — INTERNET perm, "stuck on connecting" complaints | Free | **5M+, 4.1★** [aggregator-derived, not re-verified on live listing] | Share |
| WAVO | Likely cloud (freemium 5 min/mo [single-source]) | Freemium | ~22K [AppBrain, single-source] | Share |
| Transcriber for WhatsApp (`csfm.whatsapptranscriber`) | Yes, explicit | Free | 100+ [single-source] | Share |
| Futo Voice Input | Yes (Whisper) | $5–10 one-time [conflicting figures] | — | IME, not a share-target |
| Whisper (F-Droid `org.woheller69.whisper`) | Yes | Free FOSS | — | IME; share-target support NOT_FOUND |
| TranscribeMe (Telegram bot) | No — cloud | Freemium | "2M+ users" [vendor claim, unverified] | Forward to bot |

## Ranked differentiation opportunities

1. **Italian-on-Android gap in WhatsApp's own feature** — strongest, best-corroborated finding; the primary market WhatsApp left unserved.
2. **Telegram has no free/private transcription at all** — offline share-sheet transcription fills it.
3. **Model choice** — no competitor offers swappable ASR backends; surface it as a feature, not an implementation detail.
4. **Video-file transcription** — no competitor advertises it; Anti-Vocale already supports it (verified in codebase).
5. **Reliability vs the incumbent's network dependency** — `it.mirko.transcriber` has recurring "stuck on connecting" complaints; offline architecture structurally avoids this.
6. **F-Droid / de-Googled niche essentially open** (caveat: requires actually removing Firebase — see build.gradle.kts, Crashlytics+Analytics present today).
7. **On-device summarization without hardware gating** — competitors' summaries are Gemini-Nano/Pixel-gated; Gemma backend reaches more devices (feature not yet exposed as summarization today).
8. **Speaker diarization** — white space, nobody does it well on-device; future differentiator, not a current claim.
9. **Forwarded-voice-note robustness** — WhatsApp native often fails on forwards; Anti-Vocale operates on the raw file. Validate empirically before claiming.

## Caveats

Install counts from aggregators, not live Play pages; Telegram cloud claim single-sourced; no hands-on quality testing of competitors was performed.
