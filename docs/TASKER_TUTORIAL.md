# Tutorial: Auto-Transcribe WhatsApp Voice Messages with Tasker + Anti-Vocale

**Goal:** When someone sends you a WhatsApp voice note, it gets transcribed automatically — no tapping, no sharing. The transcription appears as a notification you can copy or share.

## How it works (in 30 seconds)

```
WhatsApp writes .opus file
        ↓
Tasker detects new file (File Modified event)
        ↓
Tasker sends broadcast intent → Anti-Vocale's TaskerRequestReceiver
        ↓
Anti-Vocale loads model → transcribes → sends broadcast back
        ↓
Tasker catches response → shows notification with transcription
```

## Prerequisites

| What | Why |
|------|-----|
| **Anti-Vocale** installed & model loaded | The transcription engine |
| **Tasker** (paid, from Play Store or website) | The automation framework |
| **Android 11+** with "All files access" for Tasker | Needed to monitor WhatsApp's media folder |
| **WhatsApp** voice notes auto-download enabled | So the file actually lands on disk |

---

## Step 0: Anti-Vocale Setup

1. Open Anti-Vocale
2. Select your preferred transcription model
3. Tap **"Load Model"** and wait for the **"Ready"** status
4. Keep the model loaded (don't force-close the app) — this avoids a 3–5 second cold start penalty

## Step 1: Grant Tasker file access

1. Open **Settings → Apps → Tasker → Permissions**
2. Tap **"All files access"** → **Allow**
3. Without this, Tasker cannot monitor `Android/media/com.whatsapp/...`

## Step 2: Enable WhatsApp auto-download for voice notes

In WhatsApp: **Settings → Storage and Data → When using mobile data** (and Wi-Fi) → ensure **Audio** is checked. This ensures voice notes are downloaded automatically so Tasker can detect them.

## Step 3: Create the "Transcribe Voice" Task in Tasker

This is the core task that sends the audio file to Anti-Vocale.

**In Tasker → Tasks tab → + (new task) → name it `Transcribe Voice`**

Add these actions in order:

### Action 1: Variable Set — generate a unique ID

| Field | Value |
|-------|-------|
| Name | `%av_task_id` |
| Value | `wa_%TIMEMS` |

### Action 2: Wait — let WhatsApp finish writing the file

| Field | Value |
|-------|-------|
| Seconds | `2` |

### Action 3: Send Intent — this is the key action

| Field | Value |
|-------|-------|
| Action | `com.antivocale.app.PROCESS_REQUEST` |
| Extra | `request_type:audio` |
| Extra | `task_id:%av_task_id` |
| Extra | `file_path:%evtfile` |
| Package | `com.antivocale.app` |
| Class | `com.antivocale.app.receiver.TaskerRequestReceiver` |
| Target | `Broadcast Receiver` |

### Action 4: Variable Clear — clean up the response variable

| Field | Value |
|-------|-------|
| Name | `%result_text` |

## Step 4: Create the File Monitoring Profile

This profile triggers when WhatsApp creates a new voice note file.

**In Tasker → Profiles tab → + → Event → File → File Modified**

| Field | Value |
|-------|-------|
| File or Dir | `/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/` |
| Event | `*` (any file change) |

**Link it to the `Transcribe Voice` task.**

## Step 5: Filter out your own sent voice notes

You don't want to transcribe voice notes _you_ sent. Add a filter at the top of the `Transcribe Voice` task:

**Action 0 (insert before everything else): Stop** — skip if it's a sent message

| Field | Value |
|-------|-------|
| If | `%evtfile ~ *Sent*` |

This uses pattern matching — if the file path contains `Sent` (WhatsApp stores your outgoing voice notes in a `Sent/` subfolder), the task stops immediately.

## Step 6: Create the Response Listener Profile

Anti-Vocale sends results back as a broadcast intent. You need a second profile to catch it.

**Profile: "Anti-Vocale Response"**

- Event: **Intent Received**
- Action: `net.dinglisch.android.tasker.ACTION_TASKER_INTENT`

**Task: "Handle AV Response"**

### Action 1: If `%status` eq `success`

### Action 2: Notify

| Field | Value |
|-------|-------|
| Title | `🗣️ Transcription` |
| Text | `%result_text` |
| Icon | (pick a microphone icon) |
| Priority | `High` |
| Persistent | (unchecked — auto-dismiss) |

### Action 3: Else

### Action 4: Notify

| Field | Value |
|-------|-------|
| Title | `❌ Transcription Failed` |
| Text | `%error_message` |

### Action 5: End If

## Step 7: Test it

The easiest way to test:

1. Open a WhatsApp chat (or use WhatsApp Web from another device)
2. Send yourself a short voice note
3. Watch Tasker's run log (Tasker → 3-dot menu → Run Log)
4. You should see: File Modified → Transcribe Voice → Send Intent → (wait) → Response → Notification

Alternatively, test via ADB without recording anything:

```bash
# Copy a test audio file to the WhatsApp voice notes directory
adb push test_audio.opus "/sdcard/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/TEST.opus"
```

---

## What about Telegram?

**Short answer:** Automatic monitoring doesn't work for Telegram on non-rooted Android 11+.

**Why:** Telegram stores voice messages inside `Android/data/org.telegram.messenger/cache/` — this directory is locked down by Android's scoped storage. No other app (including Tasker) can access it.

**Workaround — semi-automatic share:**

1. In Telegram, long-press the voice message
2. Tap **Share → Anti-Vocale** (or a specific backend like "Anti-Vocale (Whisper)")
3. Anti-Vocale transcribes and shows the result notification

This uses Anti-Vocale's existing `ShareReceiverActivity` — no Tasker needed.

---

## Advanced Tips

### Pre-load the model for faster first transcription

Send this intent from Tasker (e.g., at device boot or on a schedule):

| Field | Value |
|-------|-------|
| Action | `com.antivocale.app.PRELOAD_MODEL` |
| Package | `com.antivocale.app` |
| Target | `Broadcast Receiver` |

### Handle rapid-fire voice notes

If someone sends multiple voice notes quickly, each one triggers the task independently. Anti-Vocale's `InferenceService` queues them internally — they process sequentially. Your response listener will fire once per voice note, each with its own `task_id`.

### ADB testing without WhatsApp

```bash
# Send an audio transcription request directly
adb shell am broadcast \
  -a com.antivocale.app.PROCESS_REQUEST \
  --es request_type "audio" \
  --es task_id "test_$(date +%s)" \
  --es file_path "/sdcard/Download/test_voice.m4a"

# Monitor what happens
adb logcat -s TaskerRequestReceiver InferenceService
```

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| Tasker never detects the file | Check "All files access" is granted to Tasker. Verify the path exists in a file manager. |
| "MODEL_NOT_LOADED" error | Open Anti-Vocale, load the model, try again. Consider the preload intent at boot. |
| Transcription starts but no response comes back | Increase timing. 30s audio → ~15s warm processing. Check Tasker run log for the response event. |
| Fallback notification appears ("Tap to process") | Android 12+ blocked the background service. Go to **Settings → Apps → Anti-Vocale → Battery → Unrestricted**. |
| Works for some voice notes but not others | WhatsApp may still be writing the file. Increase the initial Wait to 3–4 seconds. |

---

## How it works under the hood

Anti-Vocale's `TaskerRequestReceiver` uses a **broadcast intent** pattern rather than a bound service. This is ideal for Tasker because Tasker's "Send Intent" action natively supports broadcasts, and they're fire-and-forget — no connection lifecycle to manage.

On Android 12+, starting a foreground service from the background is restricted. If the direct service start is blocked by the OS, the receiver posts a **high-priority notification** with a pending intent that launches a trampoline activity. The notification tap counts as a user-initiated action, satisfying the OS restriction and allowing the service to start.

The response uses `net.dinglisch.android.tasker.ACTION_TASKER_INTENT` — this is Tasker's documented external intent action. Tasker automatically parses extras from intents matching this action into local variables (`%status`, `%result_text`, etc.) without any plugin setup.

---

## Quick Reference — Intent Protocol

### Request (Tasker → Anti-Vocale)

| Field | Value |
|-------|-------|
| **Action** | `com.antivocale.app.PROCESS_REQUEST` |
| **Target** | `Broadcast Receiver` |
| **Package** | `com.antivocale.app` |
| **Class** | `com.antivocale.app.receiver.TaskerRequestReceiver` |

| Extra | Type | Required | Description |
|-------|------|----------|-------------|
| `request_type` | String | Yes | `"audio"` for transcription |
| `task_id` | String | Yes | Unique identifier for tracking |
| `file_path` | String | Yes | Absolute path to the audio file |

### Response (Anti-Vocale → Tasker)

| Field | Value |
|-------|-------|
| **Action** | `net.dinglisch.android.tasker.ACTION_TASKER_INTENT` |

| Extra | Type | Description |
|-------|------|-------------|
| `task_id` | String | Matches the request |
| `status` | String | `"success"` or `"error"` |
| `result_text` | String | Transcription text (on success) |
| `error_message` | String | Error description (on error) |

### Supported audio formats

`.m4a` · `.mp3` · `.wav` · `.ogg` · `.opus` · `.aac` · `.amr` · `.flac` · `.webm`

Audio is automatically converted to 16 kHz mono WAV internally.

---

*Last Updated: 2026-06-02*
