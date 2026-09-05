# Reddit Posts — Ready to Copy-Paste

Each post is tailored to its venue. Post titles in bold, body below.

---

## 1. r/ItalyInformatica — "Mostrami il codice!" thread

**Venue:** r/ItalyInformatica recurring AutoModerator thread "Mostrami il codice! – La fiera dei vostri programmi" (weekly). Post as a comment in that thread, not as a standalone post.

**Title:** (reply to the AutoModerator thread)

**Body (IT):**

Anti-Vocale — trascrizione offline e open-source dei messaggi vocali per Android (italiano + 25 lingue)

Ciao a tutti! Condivido un'app a cui lavoro da un po': Anti-Vocale, un'app Android che trascrive i messaggi vocali (WhatsApp, Telegram, Signal) completamente offline, sul telefono, senza cloud.

**Il problema:** la trascrizione nativa di WhatsApp non supporta l'italiano su Android (solo inglese, portoghese, spagnolo, russo, hindi). Le alternative esistenti o mandano l'audio in cloud (privacy zero), o smettono di funzionare senza connessione. Anti-Vocale colma questo vuoto.

**Cosa fa:**
- Trascrive l'italiano (e altre 25 lingue europee) con modelli AI che girano sul telefono
- Scegli tra 5 modelli diversi (Parakeet TDT multilingua veloce, Whisper Distil italiano dedicato, Qwen3-ASR, Nemotron streaming, Gemma multimodale)
- Copia automatica negli appunti dopo la trascrizione (incolla direttamente in chat)
- Salvataggio automatico delle trascrizioni in una cartella a scelta (Drive, Syncthing, Dropbox)
- Trascrizione di file video (estrae la traccia audio)
- Visualizzazione progressiva del testo in tempo reale
- UI Material 3, localizzata in italiano e inglese

**Cosa NON fa:** niente cloud, niente account, niente pubblicità. L'audio non lascia mai il telefono. I modelli (300–940MB) si scaricano una volta da HuggingFace.

**Tecnologia:** Kotlin + Jetpack Compose, ONNX Runtime per l'inferenza, modelli pre-quantizzati (int8). Apache 2.0, codice aperto su GitHub.

**Link utili:**
- Play Store: https://play.google.com/store/apps/details?id=com.antivocale.app
- GitHub: https://github.com/RisorseArtificiali/anti-vocale
- Ho appena rilasciato la v1.8.0 con salvataggio in cartella e fix vari

Accetto feedback, specialmente sulla qualità della trascrizione italiana e sui modelli preferiti. Se provate l'app e avete casi dove sbaglia, segnalateli — mi aiuta a migliorare.

---

## 2. r/fossdroid — Application Release flair

**Venue:** r/fossdroid (99.8K subs). Self-promo welcomed under "Application Release" flair.

**Title:** Anti-Vocale — Free, offline, open-source voice-message transcription for Android (25 EU languages, no cloud, Apache 2.0)

**Body (EN):**

I built Anti-Vocale because WhatsApp's native transcription doesn't support Italian on Android — and the alternatives either upload your voice messages to the cloud or break when the network drops. It runs entirely on-device: share a voice note from any chat app, and it transcribes locally using AI models that run on your phone.

**Key points:**
- **Fully offline.** No internet needed. Audio never leaves your phone. Inference via ONNX Runtime.
- **25+ European languages** including Italian, English, French, German, Spanish, Portuguese, Greek, Dutch, Polish, and more.
- **5 swappable ASR backends:** Parakeet TDT (fast multilingual), Whisper Distil-IT (best Italian quality), Qwen3-ASR, Nemotron streaming (progressive display), Gemma multimodal.
- **Open source** (Apache 2.0). No accounts, no ads.
- **Auto-save to a folder** of your choice — point it at a Syncthing/Drive folder and transcripts sync automatically.
- **Video file transcription** (extracts the audio track).
- **Material 3 UI**, localized in English + Italian.

**What it doesn't do:** upload anything, require an account, show ads, or need a network. Models (300MB–940MB each) download once from HuggingFace; pick one and you're set.

**Built with:** Kotlin + Jetpack Compose, ONNX Runtime, sherpa-onnx. Apache 2.0 on GitHub.

**Links:**
- Play Store: https://play.google.com/store/apps/details?id=com.antivocale.app
- GitHub: https://github.com/RisorseArtificiali/anti-vocale
- v1.8.0 just shipped — new folder auto-save + three bug fixes

**Transparency note:** The app currently includes Firebase Crashlytics for crash reporting. I'm working on a de-tracked build flavor for F-Droid/IzzyOnDroid. The source is open and buildable without it; the FOSS audience is important to me and I want to be upfront about this rather than hide it. Feedback on this is welcome.

Feedback welcome — especially on transcription quality and model preferences for different languages.

---

## 3. r/droidappshowcase — Application Release flair

**Venue:** r/droidappshowcase (10.2K subs, purpose-built for app promo). Use "Application Release" flair.

**Title:** [Anti-Vocale] — Offline, open-source voice-message transcription for Android (25 EU languages, model choice, no cloud)

**Body (EN):**

Anti-Vocale transcribes voice messages entirely on-device — no cloud, no API calls, no data collection. Share a voice note from WhatsApp/Telegram/Signal and get the text, with the audio never leaving your phone.

**Why it exists:** WhatsApp's native transcription doesn't support Italian on Android (only EN/PT/ES/RU/HI). Telegram's is cloud-only with a 2/week free limit. Anti-Vocale fills that gap with on-device AI models.

