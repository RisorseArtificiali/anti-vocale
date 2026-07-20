# Anti-Vocale Launch Kit

Content for all promotion channels. Every claim is sourced (see docs/research/2026-07-12-competitor-landscape.md). Version: 1.8.0.

---

## Short pitch

### EN
**Anti-Vocale** — free, offline voice-message transcription for Android. Transcribes Italian (and 24+ other European languages) directly on your phone. No internet, no cloud, no data leaving your device. Share a voice note from WhatsApp/Telegram → get the text. Choose between multiple ASR models, transcribe video files, auto-save transcripts to a folder, auto-copy to clipboard.

**Why it exists:** WhatsApp's native transcription doesn't support Italian on Android (only EN/PT/ES/RU/HI). Telegram has no free on-device option. Anti-Vocale fills that gap — fully offline, fully private.

### IT
**Anti-Vocale** — trascrizione offline gratuita dei messaggi vocali su Android. Trascrive l'italiano (e oltre 24 altre lingue europee) direttamente sul telefono. Niente internet, niente cloud, nessun dato che lascia il dispositivo. Condividi una nota vocale da WhatsApp/Telegram → ottieni il testo. Scegli tra diversi modelli ASR, trascrivi file video, salva automaticamente le trascrizioni in una cartella, copia automatica negli appunti.

**Perché esiste:** la trascrizione nativa di WhatsApp non supporta l'italiano su Android (solo EN/PT/ES/RU/HI). Telegram non ha un'opzione gratuita on-device. Anti-Vocale colma quel vuoto — completamente offline, completamente privato.

---

## Comparison table

| Feature | Anti-Vocale | WhatsApp native | Telegram | Transcriber for WhatsApp (5M+) |
|---|---|---|---|---|
| **Offline** | ✅ Fully on-device | ✅ On-device | ❌ Cloud | ⚠️ Ambiguous ("stuck on connecting" complaints) |
| **Italian (Android)** | ✅ | ❌ (EN/PT/ES/RU/HI only) | ✅ (but cloud) | ✅ (but cloud-dependent) |
| **Privacy** | ✅ Audio never leaves phone | ✅ | ❌ Uploaded to servers | ⚠️ Requires internet |
| **Price** | Free, open-source (Apache 2.0) | Free | Free: 2/week; Premium ~$5/mo | Free |
| **Model choice** | ✅ 5 ASR backends (Parakeet, Whisper, Qwen3, Nemotron, Gemma) | ❌ Fixed | ❌ Fixed | ❌ Fixed |
| **Video files** | ✅ Audio track extracted + transcribed | ❌ | ❌ | ❌ |
| **Auto-save to folder** | ✅ (SAF — Drive/Syncthing/etc.) | ❌ | ❌ | ❌ |
| **Source** | Open-source (GitHub) | Proprietary | Proprietary | Proprietary |

---

## Long-form post (Reddit/HN style)

### EN (r/fossdroid, Show HN, r/droidappshowcase)

> **Anti-Vocale — free, offline, open-source voice-message transcription for Android (Italian, 25 EU languages, no cloud)**

> I built this because WhatsApp's native transcription doesn't support Italian on Android — and the alternatives either upload your voice messages to the cloud or stop working when the network drops. Anti-Vocale runs entirely on-device: share a voice note from any chat app, and it transcribes locally using on-device AI models.

> **What makes it different:**
> - **Fully offline.** No internet connection needed. The audio never leaves your phone. Models run on-device via ONNX Runtime.
> - **Italian-first.** Built around Italian voice messages (my primary use case), but supports 25+ European languages including English, French, German, Spanish, Portuguese, and more.
> - **Model choice.** 5 swappable ASR backends: Parakeet TDT (fast multilingual), Whisper Distil-IT (best Italian quality), Qwen3-ASR, Nemotron streaming, and Gemma multimodal. Pick the one that fits your quality/speed tradeoff.
> - **Open source** (Apache 2.0). No trackers, no accounts, no ads.

> **Features:**
> - Share-to-transcribe from WhatsApp, Telegram, Signal, or any app
> - Auto-copy to clipboard after transcription (paste straight back into the chat)
> - Auto-save transcripts to a folder of your choice (point it at Drive/Syncthing for sync)
> - Video file transcription (extracts the audio track)
> - Progressive display — text appears as it decodes, in real time
> - Per-app notification preferences
> - Material 3 UI, fully localized in English + Italian

> **What it doesn't do:** upload anything, require an account, show ads, or need a network connection. The models (300MB–940MB each) download once from HuggingFace; pick one and you're set.

