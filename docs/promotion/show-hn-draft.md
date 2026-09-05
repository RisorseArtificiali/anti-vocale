# Show HN draft (TASK-454, plan 2026-09-05)

Two drafts: the number-led version (recommended; HN rewards hard data) and a
shorter alternative. Both end with the maintainer's approval gate: nothing is
posted without an explicit go.

## Recommended: number-led

**Title:** Show HN: Anti-Vocale, offline voice-message transcription for Android (Apache 2.0)

**Body:**

I maintain an Android app that transcribes WhatsApp/Telegram/Signal voice messages entirely on-device: no cloud, no account, the audio never leaves the phone. Kotlin, Jetpack Compose, Apache 2.0: https://github.com/RisorseArtificiali/anti-vocale

It ships five ASR backends through sherpa-onnx (Parakeet TDT, Whisper, Qwen3-ASR, Nemotron streaming, Gemma multimodal for post-processing), all int8-quantized, plus user-imported community models. 25 European languages built in, 99 with Whisper, any sherpa-onnx model importable by URL.

Some numbers from our own measurements (desktop harness, public test sets, greedy decoding):

- Italian voice messages: 4.3% WER (Distil-Whisper specialist) vs 5.4% (Parakeet TDT), with Parakeet running 18x faster than the Distil model
- A quality cliff we shipped blindly and then measured: the Russian GigaAM v3 model survives 180-second single passes (its rotary positional table holds 200s) but the transcript collapses, 65% WER vs 10.7% at 25-second passes. The model is trained on 25s segments; the vendor's own long-form API segments at exactly that threshold. We now cap every model's pass length to its training distribution.
- Reproducible F-Droid builds: same APK bytes from their buildserver and ours, per-ABI, sherpa-onnx compiled from source

The engineering post-mortems are in the repo (docs/research/): the rotary-table crash, the memory-derived chunk caps, the per-device VAD ceilings.

F-Droid: https://f-droid.org/packages/com.antivocale.app/
Play Store: https://play.google.com/store/apps/details?id=com.antivocale.app

Happy to answer questions on the model choices, the ONNX runtime setup, or why transcription quality on-device differs from published numbers (quantization + phone thermal envelopes).

## Alternative: shorter

**Title:** Show HN: I built an Android app that transcribes voice messages offline (no cloud, FOSS)

**Body:**

Anti-Vocale transcribes voice messages locally with quantized ASR models (Parakeet, Whisper, Qwen3, streaming Nemotron): share the audio, get text back, nothing leaves the phone. Five backends, 25+ languages, community model imports, Apache 2.0.

The interesting engineering is in docs/research/ in the repo: per-model chunk caps after we measured a 6x WER collapse on long single passes, memory-derived VAD ceilings, reproducible F-Droid builds with sherpa-onnx compiled from source.

https://github.com/RisorseArtificiali/anti-vocale

## Predictable questions, prepared answers

- **Why not whisper.cpp?** We run ONNX Runtime via sherpa-onnx: one native stack for five backend families (transducer, encoder-decoder, streaming), with a unified download/verify/load pipeline. whisper.cpp is excellent for Whisper alone; we need Parakeet's 18x speed for the default experience and streaming for progressive text.
- **Why on-device at all, APIs are cheap?** Privacy is the product, and much of our audience is in markets where sending voice to a third-party cloud is a non-starter (or the connection is). Also no per-call cost at scale.
- **Model licenses?** All bundled models are redistributable (NVIDIA Open Model License, Apache, MIT variants); each model card is linked in docs/model-catalog.md.
- **Battery/heat?** RTF on a mid-range SoC is ~0.1-0.3 for the default model; a 1-minute voice message takes ~5 seconds. The models unload after an idle timeout (the timer is paused during generation, a bug we fixed and wrote up).
- **Why is quality worse than the paper's numbers?** int8 quantization plus real phone thermal envelopes; published numbers are full precision on desktop GPUs. Our FAQ states this openly.

## Notes

- Post timing: Tuesday-Thursday morning US Eastern per HN convention; the maintainer decides.
- The 65%-vs-10.7% cliff number is the strongest hook; it is measured and documented (docs/research/2026-09-05_gigaam-chunk-length-quality.md).
- Do NOT post without explicit maintainer approval (standing rule).
