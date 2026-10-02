# Play listing refresh (TASK-285 draft, maintainer-review copy)

Drafted 2026-10-02 from the launch kit + the verified differentiators.
Every claim below is verifiable; the claims table at the bottom names the
source of each. Nothing here mentions unreleased features: the listing
must be true at upload time, and 1.14 is not out yet.

## Short description (80 chars max)

### IT (primary market)
```
Trascrizione vocale offline: italiano e 24+ lingue, sul telefono, senza internet.
```
(79 chars)

### EN
```
Offline voice transcription: Italian and 24+ languages, on-device, no internet.
```
(78 chars)

## A/B variants for the Store Listing Experiment (short description)

Experiment arm B should differ on ONE axis. Run IT first (the priority
market per the task).

- B1 (lead with the WhatsApp gap):
  `Vocali WhatsApp in italiano, offline: la trascrizione che l'app non offre.` (74)
- B2 (lead with privacy):
  `Vocali in testo senza cloud: l'audio resta sul telefono. Italiano e 24+ lingue.` (78)
- B3 (lead with models/video):
  `Da vocale a testo, offline. Scelta del modello, file video, 24+ lingue.` (71)

## Full description

### IT
```
Anti-Vocale converte i messaggi vocali in testo direttamente sul telefono, senza connessione. L'audio non lascia mai il dispositivo: nessun cloud, nessun account, nessun dato inviato.

PERCHÉ SERVE
La trascrizione nativa di WhatsApp su Android non supporta l'italiano. Telegram non ha un'opzione gratuita sul dispositivo. Anti-Vocale copre entrambi i vuoti, e funziona anche dove la rete non c'è.

COSA FA
• Condividi una nota vocale da WhatsApp o Telegram e ottieni il testo
• Trascrive l'italiano e oltre 24 lingue europee, con rilevamento automatico
• Scegli il modello: più motori ASR, da veloci a precisi, inclusi modelli dedicati all'italiano
• Trascrive l'audio dei file video
• Copia automatica negli appunti e salvataggio automatico in una cartella (anche su Drive o Syncthing)
• Funziona in aereo: tutto il riconoscimento avviene sul telefono

PRIVACY
Il microfono non viene toccato: l'audio arriva solo quando lo condividi tu. Il riconoscimento è completamente locale. Codice sorgente aperto (Apache 2.0), verificabile su GitHub.

MODELLI
Oltre ai modelli inclusi puoi importarne altri compatibili, e sceglierli in base a lingua, velocità e qualità. Ogni modello dichiara lingue e dimensioni prima del download.

NOTE
Il primo download di un modello richiede una connessione (una volta sola); dopo, tutto è offline. I modelli più grandi funzionano meglio su telefoni con più memoria: l'app lo indica prima del download.
```

### EN
```
Anti-Vocale turns voice messages into text entirely on your phone, with no connection. The audio never leaves your device: no cloud, no account, no data sent anywhere.

WHY IT EXISTS
WhatsApp's built-in transcription does not support Italian on Android. Telegram has no free on-device option. Anti-Vocale covers both gaps, and keeps working where there is no network.

WHAT IT DOES
• Share a voice note from WhatsApp or Telegram and get the text
• Transcribes Italian and 24+ European languages, with automatic detection
• Choose the model: multiple ASR engines from fast to accurate, including Italian-tuned ones
• Transcribes the audio track of video files
• Auto-copy to clipboard and auto-save to a folder (including Drive or Syncthing)
• Works in airplane mode: all recognition is on-device

PRIVACY
The microphone is never touched: audio reaches the app only when you share it. Recognition is fully local. Open source (Apache 2.0), verifiable on GitHub.

MODELS
Beyond the bundled models you can import other compatible ones, choosing by language, speed and size. Every model declares its languages and size before you download it.

NOTES
The first model download needs a connection (once); after that everything is offline. Larger models work best on phones with more RAM: the app says so before you download.
```

## Claims table (every claim names its verification)

| Claim | Source |
|---|---|
| WhatsApp native lacks Italian on Android (EN/PT/ES/RU/HI) | WhatsApp transcript-language list, recheck at upload time |
| Italian + 24+ European languages | Parakeet TDT v3 catalog languages (25) |
| Recognition fully local, no network needed for transcription | The app holds the INTERNET permission for model downloads only; the drafted phrasing scopes the claim to recognition. Keep that scoping |
| Video files | audio-track extraction, shipped (TASK-8 lineage) |
| Auto-save SAF / auto-copy | TASK-539, GH #45, shipped |
| Model choice + import | external-models platform, community catalog |
| Open source Apache 2.0 | LICENSE |

## Upload checklist (maintainer-side)

1. Play Console, Store listing, IT first: paste the short + full description.
2. Start ONE Store Listing Experiment on the short description (arms: control, B1, B2; B3 spare). Let it run to significance before touching the full description.
3. EN listing from the same copy one week later (or together, if the console allows separate locales in one experiment).
4. Recheck the WhatsApp language list the day of upload; if Italian shipped there meanwhile, B1 dies first (it is the only arm that depends on the gap).
