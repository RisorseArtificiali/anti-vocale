# Research Report: IME voice dictation feasibility (TASK-712 probe, GH #115)

**Date**: 2026-10-01
**Depth**: deep (pa:research; crw + gh api evidence)
**Confidence**: HIGH on platform capability and production precedent; MEDIUM on Play policy specifics (official policy text not retrieved this pass; see confidence section)

## Executive Summary

Yes: an Android input method can implement continuous, on-device voice
dictation today, and the production architecture is already proven on Google
Play. The working pattern is a two-part design: the IME service renders the
keyboard and inserts text through `InputConnection`, while a **foreground
service typed `microphone`** holds the `AudioRecord` and runs the ASR engine.
This is exactly how VocaPhone (on Google Play, Android 13+, on-device models,
sherpa-class stack) ships, and it maps one-to-one onto infrastructure
Anti-Vocale already has (foreground services, the sherpa engine, streaming
recognizers). The hard restriction people remember is iOS-only: keyboard
extensions there cannot touch the microphone; Android has no such wall.

## Findings

### 1. Platform capability (HIGH)

- An IME app declares `RECORD_AUDIO` like any app and requests it at runtime;
  VocaPhone's manifest carries `RECORD_AUDIO` plus a
  `DictationService` declared with `android:foregroundServiceType="microphone"`
  beside the `VocaPhoneInputMethodService` (BIND_INPUT_METHOD) that "writes
  through InputConnection and never reads field text" (verified in the
  production manifest, VocaHQ/vocaphone@main).
- Android 11+ restricts microphone access to "while in use" states: the app
  must be visible/foreground or hold a microphone-typed foreground service
  started while in use (developer.android.com FGS restrictions pages). A
  microphone-typed FGS is the sanctioned way to keep capture alive across the
  IME lifecycle, including while the user's focus app is another app.
- The platform `SpeechRecognizer` path also exists
  (`createOnDeviceSpeechRecognizer`, availability-gated, Android 12+), but it
  is NOT the path a serious on-device keyboard takes: VocaPhone runs its own
  bundled model after an in-app download, exactly our engine situation. We
  would run our own sherpa streaming recognizer inside the microphone FGS.

### 2. Precedents (HIGH)

- **VocaPhone** (VocaHQ, AGPL-3.0, on Google Play for Android 13+): "a normal
  system keyboard: select it when you want to dictate"; on-device
  speech-to-text after a model download; inserts at the cursor via
  InputConnection; optional self-hosted gateway, never required. Their README
  states the constraint asymmetry explicitly: "iOS keyboard extensions cannot
  access the microphone" (iOS records in the containing app and shares session
  state); Android records in the keyboard stack directly.
- **Ramblr Voice** (F-Droid): a voice-only input method doing on-device
  transcription; second independent precedent of the IME+ASR shape.
- **Gboard** voice typing: the mass-market proof that a keyboard holding the
  microphone is a normal, accepted app category (closed source; no new
  evidence gathered this pass beyond its existence).
- A Jetpack Compose custom IME with a voice-input button exists as a tutorial
  artifact (anuragkanojiya1/Custom-Android-IME, Groq cloud backend),
  confirming the IME-side audio capture wiring is not exotic.

### 3. Play policy (MEDIUM)

- The concrete, verifiable datum: a microphone-using system keyboard with
  on-device processing is **live on Google Play today** (VocaPhone,
  com.vocahq.vocaphone), so the category is shippable.
- From training memory (labeled unverified this pass): Play's User Data
  policy requires IMEs to have a privacy policy, limits collected data to
  what the feature needs (keystrokes/audio only for the input function), and
  prominent disclosure applies to audio capture. VocaPhone's README privacy
  posture ("writes through InputConnection and never reads field text",
  on-device by default) is the shape reviewers expect.
- For our app the policy surface is favorable: on-device only, no network
  path in the IME, existing privacy documentation, and the mic permission
  already declared and disclosed for the transcription feature.

### 4. What this means for TASK-712 (the go/no-go input)

The probe's verdict: **feasible with a bounded architecture**:
`VoiceImeService` (IME UI, InputConnection commit of streaming partials) +
`microphone`-typed FGS reusing InferenceEnqueue/the streaming backend +
existing model catalog. The main NEW UX surface is the keyboard itself
(tap-to-start/tap-to-stop per the #115 reporter's ask, live text while
speaking); the engine, models, and queue all exist. Risks to price in: IME
UI is a new surface class for this app (theming, per-app password fields
where the IME must not activate), and F-Droid's build has no IME
complication (nothing Firebase touches the IME path).

## Confidence Assessment

- HIGH: platform capability (production manifest verified), the
  IME + microphone-FGS architecture, iOS-vs-Android asymmetry, VocaPhone and
  Ramblr precedents.
- MEDIUM: exact Play policy clause wording (official page not retrieved this
  pass; the category's Play presence is the strong empirical signal).
- Not verified: Gboard's internal path; whether any OEM kills microphone FGS
  types aggressively (our own freezer/oem-killer research suggests some do;
  the same TASK-684 detection applies).

## Sources

1. VocaHQ/vocaphone README and production AndroidManifest.xml (gh api, 2026-10-01)
2. https://github.com/VocaHQ/vocaphone ; https://vocaphone.vocahq.com
3. developer.android.com: restrictions on starting FGS from the background; FGS service types (microphone while-in-use)
4. https://developer.android.com/reference/kotlin/android/speech/SpeechRecognizer (on-device variant)
5. Ramblr on F-Droid (voice-only IME precedent)
6. github.com/anuragkanojiya1/Custom-Android-IME (Compose IME voice-input wiring)
