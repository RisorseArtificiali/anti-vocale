# Anti-Vocale User Manual

Anti-Vocale transcribes voice messages on your Android device, entirely offline. Audio never leaves your phone: transcription runs locally with open AI models, no account, no cloud service, no telemetry.

Manual updated for version 1.13.

## Contents

1. [Getting started](#getting-started)
2. [Choosing a model](#choosing-a-model)
3. [Transcribing: the daily flow](#transcribing-the-daily-flow)
4. [Long audio, queue, and retries](#long-audio-queue-and-retries)
5. [Community models and imports](#community-models-and-imports)
6. [Tasker and automation](#tasker-and-automation)
7. [Per-app settings](#per-app-settings)
8. [Privacy](#privacy)
9. [Troubleshooting](#troubleshooting)
10. [FAQ](#faq)

Sections 6a and 6b cover the automation surfaces: the scheduled folder watch and remote offload.

## Getting started

1. Install Anti-Vocale from your store (Google Play or F-Droid) or from a released APK on GitHub.
2. Open the app once. On the **Model** tab you will see the built-in models available for download.
3. Download one model. For most people the recommended first choice is **Parakeet TDT (stock int8, 640 MB)**: fast, small, and it covers 25 European languages.
4. To transcribe, share a voice message from any messaging app (WhatsApp, Telegram, Signal, and others) to Anti-Vocale. A notification appears while it processes, then a second notification with the text.
5. Tap the result notification to copy, share, or send the text back to the chat it came from.

No further configuration is needed. Everything below is optional.

## Choosing a model

Models differ in size, speed, language coverage, and accuracy. The Model tab shows the essential facts on each card before you download. Quick orientation:

| Model | Size | Languages | Notes |
|---|---|---|---|
| Parakeet TDT stock int8 | 640 MB | 25 European | Fast and light; the default recommendation |
| Parakeet TDT SmoothQuant | 862 MB | 25 European | More accurate, heavier; needs more RAM |
| Whisper Turbo | 988 MB | 101 | Best balance in the Whisper family |
| Whisper Medium | 903 MB | 101 | Slower than Turbo, not better for most audio |
| Whisper Small | 358 MB | 101 | Lightest Whisper; decent quality |
| Whisper Distil Italian | 938 MB | Italian only | Best Italian accuracy of the built-in set |
| Qwen3-ASR | 938 MB | Multilingual | Alternative architecture |
| Nemotron streaming | 640 MB | Multilingual | Shows text while you speak (streaming) |
| GigaAM v3 | 326 MB | Russian | Russian specialist |

Rules of thumb:
- If you mostly transcribe one language, a specialist model (Distil Italian, GigaAM) beats a generalist of the same size.
- If your phone has 4 GB of RAM or less, prefer models under 500 MB.
- Gemma models (listed separately on the Model tab) are larger language models that can also transcribe. They are interesting for experimentation but heavier and slower than the dedicated ASR models.

## Transcribing: the daily flow

- Share a voice message to Anti-Vocale. Processing starts immediately, even with the screen off.
- The result notification offers: **Copy**, **Share**, and, when the source app is supported, **Send to [App]** which pastes the text directly into the chat the voice message came from.
- With Auto-Copy enabled (Settings), the text is already on your clipboard when the notification arrives; the notification says so.
- Every transcription is kept in the **Logs** tab with the model used, duration, and processing time. Long-press an entry to retry, copy, delete, or report a bad result by email.
- With a **Whisper or SenseVoice** model, each expanded transcript carries a language chip: the flag of the language the model detected, with a tap to check or pin it when the guess is wrong (small models misfire sometimes). Other model families do not report a detected language, so the chip stays off for them; the toggle in Settings says so and stays disabled.
- With Auto-Save (Settings) every transcript is also written as a .txt file into a folder you choose.
- **Voice-note sender recognition** (optional, Settings): with notification access granted, the History row of a shared voice note can show who likely sent it. The name appears only when the note's notification arrived while the feature was on AND its reported length matches the audio; otherwise no name is shown, never a guess.

## Long audio, queue, and retries

- Any audio length works with any model: longer recordings are split and stitched automatically. (Older versions had a 6:40 limit with Parakeet; that is gone.)
- Share several messages in a row: they queue up. Each queued item can be cancelled individually from its notification while another transcription runs.
- A failed transcription can be retried with one tap from the Logs tab.

## Community models and imports

The built-in catalog does not cover every language. Anti-Vocale ships with a community catalog of extra models that you import with two taps: Model tab, Advanced, ONNX Sherpa, Import from catalog, filter by your language, tap the model, confirm. The catalog grows continuously and holds 30+ validated entries today: Arabic (dialectal, full-size and light), German (streaming and a Whisper fine-tune), Spanish (streaming and light Moonshine), Swiss German, Hebrew, Persian, Russian, Vietnamese, Korean, Ukrainian, six Indic languages (Bengali, Gujarati, Hindi, Marathi, Tamil, Telugu), English and French Canary Flash, SenseVoice, Dolphin, Orukeet (a Parakeet fine-tune covering 24 languages incl. Italian), and a low-RAM multilingual Whisper Tiny. If your language is missing, open an issue: that is how entries get added.

Advanced users can also:
- import a model from a Hugging Face repository URL or a catalog entry link (the advanced branch in the same dialog);
- import a set of model files from a folder on the phone;
- point the app at a different catalog index (the "change" action next to the catalog source) maintained by anyone, for example your community.

The import format and file requirements are documented in [external models](../../external-models.md).

## Tasker and automation

Anti-Vocale accepts a broadcast that Tasker (or any automation app) can send to transcribe a file without touching the UI:

```
Action: com.antivocale.app.PROCESS_REQUEST
Extras: request_type=audio, file_path=/path/to/audio, task_id=your-id
Optional: backend_id=<model id> to pick the model for that request
```

The result comes back as a reply broadcast. The full walkthrough with examples is in the [Tasker guide](../../TASKER_GUIDE.md).

## Scheduled folder watch

Anti-Vocale can watch folders you choose and transcribe new audio files automatically: Settings, Scheduled folder watch, Watch a folder. Scans run on a schedule the system allows; aggressive battery savers can delay them, and the manual Scan now always works. Each new audio file is transcribed with the currently selected model and lands in the Logs tab like any other run. On Android 11 or newer the folder picker can open shared-storage locations such as a messaging app's media folder, which makes automatic transcription of a chat's voice notes possible without sharing each one.

## Remote offload (experimental)

If you run your own transcription server, Anti-Vocale can delegate long or heavy transcriptions to it: Settings, Remote offload (experimental). Any OpenAI-compatible server speaking the /v1/audio/transcriptions API works (OmniVoice, the whisper.cpp server, and others). The in-app disclosure says it plainly: when the feature is enabled, the audio of the transcriptions you delegate is sent only to the server address you enter, wherever that server is (your home network or anywhere on the internet); everything else is transcribed on the device, and the feature is off by default.

## Per-app settings

For each app you share from (WhatsApp, Telegram, ...) you can configure separately: whether to show the send-back action, whether to auto-copy, and the notification sound. Settings tab, Per-app settings.

## Privacy

- Transcription is 100% on-device. No audio, no text, no metadata ever leaves your phone.
- The app has no internet permission for transcription; network is used only when you explicitly download a model.
- Logs stay on your device and are yours: clear them any time from the Logs tab.
- The Play build includes Crashlytics crash reporting (you can see and disable it in Android settings); the F-Droid build has none.

## Troubleshooting

**The transcription never finishes / the notification disappears.**
Some phone brands (Vivo, OPPO, some Xiaomi and Samsung) suspend background apps aggressively. Open Anti-Vocale once and, if it offers, grant the battery exemption; or find the app in battery settings and set it to Unrestricted. The app detects this situation and explains it in a notification when it happens.

**"Not enough memory" or crashes with big models.**
Models list their size on the card. On phones with 4 GB of RAM or less, use models under 500 MB. If a transcription fails with an out-of-memory message, try a shorter file, a smaller model, or closing other apps.

**Transcription quality is poor.**
Try a specialist model for your language (see the table above). Long-press the bad entry in Logs and use Report to send us the details (model, duration, timing; the transcript excerpt only if you choose to include it).

**NNAPI makes it crash.**
If you enabled the NNAPI provider in Settings and the app now crashes, it reverts to CPU automatically on next start. NNAPI depends heavily on the phone's chipset; if it crashes repeatedly, leave it on CPU.

## FAQ

**Does it work without internet?**
Yes. After downloading a model, transcription works entirely offline.

**Which messaging apps are supported?**
Any app that can share an audio file. The send-back action currently targets a subset of apps (WhatsApp, Telegram, and others detected automatically).

**Where are my transcripts?**
In the Logs tab, and optionally as .txt files in a folder you pick. Nothing is stored anywhere else.

**Can it transcribe voice notes automatically as they arrive?**
Not for chat apps directly, yet: it is on the roadmap. Two routes exist today: the scheduled folder watch can watch a messaging app's media folder (Android 11+) and transcribe new voice notes as they land, and the optional sender recognition labels the History row with who likely sent a note you shared.

**Why are there two stores (Play and F-Droid)?**
Same app, same features. F-Droid builds it from source with no proprietary components; Play adds automatic crash reporting.

**Is it really private?**
Yes. The source code is open; you can verify that no data leaves the device. See the repository privacy policy.

---

Found an error or something missing? Open an issue on [GitHub](https://github.com/RisorseArtificiali/anti-vocale/issues).
