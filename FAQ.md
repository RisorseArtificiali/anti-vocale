# FAQ

Questions that come up frequently, mostly collected from real [issue reports](https://github.com/RisorseArtificiali/anti-vocale/issues).

## Models and their limits

### How long can an audio file be?

There is no app-level limit. Each model has its own **per-segment** limit, and the app transparently splits longer audio into chunks and concatenates the transcripts:

| Model | Per-segment limit | Long audio |
|---|---|---|
| Whisper | 30 s | chunked by the app, any length |
| Qwen3-ASR | 30 s | chunked by the app, any length |
| Parakeet TDT | 1:00 app-side chunking (the model's own hard cap is 6:40) | chunked by the app ([#50](https://github.com/RisorseArtificiali/anti-vocale/issues/50)), chunk length sized to free RAM ([#44](https://github.com/RisorseArtificiali/anti-vocale/issues/44)) |
| Gemma (LLM) | 30 s | currently one segment |
| Nemotron 3.5 (streaming) | no known limit (streams) | n/a |
| GigaAM v3 | no known limit | n/a |

The Parakeet limit is not arbitrary: the model's attention has a hard 5000-frame cap baked into NVIDIA's checkpoint, and 5000 frames at 12.5 frames/s is exactly 400 seconds. Inputs beyond it fail natively; the app-side chunking that removes this limitation landed in [#50](https://github.com/RisorseArtificiali/anti-vocale/issues/50). Since [#44](https://github.com/RisorseArtificiali/anti-vocale/issues/44) the app chunks Parakeet at 1 minute rather than just under the native cap: attention memory grows with the square of the chunk length, and a single 6-minute pass peaks at over 5GB of RAM, enough to starve an 8GB phone. The chunk length also tightens automatically on devices with little free memory.

Every "any length" in the table above means *the app does the splitting for you in software*: no model itself handles arbitrary length in one pass. The two "no known limit" rows are models with no measured cap and no app-side splitting.

The absolute ceilings ([#73](https://github.com/RisorseArtificiali/anti-vocale/issues/73)): 2 hours for the streaming decode path, and files above 2GB are refused. With VAD enabled the whole audio is decoded into memory at once, so the maximum length depends on your device's memory (typically 10 to 25 minutes); the error message tells you the exact limit on your device. Turn VAD off for long recordings, or use a model the app chunks automatically.

### Why did my transcription stop while the app was in the background?

Some Android phones (notably several OEM skins) kill apps in the background even when they are legitimately running a foreground service; the transcription is interrupted and only closes when you reopen the app. Anti-Vocale detects this and, after it happens, offers a one-tap fix in Settings > Advanced: adding the app to the battery-optimization exemption list. On some devices you may additionally need to allow background execution in the manufacturer's own battery settings.

### Can I control where the chunk boundaries fall?

Yes, via **Settings → Strip Silence (VAD)**. With VAD on, boundaries fall on detected silence gaps (segments are merged up to ~28 s, WhisperX-style, no overlap so no repeated words). With VAD off, the app makes blind fixed-duration cuts.

### Why keep models with tight limits like Gemma's 30 seconds?

Because no model is universally better: different models win on different languages, accents, and recording conditions. We keep the choice with the user, and the [performance stats](#where-do-i-see-which-model-was-used-and-how-long-it-took) give you the data to compare on your own device.

Also, Gemma is not just another transcriber: it is a full LLM, the only model in the app capable of generative post-processing (summarization, restructuring, formatting). The 30-second cap limits the audio input, not that capability. The prompt driving it is customizable in **Settings → Transcription → Default Transcription Prompt**, with ready-made examples.

## Queue and concurrent requests

### What happens if I share a second audio while one is transcribing?

Nothing is lost. Requests are processed strictly in order: the second item is queued and starts automatically when the first finishes. Duplicate shares of the same item are deduplicated.

You get feedback from several places:

- a toast saying **"Added to queue"** when you share during an active transcription
- the foreground notification shows the queue position (**"Processing 2 of 3…"**) with the queued count
- each completed item gets its own result notification
- the **Logs** tab shows one entry per transcription with its state

### Where is the queue list?

The Logs tab *is* the list: every transcription appears there with a status (pending/done/error), timestamp, and processing time. The pending state currently lumps together "queued" and "actively processing"; splitting those into distinct labels is tracked in [#51](https://github.com/RisorseArtificiali/anti-vocale/issues/51).

## Results and metadata

### Where do I see which model was used and how long it took?

In the **Logs** tab: each entry shows "Processed in Xs" under the transcript, along with the timestamp and the audio duration. The model name is being added there, and a settings toggle will optionally surface a details row (model, time, task id) on result entries as well ([#45](https://github.com/RisorseArtificiali/anti-vocale/issues/45)).

### How do I delete or manage log entries?

Swipe an entry to delete it. A standard long-press context menu is being added alongside the gesture ([#52](https://github.com/RisorseArtificiali/anti-vocale/issues/52)), with options like delete, re-transcribe, and copy.

## The project

### Is this app built with AI?

Yes, and openly. A coding agent executes most of the day-to-day development; the maintainer designs, reviews, and owns every change, and signs every release. The reason is practical: the app's quality surface is a matrix of phones, inference backends, models, and languages, far too vast to validate the way a narrower product could, and published accuracy numbers rarely match real phones because they are measured on desktop machines with full-precision models, before quantization. Heavy AI assistance plus feedback from people on real devices is the only sustainable way to cover that matrix. What protects you is the bar every change must pass: the automated test suite, and verification on a real device before release for anything that changes behavior; the measurements behind the fixes are public in the issues.

### Will this app become obsolete once Android ships its own on-device transcription?

Most likely, eventually, yes. If the operating system transcribed every voice message natively, in every language, inside every app, a third-party tool for the same job would lose its reason to exist, and that would be a good outcome. Until then there is plenty of gap left to fill: built-in transcription is still language-limited (WhatsApp's, for example, supports a fixed shortlist, four languages on Android, and a longer list on iPhone that depends on the iOS version), OS features do not let you pick a model or load a community fine-tune for your language, and several messengers ship nothing at all.
