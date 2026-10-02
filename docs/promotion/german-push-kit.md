# German push kit (TASK-403, maintainer-review copy)

Drafted 2026-10-02. The precondition (German UI in a store release) has
been met since 1.11; the IzzyOnDroid page for com.antivocale.app returns
404 today (inclusion not requested yet). All three actions are external
posts and stay yours to send; the copy below is ready for review. First
person singular throughout, developer disclosure in the forum replies,
no hype. The three android-hilfe.de thread URLs died with the 2026-08-27
research report (not in the repo anymore): re-find them via the forum
search "Sprachnachrichten transkribieren" before posting; the replies are
written so they fit any of the three asks.

## 1. Mastodon reply to Sebastian Pertsch

Replying to pertsch.social/@Sebastian/117154613701771358. Short, in
German, one fact he cares about (the German UI he could not have yet):

```
Vielen Dank für die Empfehlung! Seit Version 1.11 hat die App auch eine
komplette deutsche Oberfläche. Feedback aus der deutschen Community
nimmt das Projekt direkt auf GitHub auf.
```

## 2. IzzyOnDroid inclusion request

Issue at codeberg.org/IzzyOnDroid/repodata, title
"Inclusion request: Anti-Vocale". Body:

```
**App name:** Anti-Vocale
**Package:** com.antivocale.app
**Summary:** Offline transcription of voice messages (WhatsApp, Telegram) and audio/video files, fully on-device.
**Description:** Anti-Vocale converts voice messages into text entirely on the phone. No cloud, no account: recognition runs on-device via bundled AI runtimes (sherpa-onnx, LiteRT-LM); the ASR models themselves download at runtime, per the user's choice. Italian and 24+ European languages, automatic language detection, multiple models from fast to accurate, community-model import, video-file audio extraction, auto-copy and SAF auto-save. Open source (Apache 2.0), reproducible F-Droid build.
**Website / source:** https://github.com/RisorseArtificiali/anti-vocale
**License:** Apache-2.0
**Track / addition channel:** F-Droid (com.antivocale.app), releases tagged on GitHub.

**Size note:** the APKs exceed the 30 MB rule-of-thumb (arm64 ~36 MB release-signed, x86_64 ~39 MB). The cause is the on-device AI runtime (sherpa-onnx JNI, LiteRT-LM), which must ship inside the APK: it loads at first model use and cannot be deferred. No ASR model weights are bundled; every model downloads at runtime.
```

## 3. android-hilfe.de replies (three threads, one text each)

Each as its own reply in its own thread, with the disclosure line kept.

```
Falls es noch aktuell ist: ich habe dafür eine App gebaut. Anti-Vocale
transkribiert Sprachnachrichten von WhatsApp und Telegram komplett auf
dem Gerät, ohne Cloud und ohne dass das Audio das Handy verlässt.
Italienisch und 24+ weitere europäische Sprachen, mehrere Modelle zur
Auswahl, auch die Tonspur von Videodateien. Open Source (Apache 2.0),
auf F-Droid und im Play Store.

Transparenz: ich bin der Entwickler. Wenn es nicht erwünscht ist, dass
ich hier selbst antworte, sage ich Bescheid und lasse den Thread in
Ruhe.
```

## 4. Play Console (no copy, measurement)

After the push, compare German listing views and installs week-over-week
in Play Console; the German UI shipped in 1.11, so any lift from step 3
is attributable to the push, not the UI change.