> **Links:** [Play Store](https://play.google.com/store/apps/details?id=com.antivocale.app) · [GitHub](https://github.com/RisorseArtificiali/anti-vocale) · v1.8.0 just shipped with a new "save transcripts to folder" feature and three bug fixes.

> Feedback welcome — especially on Italian transcription quality and model preferences.

### IT (r/ItalyInformatica "Mostrami il codice")

> **Anti-Vocale — trascrizione vocale offline e open-source per Android (italiano, 25 lingue UE, zero cloud)**

> L'ho creato perché la trascrizione nativa di WhatsApp non supporta l'italiano su Android — e le alternative o caricano i tuoi messaggi vocali sul cloud, o smettono di funzionare senza rete. Anti-Vocale funziona interamente on-device: condividi una nota vocale da qualsiasi app di chat, e la trascrive localmente con modelli AI che girano sul telefono.

> **Cosa lo rende diverso:**
> - **Completamente offline.** Nessuna connessione internet necessaria. L'audio non lascia mai il telefono. I modelli girano on-device via ONNX Runtime.
> - **Italiano per primo.** Costruito attorno ai messaggi vocali italiani (il mio caso d'uso principale), ma supporta 25+ lingue europee.
> - **Scelta del modello.** 5 backend ASR intercambiabili: Parakeet TDT (multilingua veloce), Whisper Distil-IT (migliore qualità italiana), Qwen3-ASR, Nemotron streaming, Gemma multimodale.
> - **Open source** (Apache 2.0). Niente tracker, niente account, niente pubblicità.

> **Caratteristiche:** condivisione da WhatsApp/Telegram/Signal, copia automatica negli appunti, salvataggio automatico in una cartella (Drive/Syncthing), trascrizione di file video, visualizzazione progressiva del testo, preferenze di notifica per-app, UI Material 3 localizzata in italiano e inglese.

> **Link:** [Play Store](https://play.google.com/store/apps/details?id=com.antivocale.app) · [GitHub](https://github.com/RisorseArtificiali/anti-vocale) · v1.8.0 appena rilasciata con "salvataggio in cartella" e tre bug fix.

> Feedback benvenuto — specialmente sulla qualità della trascrizione italiana.

---

## Show HN (when ready — one-shot, don't waste it)

> **Show HN: Anti-Vocale – On-device, offline voice-message transcription for Android (25 EU langs, open-source)**

> Anti-Vocale transcribes voice messages entirely on-device using ONNX Runtime + models like Parakeet TDT, Whisper, and Gemma. No cloud, no API calls, no data collection.

> The motivation: WhatsApp's native transcription doesn't support Italian on Android (only EN/PT/ES/RU/HI). Telegram's is cloud-only with a 2/week free limit. Anti-Vocale fills the gap with 5 swappable ASR backends running locally, including a multilingual streaming model (Nemotron) that shows text progressively as it decodes.

> Built in Kotlin/Compose, Apache 2.0, no trackers. Models download once from HuggingFace (300MB–940MB each); the app supports model hot-swapping, video-file transcription, auto-save to a SAF folder (for Drive/Syncthing sync), and per-app notification preferences.

> [Play Store](https://play.google.com/store/apps/details?id=com.antivocale.app) · [GitHub](https://github.com/RisorseArtificiali/anti-vocale)

---

## Screenshot checklist (for TASK-280 AC #1 — capture before posting)

1. **Share from WhatsApp → transcription result** (the core flow, one shot)
2. **Model selection screen** (shows the 5-backend choice — unique differentiator)
3. **Progressive transcription in PiP** (text appearing in real-time)
4. **Settings: Auto-Copy + Save to folder** (shows the new v1.8.0 features)

Take at 1264×2780 (device native). For Reddit, a single combined image or 2-3 max. For Play Store, use the existing screenshots (TASK-137).

---

## Channel sequencing (from the promotion playbook)

1. **This launch kit** → done (this document)
2. **IzzyOnDroid** (TASK-279) → durable asset to link from every post
3. **r/fossdroid + r/ItalyInformatica** (TASK-281) → debug the pitch, low stakes
4. **r/droidappshowcase + r/degoogle** (TASK-282) → second wave
5. **Show HN** (TASK-283) → one polished shot when the listing is stable
6. **AlternativeTo + Lemmy** (TASK-284) → set-and-forget
7. **Second wave** (TASK-286) → verify rules first

## FOSS caveat

The app currently ships Firebase Crashlytics. FOSS audiences (r/fossdroid, r/degoogle, IzzyOnDroid) will notice. Two options:
- **Be upfront** in the post: "the app includes Firebase Crashlytics for crash reporting; I'm working on a de-tracked build flavor (TASK-120.1). The source is open and you can build without it."
- **Land the de-Firebase flavor first** (TASK-120.1), then post in FOSS venues without caveats.

Either is defensible; the upfront approach gets the launch moving faster.
