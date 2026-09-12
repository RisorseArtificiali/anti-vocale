# Auto-Transcription Feasibility (no manual share) + Sender Identification

Research date: 2026-07-12. Verification labels ([single-source], NOT_FOUND) travel with the data — do not strip them when quoting.

## Headline

No mechanism offers a clean, Play-policy-safe path to *fully* automatic WhatsApp/Telegram voice-note transcription with reliable sender ID. Every verified prior-art app (including the 5M+-install "Transcriber for WhatsApp", package `it.mirko.transcriber`, dev Mirko Dimartino — closed-source, share-sheet only) uses the manual share flow, not automatic detection.

## Ranked approaches (feasibility × policy safety)

### 1. NotificationListenerService — medium feasibility, medium-high policy risk (best of the set)
- Detects new voice-message notifications and reads sender name via `Notification.MessagingStyle` / `Person` (stable API since 18): https://developer.android.com/reference/android/app/Notification.MessagingStyle
- **No access to the audio itself** — no `EXTRA_AUDIO_CONTENTS_URI` or equivalent exists (verified against SDK/AOSP).
- Play policy gray zone: Play Protect dev guidance (developers.google.com/android/play-protect/warning-dev-guidance, updated 2025-11-13) lists permitted uses (wearable relay, aggregation, alternate UI) that do NOT include extracting third-party message content [single-source policy enumeration]. Requires prominent opt-in disclosure.
- The ONLY mechanism that yields a sender name.

### 2. File/MediaStore observation (no special permission) — low-medium feasibility, low-medium risk
- `Android/media/` is explicitly part of shared storage and NOT covered by the `Android/data`/`Android/obb` exclusion (verified 2026-07-12: https://developer.android.com/training/data-storage/manage-all-files). WhatsApp voice notes at `Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/` are readable.
- `ContentObserver` on `MediaStore.Audio`/`MediaStore.Files` is the documented way to detect new files, no special permission.
- Gives file arrival, NOT sender identity.
- **Telegram**: incoming voice notes land in `Android/data/org.telegram.messenger/cache` (bugs.telegram.org/c/10927) — unreadable by third parties on Android 11+. Later moves to shared storage are unverified third-party claims. Telegram remains share-sheet-only.

### 3. MANAGE_EXTERNAL_STORAGE — technically works, Play-policy-blocked
- Grants `Android/media` read, but Play's allowed core purposes (support.google.com/googleplay/android-developer/answer/10467955) do not include transcription; documented rejection pattern exists. Not viable on Play.

### 4. AccessibilityService — highest policy risk, do not build on it
- Play restricts `isAccessibilityTool` to disability-assistance apps (answer/10964491); Oct 30 2025 policy update prohibits autonomous action via Accessibility APIs. Risk: suspension/account termination.

## Sender-ID correlation

No open-source implementation found that correlates notification timestamps with new voice-note files (NOT_FOUND). Known failure modes if built from scratch: notification grouping (4+ notifications collapse from API 24), concurrent voice notes from different chats within seconds, no ordering guarantee between notification post and file write/MediaStore index. Treat as unvalidated engineering; label output "likely from X", never asserted.

## Competitive context

- WhatsApp native transcripts (announced 2024-11-21, on-device): **Android excludes Italian** (EN/PT/ES/RU + HI per some sources); iOS has ~20 languages incl. Italian. No announced Android expansion timeline. Durable gap for Italian-market positioning as of mid-2026.
- Telegram transcription: server-side, free tier 2 messages/week (core.telegram.org/api/transcribe), Premium unlimited.

## Recommended architecture (semi-automatic, consent-forward)

1. NotificationListenerService (opt-in, prominent disclosure) to detect voice-note arrival + capture sender name.
2. On detection, post a one-tap "Transcribe this voice note" notification action that feeds the newest file from the already-readable WhatsApp media folder into the existing pipeline — human-in-the-loop for the audio access, near-automatic UX (1 tap vs long-press→share).
3. `ContentObserver` on MediaStore purely for file-arrival detection; sender name attached only as best-effort label ("likely from X").
4. Optional fully-automatic mode (zero-tap) can be offered behind an explicit opt-in for WhatsApp only, understanding the correlation caveats; avoid AccessibilityService and MANAGE_EXTERNAL_STORAGE entirely.
