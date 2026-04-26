package com.antivocale.app.llm

// LlamaCppEngine was removed and replaced by LlamaBroEngine (llama-bro SDK)
// as part of TASK-143: "Replace LlamaCppEngine with llama-bro SDK for GGUF inference".
//
// LlamaBroEngine wraps native JNI code (LlamaEngine from llama-bro SDK) and cannot be
// unit-tested in a JVM environment — it requires the native .so library loaded on device.
// Integration tests should be written once llama-bro provides a test artifact.
// See TASK-154: "Tests: Transcription backends — Whisper, SherpaOnnx, Qwen3Asr, Llm contracts".
