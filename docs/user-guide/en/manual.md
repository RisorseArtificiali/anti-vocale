# Anti-Vocale User Manual

Anti-Vocale transcribes voice messages on your Android device, entirely offline. Audio never leaves your phone: transcription runs locally with open AI models, no account, no cloud service, no telemetry.

Manual updated for version 1.15.

## Contents

1. [Getting started](#getting-started)
2. [Choosing a model](#choosing-a-model)
3. [Transcribing: the daily flow](#transcribing-the-daily-flow)
4. [WhatsApp voice notes, automatically](#whatsapp-voice-notes-automatically)
5. [Results: labels, formats, and the floating window](#results-labels-formats-and-the-floating-window)
6. [Long audio, queue, and retries](#long-audio-queue-and-retries)
7. [Community models and imports](#community-models-and-imports)
8. [Tasker and automation](#tasker-and-automation)
9. [Per-app settings](#per-app-settings)
10. [Backup and transfer](#backup-and-transfer)
11. [Privacy](#privacy)
12. [Troubleshooting](#troubleshooting)
13. [FAQ](#faq)

Sections 8a, 8b, and 8c cover the automation surfaces: the scheduled folder watch, remote offload, and the Open Transcribe provider.

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
- Every transcription is kept in the **Logs** tab with the model used, duration, and processing time. Long-press an entry to retry, copy, delete, or report a bad result by email; **Re-transcribe** runs the same audio through a different model of your choice.
- With a **Whisper or SenseVoice** model, each expanded transcript carries a language chip: the flag of the language the model detected, with a tap to check or pin it when the guess is wrong (small models misfire sometimes). Other model families do not report a detected language, so the chip stays off for them; the toggle in Settings says so and stays disabled.
- Models that detect the language themselves can misjudge it. When a transcript's language clearly differs from your phone's language, a one-line hint under the text points to the language setting for the models that honor it. The hint never changes anything by itself.
- Share the same audio twice and nothing runs twice without asking: if that file was already transcribed with the same model, a small dialog offers to open the existing result or transcribe it again. Anything that changed (a different model, a failed past run) just transcribes.
- With Auto-Save (Settings) every transcript is also written as a .txt file into a folder you choose.

## WhatsApp voice notes, automatically

Anti-Vocale can transcribe new WhatsApp voice notes the moment they arrive, with no taps at all. The text lands in the Logs tab and as a result notification; if Android blocks a background start (for example with the screen off), a one-tap notification starts the transcription instead.

Setup takes one minute, in **Settings > Voice notes**:

1. Turn on **Voice note transcription** and grant audio access when Android asks. If you declined twice, Android shows no dialog any more; the red hint that appears instead opens the system page where the permission can still be granted.
2. Pick the WhatsApp Voice Notes folder once through the guided picker. A wrong folder is refused on the spot; you can change or remove it any time from the same card.
3. Optional: turn on **sender recognition** too, and grant notification access. The Logs row is then labeled with the sender's name.

Two honest rules govern the labels. A name appears only when the note's own notification confirms it, meaning the notification arrived while the feature was on and its reported length matches the audio: names are never guessed. And notes you download manually (with auto-download off) stay unlabeled, because their notification is too old to be trusted. The waiting list of recent notifications lives in memory only and disappears when the feature is off.

Privacy is the same contract as everywhere else: while detection is on, it reads only the WhatsApp voice-note folder listing; a note's audio is read only to transcribe it, entirely on this device; while the feature is off, nothing is read at all.

One caveat, stated plainly: on phones with aggressive battery managers, notes that arrive while the system has suspended the app can be missed until it wakes. If you rely on the automatic mode, excluding Anti-Vocale from battery optimization makes it reliable. WhatsApp only for now; Telegram is on the roadmap.

## Results: labels, formats, and the floating window

- **Speaker labels**: opt in from Settings and transcripts of conversations get per-sentence speaker attribution, rendered as SPEAKER turns in the result and every export.
- **Playback**: the original audio plays right from the expanded row. Play and pause, a position bar to scrub, playback speed, and a skip-silences mode for recordings with long pauses, which jumps the gaps instead of playing them through.
- **Share as more than text**: from a result row, share as plain text, timed text, SRT, or VTT. The timed formats need a transcription that carries cues. "Share as..." is opt-in from Settings.
- **Picture-in-Picture**: while a transcription runs, a floating window can show the text appearing live, so you can keep using other apps.
- **Re-transcribe**: retry any entry with a different model straight from its row, and compare.

## Long audio, queue, and retries

- Any audio length works with any model: longer recordings are split and stitched automatically. (Older versions had a 6:40 limit with Parakeet; that is gone.)
- Share several messages in a row: they queue up. Each queued item can be cancelled individually from its notification while another transcription runs.
- A failed transcription can be retried with one tap from the Logs tab.

## Community models and imports

The built-in catalog does not cover every language. Anti-Vocale ships with a community catalog of extra models that you import with two taps: Model tab, Advanced, ONNX Sherpa, Import from catalog, filter by your language, tap the model, confirm. The catalog grows continuously and holds 30+ validated entries today: Arabic (dialectal, full-size and light), German (streaming and a Whisper fine-tune), Spanish (streaming and light Moonshine), Swiss German, Hebrew, Persian, Russian, Vietnamese, Korean, Ukrainian, six Indic languages (Bengali, Gujarati, Hindi, Marathi, Tamil, Telugu), English and French Canary Flash, SenseVoice, Dolphin, Orukeet (a Parakeet fine-tune covering 24 languages incl. Italian), and a low-RAM multilingual Whisper Tiny. If your language is missing, open an issue: that is how entries get added.

Advanced users can also:
- import a model from a Hugging Face repository URL or a catalog entry link (the advanced branch in the same dialog);
- import a set of model files from a folder on the phone;
- export a downloaded model as an **offline bundle** and import it on another device, so a model downloaded once moves without re-downloading;
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

## Open Transcribe (experimental)

Apps that speak the open Open Transcribe contract, like Forkgram (a Telegram client), can use Anti-Vocale as their in-place voice-message transcriber: enable **Settings > Automation > Open Transcribe API** (off by default), then pick Anti-Vocale in the client's offline-transcription settings. Voice messages are transcribed with the app's own models and the audio never leaves the device. Turning the toggle off removes the app from the clients' pickers again.

## Per-app settings

For each app you share from (WhatsApp, Telegram, ...) you can configure separately: whether to show the send-back action, whether to auto-copy, and the notification sound. Settings tab, Per-app settings.

## Backup and transfer

Your settings and your whole transcription history can move to another phone. **Settings > Backup and transfer** exports a zip archive to a folder you choose; importing it on any device merges the rows into the local history, and importing the same archive twice changes nothing. Treat the archive file like the data it holds: transcripts, sender names, and speaker labels sit in it in plain text, readable by anyone with the file, which the export itself confirms before writing.

## Privacy

- Transcription is 100% on-device. No audio, no text, no metadata ever leaves your phone.
- The app has no internet permission for transcription; network is used only when you explicitly download a model.
- Logs stay on your device and are yours: clear them any time from the Logs tab.
- The manual backup archive (Settings > Backup and transfer) holds transcripts, sender names, and speaker labels in plain text: anyone with the file can read them. The app never sends the archive anywhere; where it goes is where you put it.
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

**Automatic voice notes stopped arriving.**
Two usual causes. If WhatsApp's folder was deleted and recreated (a reinstall, a cleanup tool), the stored folder permission can die: open Settings > Voice notes and pick the folder again from the card. If your phone suspends background apps aggressively, notes that arrive while the app is suspended are missed until it wakes: the battery exemption above is the fix, and it matters more for this feature than for manual sharing.

## FAQ

**Does it work without internet?**
Yes. After downloading a model, transcription works entirely offline.

**Which messaging apps are supported?**
Any app that can share an audio file. The send-back action currently targets a subset of apps (WhatsApp, Telegram, and others detected automatically).

**Where are my transcripts?**
In the Logs tab, and optionally as .txt files in a folder you pick. Nothing is stored anywhere else.

**Can it transcribe voice notes automatically as they arrive?**
Yes, for WhatsApp: turn on Settings > Voice notes, grant audio access, and pick the WhatsApp Voice Notes folder once. New notes are transcribed as they land, and the row can carry the sender's name. The scheduled folder watch remains the generic route for any app's media folder (Android 11+), and Telegram is on the roadmap. On phones with aggressive battery managers, notes arriving while the app is suspended can be missed: the battery exemption makes the automatic mode reliable.

**Why does a voice note have no sender name?**
The name appears only when the note's notification arrived while sender recognition was on and its reported length matches the audio. Notes downloaded manually (auto-download off) and notes whose notification never arrived get no name, on purpose: a wrong name is worse than no name.

**Why are there two stores (Play and F-Droid)?**
Same app, same features. F-Droid builds it from source with no proprietary components; Play adds automatic crash reporting.

**Is it really private?**
Yes. The source code is open; you can verify that no data leaves the device. See the repository privacy policy.

---

Found an error or something missing? Open an issue on [GitHub](https://github.com/RisorseArtificiali/anti-vocale/issues).
