# Moonshine v2: the ~9.25s empty decodes are sherpa feeding the decoder mask at the wrong length

2026-09-22. Companion audio-level data: `eval/smallclass/results_moonshine*.json`
and RESULTS.md "2026-09-22 TASK-619" section; the synthetic probes below live
only in this note (repro snippet at the bottom). App-side consequence already
shipped: `ExternalSherpaBackend.familyChunkCapSeconds(MOONSHINE) = 8`.

REVERSAL NOTE: the first version of this note attributed the ceiling to a
defective export and "exonerated" sherpa. Wrong way round. A raw-length-mask
probe (twelfth review round, independently re-verified twice) shows the
shipped decoder graphs are contract-correct at ANY length; the bug is
sherpa's one-line mask feeding.

## The causal chain, verified end to end

1. Symptom (app + harness): the 2026-02-27 uk/ar/vi moonshine-base exports
   decode correctly up to ~9.2s of audio and return EMPTY above ~9.25s. sherpa
   logs "Caught exception ... Return an empty result" (the onnxruntime error is
   swallowed), so the failure is silent.
2. The decoder graph's contract (inherited from the transformers 4.49
   moonshine code the export was made from): `encoder_attention_mask` at RAW
   audio length; the graph itself downsamples it via
   `mask[..., ::384][..., :mask_len]` (stride 384 = the conv strides = the
   paper's 384) before the cross-attention mask Add.
3. sherpa's `OfflineMoonshineModelV2::Impl::ForwardDecoder`
   (offline-moonshine-model-v2.cc, 1.13.8) feeds that mask at FRAME length
   (`mask.resize(encoder_out.shape[1], 1)`), unlike ForwardEncoder in the
   same file, which correctly feeds the raw audio length. With a frame-length
   mask the graph's `::384` slice yields ceil(frames/384) elements: 1 for
   <=384 frames (broadcasts, works by accident), 2 for 385-768 (the exact
   "2 by 403" of the ORT error at 403 frames: 2 = ceil(403/384), computed at
   RUNTIME, nothing baked), and so on failing for every longer input.
4. Proof the graph is fine (twelfth review round + two independent
   re-verifications): driving the shipped uk decoder directly in
   onnxruntime, a sherpa-style frame-length mask fails at 385/403/800 frames
   while a contract raw-length mask (frames x 384 all-ones) runs CLEAN at
   every one of them. The same bisect with the frame mask shows the 384/385
   boundary on uk/ar/vi.
5. Boundary arithmetic, measured against the real encoder (the conv kernels
   are unpadded and eat ~2 frames, so the nominal 384 samples/frame is
   approximate): 147456 samples give 382 frames; the true 384/385 frame
   boundary sits at ~9.23s of audio (matches the measured 9.2s TEXT /
   9.3s EMPTY); 8s+pad lands at 373 frames, 9s+pad at 415 (both sides of
   the boundary, consistent with the app measurements). The 8s cap keeps
   every chunk comfortably under it.
6. Control: the es export of the same 2026-02-27 line is unaffected because
   its decoder has NO `encoder_attention_mask` input at all (sherpa's
   `decoder_needs_mask_` flag exists exactly for that); sherpa's wrong-length
   mask never reaches it.

## Pure-onnxruntime reproducer (zero sherpa)

```python
import numpy as np, onnxruntime as ort, re

def drive(model_path, enc_len):
    so = ort.SessionOptions(); so.log_severity_level = 3
    s = ort.InferenceSession(model_path, so, providers=["CPUExecutionProvider"])
    inputs = {i.name: i.shape for i in s.get_inputs()}
    dmodel = inputs["encoder_hidden_states"][2]
    layers = max(int(m.group(1)) for n in inputs
                 if (m := re.match(r"past_key_values\.(\d+)\.", n)))
    nh, hd = next((sh[1], sh[3]) for n, sh in inputs.items()
                  if n.startswith("past_key_values.0.decoder.key"))
    enc = (np.random.default_rng(0).standard_normal((1, enc_len, dmodel)) * 0.1).astype(np.float32)
    feed = {"input_ids": np.array([[1]], dtype=np.int64),
            "encoder_hidden_states": enc,
            "use_cache_branch": np.array([False], dtype=bool)}
    if "encoder_attention_mask" in inputs:
        feed["encoder_attention_mask"] = np.ones((1, enc_len), dtype=np.int64)
    for i in range(layers + 1):
        for w in ("decoder.key", "decoder.value", "encoder.key", "encoder.value"):
            feed[f"past_key_values.{i}.{w}"] = np.zeros((1, nh, 0, hd), dtype=np.float32)
    s.run(None, feed)  # raises at enc_len >= 385 on the affected exports

# The snippet feeds the sherpa-style FRAME-length mask: it reproduces the
# failure (affected uk/ar/vi FAIL at 385, OK at 384). Swap the mask for a
# raw-length one (np.ones((1, enc_len * 384), dtype=np.int64)) and the SAME
# shipped graphs run clean at 385/403/800: that pair of runs is the proof
# the graphs are contract-correct and the defect is the mask length.
# control (OK through 1500 at any mask style): es (no mask input at all)
```

## Where this goes upstream

Destination: k2-fsa/sherpa-onnx. One-line runtime fix plus one robustness
observation. (For the record: sherpa's `scripts/moonshine/v2/run.sh` only
DOWNLOADS the quantized .ort files from download.moonshine.ai and rebrands
them; the exports themselves are contract-correct and need no re-export. The
mirrors are csukuangfj2/sherpa-onnx-moonshine-*.)

1. `ForwardDecoder` feeds `encoder_attention_mask` at encoder-FRAME length
   while the moonshine v2 decoder graphs (transformers 4.49 contract) expect
   RAW audio length and downsample internally (`mask[..., ::384]`). Fix:
   feed all-ones at raw length, exactly as `ForwardEncoder` already does for
   the encoder mask in the same file. Any all-ones length >=
   (frames-1)*384+1 is semantically correct for unmasked audio; the true raw
   length is ideal, frames*384 works. Affects the mask-bearing 2026-02-27
   decoders: base-{uk,ar,vi,ja,zh} and tiny-{en,ja,ko} of that line (there
   is no base-ko); the es export has no mask input and is unaffected.
2. `OfflineRecognizerMoonshineV2Impl::DecodeStream` catches `Ort::Exception`
   and returns an EMPTY result. That design hid this defect completely (our
   app shipped a 30s chunk cap against a mask-induced failure and the only
   symptom was missing text). Surfacing the error, or at least a distinct
   error result, would have made this diagnosable on the first report.

## What this changes locally (already shipped / recorded)

- The 8s family cap is correct and stays until a sherpa release carries the
  mask fix: 373 frames with the pad, under the 384 boundary, margin for VAD
  chunk edges. After the sherpa bump, the moonshine cap can go to
  whisper-class 30s and TASK-621's per-record cap design becomes moot.
- TASK-619's catalog read: the uk/ar/vi numbers are quality of a model whose
  only defect is sherpa's mask feeding; once sherpa fixes it, uncapped runs
  become possible and the capped-at-8s boundary damage disappears. Re-measure
  before promoting.
- The Mac export attempt (optimum/dynamo routes) is moot: nothing needs
  re-exporting. What it taught survives on TASK-621: the raw-vs-frame mask
  contract and the mask-free es generation.

## Draft upstream issue (not filed; maintainer approval pending per standing rule)

Title: moonshine v2: ForwardDecoder feeds encoder_attention_mask at frame
length; graphs expect raw audio length (silent empty decode above 384
frames)

Body sketch: the repro below (both mask lengths, the boundary table), the
affected model list (mask-bearing 2026-02-27 decoders: base-{uk,ar,vi,ja,zh},
tiny-{en,ja,ko}; es unaffected), the mechanism (mask[..., ::384] internal
downsample; ceil(frames/384)=2 runtime "2 by 403"), the one-line fix (feed
all-ones at raw length as ForwardEncoder does), and the secondary ask
(surface DecodeStream exceptions instead of returning empty, which hid this
for weeks downstream).
