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

**Links:** [Play Store](https://play.google.com/store/apps/details?id=com.antivocale.app) · [GitHub](https://github.com/RisorseArtificiali/anti-vocale) · F-Droid https://f-droid.org/packages/com.antivocale.app/

*(Update the version line at posting time: the draft predates v1.13; current releases and notes live on the GitHub releases page.)*

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

**Title:** App gratuita e open-source para transcrever audios do WhatsApp no próprio celular, sem enviar nada pra nuvem

**Body (PT-BR):**

Anti-Vocale: transcrição offline de mensagens de voz para Android (português + 25 idiomas)

Se você também recebe aquela mensagem de voz de 7 minutos e não pode ouvir na hora, esse projeto é pra você. O Anti-Vocale transcreve audios do WhatsApp, Telegram e Signal inteiramente no celular, sem nuvem, sem conta, sem internet depois de baixar os modelos.

**O problema (corrigido 2026-10-02):** o WhatsApp hoje JÁ transcreve em português no Android em aparelhos recentes (a transcrição nativa cobre EN/PT/ES/RU/HI; confirme os idiomas atuais no dia do post). O Anti-Vocale não compete com "existe ou não": os diferenciais verificáveis são outros: o Telegram não tem transcrição gratuita no aparelho; as alternativas de terceiros mandam o áudio pra nuvem; o WhatsApp nativo funciona só em aparelhos que receberam o recurso. No Anti-Vocale o áudio nunca sai do aparelho, em qualquer Android 8+.

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

---

## 2b. r/fossdroid: REFRESHED draft (2026-10-01, for the maintainer's review; supersedes paragraph 2 when approved)

**Venue:** r/fossdroid, "Application Release" flair. Re-verify the rules at posting time (last verified 2026-07-12).

**Title:** Anti-Vocale: offline, open-source voice-message transcription for Android (30+ languages, 7 model backends, on-device AI, Apache 2.0)

**Body (EN, first person, ready to edit):**

I'm the developer of Anti-Vocale. It transcribes voice messages entirely on your phone: share a voice note from WhatsApp/Telegram/Signal, and local AI models turn it into text. Nothing is uploaded, there is no account, and it works with the network off. I started it because WhatsApp's native transcription doesn't support Italian on Android, and every alternative I found either uploads your audio or breaks offline.

What it does today (v1.13.x):

- 7 swappable ASR backends: Parakeet TDT multilingual, Whisper (Small/Turbo/Medium plus a dedicated Italian distil), Qwen3-ASR, Nemotron streaming with live text, Gemma via LiteRT. You pick per use.
- 30+ languages covered across the built-in catalog, plus a community catalog of importable external models (7 model families) with one-tap import.
- Speaker labels on transcripts (diarization), optional speaker identification by voice sample, punctuation and smart summaries via an optional on-device Gemma pass.
- Video files too: extracts the audio track and transcribes it, or imports embedded subtitles.
- Auto-copy to clipboard, auto-save transcripts to any folder (Syncthing/Drive), Tasker automation hooks, Material 3 UI localized in 10+ languages.

Where to get it:

- F-Droid (the fully FOSS build, no proprietary binaries): https://f-droid.org/packages/com.antivocale.app/
- GitHub (Apache 2.0): https://github.com/RisorseArtificiali/anti-vocale
- Play Store build exists too; note it ships Firebase Crashlytics for crash reports. The F-Droid build is Firebase-free and built from the same source: I kept both because crash reports from the mainstream build make it better for everyone. If the FOSS-only build is your thing, that one is first-class, not an afterthought.

Honest limits: models are 300MB-940MB downloads (from HuggingFace, once); transcription speed depends on your SoC (mid-range phones are fine, older ones are slow with the bigger models); the app is share-in/share-out by design, so no in-app chat scraping or notification reading.

Feedback welcome, especially transcription quality in your language and which models work best on your hardware.

---

*Notes for the maintainer (delete before posting): the paragraph 2 draft above was July-era (v1.8.0, pre-F-Droid). This refresh: adds the F-Droid FOSS link as primary, turns the Firebase note from an apology into the two-build explanation, lists the 2026 feature set (community catalog, speaker labels, Gemma passes, subtitles), and adds the honest-limits paragraph (FOSS audiences reward it). Numbers to verify at posting: language count (catalog), community-catalog family count, F-Droid latest version.*
