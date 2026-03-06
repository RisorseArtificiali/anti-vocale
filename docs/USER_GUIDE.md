# Anti-Vocale User Guide

## Table of Contents
- [Overview](#overview)
- [Getting Started](#getting-started)
- [Share Back Feature](#share-back-feature)
- [Per-App Settings](#per-app-settings)
- [Troubleshooting](#troubleshooting)

---

## Overview

Anti-Vocale transcribes voice messages locally on your Android device, keeping your audio private and saving you time.

**Key Features:**
- 🎯 **Local transcription** - No internet required
- 🔒 **Privacy-focused** - Audio never leaves your device
- ⚡ **Fast and accurate** - Uses state-of-the-art AI models
- 🔄 **Share Back** - Send transcriptions back to the messaging app

---

## Getting Started

### 1. Share a Voice Message

When you receive a voice message in WhatsApp, Telegram, or another app:

1. Tap the **Share** icon in the messaging app
2. Select **Anti-Vocale** from the share sheet
3. Wait for transcription to complete (usually a few seconds)
4. Receive notification with the transcription

### 2. Copy the Transcription

- **Auto-copy**: For WhatsApp, the transcription is automatically copied to your clipboard
- **Manual copy**: Tap the **Copy** button in the notification

### 3. Share the Transcription

You have several options:

- **Share Back**: Tap "Send to [App]" to send the transcription directly back
- **Share**: Tap the **Share** button to pick any app
- **Paste**: Manually paste the transcription from your clipboard

---

## Share Back Feature

### What is Share Back?

**Share Back** is a one-tap feature that sends transcriptions directly back to the app where the voice message came from.

Instead of:
- Copying text
- Switching apps
- Finding the chat
- Pasting the text

You just:
- Tap "Send to WhatsApp" (or Telegram)
- Done! ✨

### How It Works

1. **Automatic Detection**: Anti-Vocale detects which app shared the voice message
2. **Smart Button**: The notification shows "Send to [App Name]" instead of generic "Share"
3. **Direct Injection**: The transcription is sent directly to the app
4. **Ready to Send**: The app opens with the transcription ready to send

### Supported Apps

| App | Support Type | Notes |
|-----|--------------|-------|
| **WhatsApp** | ✅ Full | Direct text injection |
| **Telegram** | ✅ Full | Direct text injection |
| **Signal** | ⚠️ Clipboard | Copies to clipboard, then open Signal |
| **Other Apps** | ⚠️ Share Sheet | Pick destination manually |

**Legend:**
- ✅ **Full Support**: One-tap to send transcription back
- ⚠️ **Clipboard/Share Sheet**: Manual steps required

### Customizing Share Back

You can customize Share Back behavior for each app:

1. Open **Anti-Vocale**
2. Go to **Settings** tab
3. Tap **Per-App Settings**
4. Select an app (e.g., WhatsApp)
5. Toggle **Quick Share Back**:
   - **ON**: "Send to [App]" button (fastest)
   - **OFF**: Regular **Share** button with picker (more flexibility)

**Recommended Settings:**
- **WhatsApp**: Quick Share Back **ON**
- **Telegram**: Quick Share Back **ON**
- **Other apps**: Quick Share Back **OFF** (unless you know they support it)

### Quick Share Back vs Regular Share

| Feature | Quick Share Back | Regular Share |
|---------|------------------|---------------|
| **Taps required** | 1 | 2 |
| **App selection** | Automatic | Manual (picker) |
| **Best for** | WhatsApp, Telegram | Trying new apps, forwarding to multiple apps |
| **Flexibility** | Low (one app) | High (any app) |

---

## Per-App Settings

Customize how Anti-Vocale behaves for each messaging app.

### Accessing Per-App Settings

1. Open **Settings** tab
2. Scroll to **Per-App Settings**
3. Tap on an app to open its preferences

### Available Settings

#### Auto-Copy Transcription
- **What it does**: Automatically copies transcription to clipboard
- **Best for**: Apps where you usually paste the text (WhatsApp)
- **Default**: ON for WhatsApp, OFF for others

#### Show Share Button
- **What it does**: Shows Share/Share Back button in notification
- **Best for**: All apps - keeps your options open
- **Default**: ON for all apps

#### Quick Share Back
- **What it does**: One-tap "Send to [App]" vs share picker
- **Best for**: Apps you frequently use (WhatsApp, Telegram)
- **Default**: ON for WhatsApp & Telegram, OFF for others

#### Notification Sound
- **What it does**: Sound played when transcription completes
- **Options**: Default, Silent, Chime, Bell
- **Default**: Default (system notification sound)

---

## Troubleshooting

### Share Back Button Not Showing

**Problem**: I don't see the "Send to [App]" button, only "Share"

**Possible Causes**:
1. **App detection failed**: Anti-Vocale couldn't identify the source app
2. **Quick Share Back disabled**: Check per-app settings
3. **Unsupported app**: Some apps don't work with direct sharing

**Solutions**:
1. **Toggle Quick Share Back OFF** in per-app settings to see the share picker
2. **Use regular Share** and manually select the app
3. **Check app compatibility** (see Supported Apps table above)

### App Opens But Text is Missing

**Problem**: WhatsApp/Telegram opened, but the transcription isn't there

**Cause**: The app doesn't support direct text injection (rare)

**Solution**:
1. The transcription was copied to clipboard
2. **Long-press** in the chat input field
3. Tap **Paste**

### "Share Back button not showing" Help Tooltip

If you see a **(?)** help icon next to Quick Share Back in settings:

**Tap it** to see:
- Explanation of Quick Share Back
- How it works
- When to use it vs regular Share

### Known Limitations

1. **App Detection**: On Android 14+, some apps hide their identity
   - **Workaround**: Use regular Share button

2. **Direct Injection**: Not all apps support receiving text this way
   - **Workaround**: Transcription is copied to clipboard automatically

3. **Background Restrictions**: Android may block some actions
   - **Workaround**: Open Anti-Vocale from the share sheet as usual

---

## Tips & Tricks

### Speed Up Transcription

- **Preload models**: Open Anti-Vocale and let it load the model before sharing
- **Use default prompt**: Leave the prompt empty for fastest results
- **Close other apps**: Free up memory for faster processing

### Best Per-App Settings by Use Case

**For WhatsApp users:**
- Auto-copy: **ON**
- Show Share Action: **ON**
- Quick Share Back: **ON**
- Sound: Silent or Chime

**For Telegram users:**
- Auto-copy: **OFF** (Telegram has good forwarding)
- Show Share Action: **ON**
- Quick Share Back: **ON**
- Sound: Default

**For trying new apps:**
- Auto-copy: **ON** (always good to have)
- Show Share Action: **ON**
- Quick Share Back: **OFF** (use share picker)
- Sound: Default

---

## FAQ

### Q: Does Share Back work with all apps?

**A**: No, only with apps that support receiving text via Intent. WhatsApp and Telegram work perfectly. Other apps may fall back to clipboard.

### Q: Why is Share Back sometimes not available?

**A**: On Android 14+, apps can hide their identity for privacy. If Anti-Vocale can't detect the source app, it falls back to the regular Share button.

### Q: Can I forward a transcription to multiple people?

**A**: Yes! Use the **Share** button instead of Share Back, then pick recipients in the messaging app.

### Q: Does Share Back work if the app is closed?

**A**: Yes, Share Back will open the app and prepare the transcription for sending.

### Q: My language isn't transcribing well

**A**:
1. Try the **Whisper** model (better multilingual support)
2. Add a language-specific prompt in Settings → Default Prompt
3. Make sure the voice message is clear and has minimal background noise

---

## Need Help?

- **Check the Tasker Guide**: [TASKER_GUIDE.md](TASKER_GUIDE.md)
- **Build Instructions**: [BUILD.md](BUILD.md)
- **Report Issues**: GitHub Issues

---

*Last updated: March 2026*
*Version: 1.0*
