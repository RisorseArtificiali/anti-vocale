# Tasker Integration Guide

This guide explains how to configure Tasker to use LocalAI Bridge for on-device text generation and audio transcription.

## Prerequisites

1. **Install LocalAI Bridge** from the APK
2. **Download a Gemma 3n model** from [HuggingFace LiteRT Community](https://huggingface.co/litert-community)
   - Recommended: `gemma-3n-e2b.litertlm` (smaller, faster)
   - Alternative: `gemma-3n-e4b.litertlm` (larger, more capable)
3. **Open the app** and select your model file
4. **Tap "Load Model"** and wait for "Ready" status

## Intent Protocol

### Request Intent

| Field | Value |
|-------|-------|
| **Action** | `com.localai.bridge.PROCESS_REQUEST` |

| Extra | Type | Required | Description |
|-------|------|----------|-------------|
| `request_type` | String | Yes | `"text"` or `"audio"` |
| `task_id` | String | Yes | Unique identifier for tracking |
| `prompt` | String | For text | The text prompt to process |
| `file_path` | String | For audio | Absolute path to audio file |

### Response Intent

| Field | Value |
|-------|-------|
| **Action** | `net.dinglisch.android.tasker.ACTION_TASKER_INTENT` |

| Extra | Type | Description |
|-------|------|-------------|
| `task_id` | String | Matches the request task_id |
| `status` | String | `"success"` or `"error"` |
| `result_text` | String | Generated text (on success) |
| `error_message` | String | Error description (on error) |

---

## Example Profile 1: Text Generation

### Use Case
Send a text prompt and receive an AI-generated response.

### Tasker Configuration

**Task: AI Text Query**

1. **Variable Set**
   - Name: `%task_id`
   - Value: `text_%TIMEMS`

2. **Variable Set**
   - Name: `%prompt`
   - Value: `Write a haiku about technology`

3. **Send Intent**
   - Action: `com.localai.bridge.PROCESS_REQUEST`
   - Extra: `request_type:text`
   - Extra: `task_id:%task_id`
   - Extra: `prompt:%prompt`
   - Package: `com.localai.bridge`
   - Class: `com.localai.bridge.receiver.TaskerRequestReceiver`
   - Target: `Broadcast Receiver`

4. **Wait**
   - Seconds: `10` (adjust based on expected response time)

5. **Variable Clear**
   - Name: `%result_text`

6. **Profile: Listen for Response**
   - Event: Intent Received
   - Action: `net.dinglisch.android.tasker.ACTION_TASKER_INTENT`

7. **Task: Handle Response**
   - If `%status` eq `success`
     - Flash: `%result_text`
     - (Or use `%result_text` in your workflow)
   - Else
     - Flash: `Error: %error_message`

---

## Example Profile 2: Voice Message Transcription

### Use Case
Transcribe a voice recording to text.

### Tasker Configuration

**Task: Transcribe Voice Note**

1. **Variable Set**
   - Name: `%task_id`
   - Value: `audio_%TIMEMS`

2. **Variable Set**
   - Name: `%audio_path`
   - Value: `/sdcard/Download/voice_message.m4a`
   - (Or use a variable from a previous step)

3. **Send Intent**
   - Action: `com.localai.bridge.PROCESS_REQUEST`
   - Extra: `request_type:audio`
   - Extra: `task_id:%task_id`
   - Extra: `file_path:%audio_path`
   - Extra: `prompt:Transcribe this speech:` (optional)
   - Package: `com.localai.bridge`
   - Class: `com.localai.bridge.receiver.TaskerRequestReceiver`
   - Target: `Broadcast Receiver`

4. **Wait**
   - Seconds: `30` (longer for audio; adjust based on duration)

5. **Profile: Listen for Response**
   - Event: Intent Received
   - Action: `net.dinglisch.android.tasker.ACTION_TASKER_INTENT`

6. **Task: Handle Transcription**
   - If `%status` eq `success`
     - Variable Set: `%transcription` to `%result_text`
     - Flash: `Transcription: %transcription`
   - Else
     - Flash: `Error: %error_message`

---

## Example Profile 3: WhatsApp Voice Message Auto-Transcribe

### Use Case
Automatically transcribe incoming WhatsApp voice messages.

### Tasker Configuration

**Profile: WhatsApp Voice Received**
- Event: File Created
- Path: `/sdcard/Android/media/com.whatsapp/WhatsApp/WhatsApp Voice Notes/`

**Task: Auto-Transcribe**

1. **Variable Set**
   - Name: `%voice_file`
   - Value: `%new_file` (from File Created event)

2. **Wait**
   - Seconds: `2` (allow file to finish writing)

3. **Variable Set**
   - Name: `%task_id`
   - Value: `wa_%TIMEMS`

4. **Send Intent**
   - Action: `com.localai.bridge.PROCESS_REQUEST`
   - Extra: `request_type:audio`
   - Extra: `task_id:%task_id`
   - Extra: `file_path:%voice_file`
   - Package: `com.localai.bridge`
   - Target: `Broadcast Receiver`

5. **Wait**
   - Seconds: `30`

6. **Profile: Response Handler**
   - Event: Intent Received
   - Action: `net.dinglisch.android.tasker.ACTION_TASKER_INTENT`

7. **Task: Show Transcription**
   - If `%status` eq `success`
     - Notify: `Voice Transcription: %result_text`
   - Else
     - Notify: `Transcription Failed: %error_message`

---

## Timing Guidelines

| Request Type | Audio Length | Cold Start | Warm |
|--------------|--------------|------------|------|
| Text | N/A | 3-5s | 0.5-2s |
| Audio | 10s | 5-8s | 2-4s |
| Audio | 30s | 8-15s | 5-10s |
| Audio | 60s (2 chunks) | 15-25s | 10-20s |
| Audio | 90s (3 chunks) | 25-35s | 15-30s |

**Tips:**
- Set your Wait time generously (add 50% buffer)
- For long audio, the app automatically chunks into 30s segments
- Check the Logs tab in the app for debugging

---

## Supported Audio Formats

The app uses FFmpeg for audio conversion, supporting:
- `.m4a` (AAC)
- `.mp3`
- `.wav`
- `.ogg`
- `.opus`
- `.aac`
- `.amr`
- `.flac`
- `.webm` (audio)

Audio is automatically converted to 16kHz mono WAV internally.

---

## Troubleshooting

### "MODEL_NOT_LOADED" Error
- Open the LocalAI Bridge app
- Tap "Load Model" and wait for "Ready" status
- Retry your Tasker task

### "Audio preprocessing failed" Error
- Verify the audio file exists at the specified path
- Check the file format is supported
- Try a different audio file to isolate the issue

### No Response Received
- Check the Logs tab in the app for request status
- Ensure Tasker has permission to receive broadcasts
- Increase the Wait time in your Tasker task

### Slow Performance
- Cold starts take 3-5 seconds for model loading
- Keep the model loaded between requests for faster response
- Close other memory-intensive apps

---

## Advanced: ADB Shell Testing

Test the integration directly via ADB:

```bash
# Text request
adb shell am broadcast \
  -a com.localai.bridge.PROCESS_REQUEST \
  --es request_type "text" \
  --es task_id "test_001" \
  --es prompt "Hello, how are you?"

# Audio request
adb shell am broadcast \
  -a com.localai.bridge.PROCESS_REQUEST \
  --es request_type "audio" \
  --es task_id "test_002" \
  --es file_path "/sdcard/Download/test.m4a"

# Monitor responses
adb logcat -s TaskerRequestReceiver InferenceService
```

---

## Security Notes

- All processing is **100% on-device** - no internet required
- Audio files are read from storage but not uploaded anywhere
- Model files are stored in app-private storage after selection
- Broadcast intents are limited to local device

---

*Last Updated: 2026-02-28*
