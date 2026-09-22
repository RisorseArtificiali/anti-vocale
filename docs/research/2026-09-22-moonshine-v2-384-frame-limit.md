# Moonshine v2 decoder: the 384-frame limit (root cause of the ~9.25s empty decodes)

2026-09-22, night session. Companion data: `eval/smallclass/results_moonshine*.json`,
RESULTS.md "2026-09-22 TASK-619" section. App-side consequence already shipped:
`ExternalSherpaBackend.familyChunkCapSeconds(MOONSHINE) = 8`.

## The causal chain, verified end to end

1. Symptom (app + harness): the 2026-02-27 uk/ar/vi moonshine-base exports
   decode correctly up to ~9.2s of audio and return EMPTY above ~9.25s. sherpa
   logs "Caught exception ... Return an empty result" (the onnxruntime error is
   swallowed), so the failure is silent.
2. sherpa-onnx 1.13.8 runtime is NOT the cause. Its v2 path
   (`offline-recognizer-moonshine-v2-impl.h` DecodeStream,
   `offline-moonshine-model-v2.cc` ForwardEncoder/ForwardDecoder,
   `offline-moonshine-v2-greedy-search-decoder.cc`) feeds the decoder one
   token per step with dynamic shapes and no length logic. Driving the decoder
   graph DIRECTLY in onnxruntime (no sherpa at all), with synthetic inputs
   matching sherpa's exact contract, reproduces the failure.
3. The limit is baked in the decoder graph: bisected on synthetic encoder
   lengths, uk/ar/vi `decoder_model_merged.ort` all run fine at 384 encoder
   frames and fail at 385. 384 frames x 384 samples/frame (the moonshine
   paper's 384, cited in sherpa's own greedy decoder) = 147456 samples =
   9.216s: exactly the measured audio boundary (9.2s TEXT / 9.3s EMPTY).
   The app's 1s silence pad explains the app-side numbers: 8s+pad = 375
   frames (OK), 9s+pad = 400 frames (fail).
4. The failing op is `/model/decoder/layers.0/encoder_attn/Add` inside the
   `optimum::if` cache branch ("Attempting to broadcast an axis by a
   dimension other than 1. 2 by 403" at enc_len 403): an export-time defect
   in the mask-bearing export generation, not a designed maximum (the graph's
   input shapes are fully dynamic in encoder_sequence_length).
   The fragile code path is IDENTIFIED (transformers 4.49 moonshine
   modeling, export-attempt follow-up): the decoder downsamples the encoder
   mask itself via `mask[..., ::384][..., :mask_len]` (stride 384 = the conv
   strides = the paper's 384) before the cross-attention mask Add. A second
   interface fact: sherpa feeds the decoder's `encoder_attention_mask` at
   FRAME length (`mask.resize(encoder_out.shape[1], 1)`), while the HF
   model's contract expects it at RAW audio length. The healthy es
   generation sidesteps the whole class: its decoder has NO mask input at
   all (sherpa's `decoder_needs_mask_` flag exists exactly for it). A
   re-export should drop the mask input (sherpa feeds all-ones only;
   unmasked == all-ones masked).
5. Control: the es export of the same 2026-02-27 line (63MB generation, and
   structurally DIFFERENT: its decoder has NO `encoder_attention_mask` input,
   which is why sherpa carries the `decoder_needs_mask_` flag) runs clean at
   every probed length through 1500 frames (~36s). The 63MB es export is a
   different, healthy export generation.

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

# affected (FAIL at 385, OK at 384): uk, ar, vi
# control (OK through 1500): es (its decoder has no encoder_attention_mask input)
```

## Where this goes upstream

Destinations: k2-fsa/sherpa-onnx (runtime + the repackage script; its
`scripts/moonshine/v2/run.sh` only DOWNLOADS the quantized .ort files from
download.moonshine.ai and rebrands them, it does not export) and
moonshine-ai / Useful Sensors (the actual export pipeline that baked the
defect; the mirrors are csukuangfj2/sherpa-onnx-moonshine-*). Two findings,
one issue each or one combined.

1. The mask-bearing 2026-02-27 decoder exports (uk/ar/vi at minimum; ja/zh/ko
   of the same line are suspect) fail above 384 encoder frames with an
   onnxruntime broadcast error in layers.0 cross-attention inside the
   optimum cache branch. Either the export bakes a wrong constant (the "2"
   operand of the failing Add) or the cache branch mishandles
   encoder lengths past a boundary; the graph's declared shapes are dynamic,
   so users reasonably expect long audio to work. A re-export or a documented
   max would fix every downstream consumer.
2. `OfflineRecognizerMoonshineV2Impl::DecodeStream` catches `Ort::Exception`
   and returns an EMPTY result. That design hid this defect completely (our
   app shipped a 30s chunk cap against a 9.2s-broken model and the only
   symptom was missing text). Surfacing the error, or at least a distinct
   error result, would have made this diagnosable on the first report.

## What this changes locally (already shipped / recorded)

- The 8s family cap is correct and now EXPLAINED (not just measured): 375
  frames with the pad, under the 384 limit, with margin for VAD boundaries.
- TASK-621 (per-record cap) gains the real discriminator: the affected
  generation is identifiable WITHOUT running audio: the failing decoders
  HAVE an `encoder_attention_mask` input, the healthy es generation does
  not. An import-time structural probe (decoder input names) can route
  8s-vs-30s per record.
- TASK-619's catalog read: the uk/ar/vi promotion cases are evaluation of a
  DEFECTIVE export (WER at <=9.2s stands, but chunked-at-8s quality pays
  boundary damage the fixed graph would not); worth re-measuring if upstream
  re-exports. es is unaffected.

## Draft upstream issue (not filed; maintainer approval pending per standing rule)

Title: moonshine v2 decoders (2026-02-27 exports with encoder_attention_mask)
fail above 384 encoder frames; DecodeStream swallows the error as an empty
result

Body sketch: the repro above, the affected repo list
(csukuangfj2/sherpa-onnx-moonshine-base-{uk,ar,vi}-quantized-2026-02-27; es
of the same line is clean and structurally different), sherpa 1.13.8 python
wheel + pure onnxruntime 1.24.4 both reproduce, error text, and the ask
(re-export the affected decoders or document the max; consider surfacing
decode exceptions instead of returning empty).