**Features:**
- 5 swappable ASR backends (Parakeet TDT, Whisper Distil-IT, Qwen3-ASR, Nemotron streaming, Gemma)
- 25+ European languages, Italian-first
- Auto-copy to clipboard after transcription
- Auto-save transcripts to a folder (Drive/Syncthing/etc.)
- Video file transcription
- Progressive display — text appears in real time as it decodes
- Per-app notification preferences
- Material 3 UI

**Built with:** Kotlin + Compose, ONNX Runtime, Apache 2.0.

**Links:** [Play Store](https://play.google.com/store/apps/details?id=com.antivocale.app) · [GitHub](https://github.com/RisorseArtificiali/anti-vocale) · v1.8.0 just released.

---

## 4. r/degoogle — Weekly DeGoogle Showcase thread

**Venue:** r/degoogle (509K subs). Weekly "DeGoogle Showcase" thread only — do NOT post standalone.

**Title:** (reply to the weekly showcase thread)

**Body (EN):**

Anti-Vocale — offline voice-message transcription for Android. Transcribes Italian + 25 EU languages entirely on-device (ONNX Runtime + local AI models). No cloud, no Google services for transcription, audio never leaves the phone. Share from WhatsApp/Telegram/Signal → get text. Apache 2.0, open-source.

Features: 5 swappable ASR models, auto-copy to clipboard, auto-save to a SAF folder (for Syncthing/Drive sync), video transcription, progressive display, Material 3.

Play Store: https://play.google.com/store/apps/details?id=com.antivocale.app
GitHub: https://github.com/RisorseArtificiali/anti-vocale

Note: The app currently uses Firebase Crashlytics for crash reporting. A de-tracked build flavor is in progress for F-Droid. Source is open and buildable without Firebase.

---

## Posting notes

- **r/ItalyInformatica:** post as a **reply to the "Mostrami il codice" AutoModerator thread**, not as a standalone post. Italian audience, tech-savvy, no FOSS-tracker sensitivity. Highest conversion for Italian users.
- **r/fossdroid:** use "Application Release" flair. Include the Firebase transparency note — this audience WILL check and will appreciate honesty over hiding it.
- **r/droidappshowcase:** use "Application Release" flair. More general Android audience, less FOSS-sensitive.
- **r/degoogle:** ONLY in the weekly showcase thread. The Firebase note is mandatory here — this is the most privacy-sensitive audience.
- **Timing:** stagger the posts (don't post all four on the same day). Start with r/ItalyInformatica (friendliest, best topical fit), then r/fossdroid 1-2 days later, then the others.
- **Engagement:** reply to comments promptly on the first day — it boosts visibility (Reddit algorithm favors early engagement).
- **Screenshots:** attach 2-3 screenshots to each post if possible (share-from-WhatsApp flow, model selection screen, progressive transcription). These dramatically increase click-through.


---

## 5. r/brasil / r/AndroidBR (PT-BR, Brazil entry, plan 2026-09-05)

**Venue note (verify at posting time):** r/brasil forbids self-promo outside
specific contexts; the safer targets are r/AndroidBR (app posts welcome with
flair) and r/appsdoandroid. Rule-check both on the day; r/brasil only inside
a thread where voice messages or transcription comes up naturally.

**Title:** App gratuita e open-source para transcrever audios do WhatsApp no proprio celular, sem enviar nada pra nuvem

**Body (PT-BR):**

Anti-Vocale: transcrição offline de mensagens de voz para Android (português + 25 idiomas)

Se você também recebe aquela mensagem de voz de 7 minutos e não pode ouvir na hora, esse projeto é pra você. O Anti-Vocale transcreve audios do WhatsApp, Telegram e Signal inteiramente no celular, sem nuvem, sem conta, sem internet depois de baixar os modelos.

**O problema:** a transcrição nativa do WhatsApp no Android não cobre português, e as alternativas existentes ou mandam seu áudio pra nuvem de terceiros, ou não funcionam offline. No Anti-Vocale o áudio nunca sai do aparelho.

**O que faz:**
- Transcreve português e outros 25 idiomas europeus com modelos de IA que rodam no próprio telefone
- Escolha entre 5 modelos (Parakeet TDT multilíngue e rápido, Whisper, Qwen3-ASR, Nemotron em streaming, Gemma multimodal)
- Copia a transcrição automaticamente pra área de transferência (é só colar na conversa)
- Salvamento automático das transcrições numa pasta sua (Drive, Syncthing, Dropbox)
- Transcreve arquivos de vídeo (extraindo o áudio)
- Texto aparecendo em tempo real durante a transcrição
- Interface Material 3, disponível em português

**O que NÃO faz:** nada de nuvem, nada de conta, nada de anúncio. Licença Apache 2.0, código aberto no GitHub.

**Tamanhos:** os modelos têm entre 300MB e 940MB, baixados uma vez só (o mais rápido, Parakeet, tem 640MB).

**Links:**
- F-Droid: https://f-droid.org/packages/com.antivocale.app/
- Play Store: https://play.google.com/store/apps/details?id=com.antivocale.app
- GitHub: https://github.com/RisorseArtificiali/anti-vocale

Aceito feedback, principalmente sobre a qualidade da transcrição em português (usamos o Whisper, e os benchmarks públicos são medidos em outros idiomas, então relatos reais brasileiros ajudam demais). Se testarem e encontrarem erros, me avisem, ajuda a melhorar o modelo pra PT-BR.
