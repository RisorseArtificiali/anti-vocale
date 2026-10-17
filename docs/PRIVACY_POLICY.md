# Privacy Policy - Anti-Vocale

**Last Updated:** October 17, 2026

## Introduction

Anti-Vocale is an Android application that transcribes voice messages locally on your device. This Privacy Policy explains how the App handles your data.

## Data Collection

**Anti-Vocale does NOT collect, transmit, or store any personal data.**

### No Data Leaves Your Device

- All audio processing happens locally on your phone
- No network requests are made for transcription
- No analytics or tracking software
- No user accounts or authentication required
- No cloud services or APIs are contacted

## Permissions

The App requests the following permissions:

### Storage Permissions
- **READ_EXTERNAL_STORAGE / READ_MEDIA_AUDIO**: Required to access shared audio files from other apps
- **WRITE_EXTERNAL_STORAGE**: Required to save downloaded AI models (Android 9 and earlier)

### Foreground Service
- **FOREGROUND_SERVICE / FOREGROUND_SERVICE_SPECIAL_USE**: Required to perform transcription in the background

### Notifications
- **POST_NOTIFICATIONS**: Required to display transcription results

### Media access (opt-in)
- **READ_MEDIA_AUDIO**: Requested only when you enable automatic WhatsApp voice-note transcription. While that feature is on, the App reads the listing of the WhatsApp voice-note folder to detect new notes; a note's audio is read only to transcribe it, on this device. While the feature is off, nothing is read.
- **Notification access (special access, opt-in)**: Requested only when you enable voice-note sender recognition. The App then reads messaging notifications from WhatsApp to learn the sender's name and the voice note's length, keeps them in memory only, and clears them when the feature is turned off. No notification content is stored permanently or transmitted.

### Network
- **INTERNET**: Used ONLY for downloading AI models (not for transcription or data transmission)

## Data Storage

### Audio Files
- Audio files are processed in memory and not permanently stored
- Temporary files are deleted after transcription completes

### AI Models
- Downloaded AI models are stored in your app's private storage
- Models can be deleted through the app's settings

### Transcription History
- Transcriptions are stored locally in your app's database
- History can be cleared or deleted through the app's interface
- No synchronization or backup to cloud services

### Notification Identity Cache (opt-in)
- With sender recognition enabled, the sender name and voice-note length of recent WhatsApp notifications are held in memory only
- This list never touches disk, is cleared when the feature is turned off, and the process ending forgets it

### Android System Backup
- Android's automatic backup may carry your transcription history through the OS backup transport to whatever cloud account backs up your device
- Excluded from that backup, always: your settings and any credentials you configured (for example a remote-offload API key, HuggingFace login tokens), your speaker voiceprints and enrollment audio, and downloaded models (they re-download); the exclusions also keep the backup under Android's size quota, so the history itself actually rides
- A direct device-to-device transfer (cable migration) keeps everything

### The Backup Archive (Backup and transfer)
- The in-app backup (Settings, "Backup and transfer") writes a zip archive to a folder you choose. The archive contains your settings and your full transcription history, including the transcripts, the sender names of transcribed voice notes, and speaker-labeled text, all readable in plaintext inside the archive
- Anyone who has the archive file can read everything in it. The export shows this disclosure and asks you to confirm before writing, and the file is written only to the destination you picked; the app never sends the archive anywhere else
- Importing an archive merges its rows into your local history on this device; it does not transmit anything

## Data Sharing

**Anti-Vocale does NOT share data with any third parties.**

## Data Security

- All data processing happens on-device
- No data transmission means no risk of interception
- Android's sandboxing protects app data from other apps

## Children's Privacy

The App is not directed to children under 13. No personal information is collected from any users.

## Changes to This Policy

We may update this Privacy Policy from time to time. Changes will be posted in this document.

## Contact

For questions about this Privacy Policy, please contact: [INSERT EMAIL]

---

**Effective Date:** October 17, 2026
