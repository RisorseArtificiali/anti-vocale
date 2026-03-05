# Backend Compatibility Reference

## Default Prompt Feature (Task-10)

The default prompt configuration only works with **LLM-based transcription backends**.

### Backend Behavior

| Backend | Models | Uses Prompt? | Notes |
|---------|--------|--------------|-------|
| LlmTranscriptionBackend | Gemma, multimodal LLMs | ✅ Yes | Prompt guides model behavior |
| SherpaOnnxBackend | Parakeet TDT | ❌ Ignored | Pure STT, prompt accepted but unused |
| WhisperBackend | Whisper | ❌ Ignored | Pure STT, prompt accepted but unused |

### Code Reference
- `InferenceService.processAudioRequest()` - passes prompt to backend
- `LlmTranscriptionBackend.transcribeAudio()` - uses prompt via `LlmManager.generateFromAudio(prompt, audioData)`
- `SherpaOnnxBackend.transcribeAudio()` / `WhisperBackend.transcribeAudio()` - accept prompt param but ignore it

### UI Note
Settings tab now includes an info banner explaining this limitation:
- English: "Only applies to LLM models (Gemma). Whisper and Parakeet models ignore this setting."
- Italian: "Applica solo ai modelli LLM (Gemma). I modelli Whisper e Parakeet ignorano questa impostazione."
