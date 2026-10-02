# Directory listings kit (TASK-284, maintainer-review copy)

Drafted 2026-10-02. Verified today: no AlternativeTo entry exists (404),
so the listing is a fresh submission. All three actions are external
posts/submissions and stay maintainer-sent. Claims stay within the
verified set used across the promotion kit.

## 1. AlternativeTo submission

Positioned as an alternative to BOTH "Transcriber for WhatsApp" and the
native WhatsApp transcription feature. Submission fields:

**Name:** Anti-Vocale

**URL:** https://github.com/RisorseArtificiali/anti-vocale
(store links go in the listing's own fields once created:
F-Droid https://f-droid.org/packages/com.antivocale.app/ and
Play https://play.google.com/store/apps/details?id=com.antivocale.app)

**License:** Apache 2.0 (Open Source)

**Platforms:** Android

**Alternative to:** Transcriber for WhatsApp; WhatsApp voice-message
transcription

**Short description (EN):**

```
Offline, open-source voice-message transcription for Android. Transcribes
WhatsApp and Telegram voice notes and audio/video files entirely on the
phone: no cloud, no account, the audio never leaves the device. Italian
and 24+ European languages, multiple downloadable ASR models, automatic
language detection, auto-copy and auto-save.
```

**Tags:** transcription, speech-to-text, whatsapp, telegram, offline,
privacy, open-source

## 2. Lemmy: !opensource@lemmy.ml

Confirmed active at research time (2026-09). Post text:

**Title:** Anti-Vocale: offline voice-message transcription for Android (Apache 2.0, F-Droid)

**Body (EN):**

```
I built an Android app that transcribes WhatsApp/Telegram voice messages
entirely on the phone. No cloud, no account: recognition runs on-device
(sherpa-onnx + LiteRT-LM runtimes in the APK; the models download at
runtime, your choice). Italian and 24+ European languages, automatic
language detection, several models from fast to accurate, video-file
audio extraction, auto-copy, auto-save to any folder.

Why: WhatsApp's native transcription does not cover Italian on Android,
and the third-party alternatives upload your audio. If the audio never
has to leave the phone, it shouldn't.

Apache 2.0, source on GitHub, on F-Droid and Play.

- Source: https://github.com/RisorseArtificiali/anti-vocale
- F-Droid: https://f-droid.org/packages/com.antivocale.app/
- Play: https://play.google.com/store/apps/details?id=com.antivocale.app

Happy to answer questions about the on-device stack. Real-language
feedback (especially non-English) is what improves the model
recommendations.
```

(!fdroid@lemmy.world errored at research time: re-check before posting;
if alive, the same text fits there with a shorter title.)

## 3. Mastodon: floss.social

Post AFTER the IzzyOnDroid listing exists (the task's sequencing: the
post points at F-Droid + Izzy as the install sources). Two posts, second
one a week later.

**Post 1:**

```
Anti-Vocale: voice-message transcription that runs entirely on your
Android phone. No cloud, no account. Italian + 24 European languages,
Apache 2.0. #fdroid #android

https://f-droid.org/packages/com.antivocale.app/
```

**Post 2 (a week later, reply to post 1):**

```
Now also on IzzyOnDroid for the Play-averse: same reproducible build,
same Apache-2.0 source. #fdroid

[izzy link]
```

## Sequencing

1. AlternativeTo (no dependency, can go first).
2. Lemmy !opensource (no dependency).
3. Mastodon post 1 (after Izzy inclusion request is granted; until then
   post 1 can still go out pointing at F-Droid alone, and post 2 waits).
