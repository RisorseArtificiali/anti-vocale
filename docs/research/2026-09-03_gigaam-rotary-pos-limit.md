# Research Report: GigaAM v3 3-minute cap, artifact limit or intrinsic?

**Date**: 2026-09-03
**Depth**: deep (source-verified at 3 layers + desktop reproduction)
**Confidence**: HIGH

## Executive Summary

Yes, the cap is real: any faithful GigaAM v3 export dies above 200.0s of audio in a single pass. The 5000-position rotary table comes from the model config Sber published (which inherits NeMo's default), not from anything we or sherpa-onnx chose; the ONNX file bakes it into the graph. Mathematically the weights are NOT intrinsically capped (the rotary table is a deterministic sinusoid, trivially extendable at re-export), but the authors themselves refuse single-pass input above 25 seconds and route long audio through VAD segmentation: long single-pass is unsupported and unvalidated, and O(n^2) attention would punish it anyway. Our 180s chunk cap is the same strategy Sber ships, coarser.

## Findings

### 1. The limit is in the published model config, not in our export choices

- sherpa-onnx's export script does not hand-write the encoder config: it loads the official package (`gigaam.load_model("v3_rnnt")`) and the dict in the script is only a printout of `model.cfg`. The `pos_emb_max_len: 5000` shown there is the model's own config (scripts/nemo/GigaAM/export-onnx-rnnt-v3.py, read at tag v1.13.5).
- GigaAM's `ConformerEncoder.__init__` (gigaam/encoder.py, Sber's repo) declares `pos_emb_max_len: int = 5000` as the class default; the encoder class is vendored NeMo code.
- NeMo itself: `pos_emb_max_len` "Defaults to 5000" in NVIDIA's ConformerEncoder (official source doc string). So 5000 is the NeMo lineage default that GigaAM inherited and never raised.

### 2. The shipped artifact bakes the table, hence the crash

- The rotary embedding is `register_buffer(..., persistent=False)`: not a learned parameter, rebuilt at init. ONNX export traces the forward and freezes the buffer as a graph constant sized 2x5000 (cos half + sin half). Any input producing more than 5000 positions broadcasts against the constant and dies in `/layers.0/self_attn/Mul_1` (reproduced desktop: 190s decodes, 205s fails with "5000 by 5125"; the user's report: "5000 by 5229" at 208.1s).
- Positions rate: preprocessor hop 160 samples (10ms) with subsampling factor 4 gives 25 positions/s (5229/208.1 = 25.1 in the user's crash). Hard cap: 5000/25 = 200.0s of features, 199s of real audio with our 1s tail pad.

### 3. The weights are not intrinsically capped, but long single-pass is unsupported by the authors

- The rotary table is deterministic (`inv_freq = 1/base^(2i/d)`, sin/cos): no learned positional parameters exist. Re-exporting with a larger `pos_emb_max_len` (the package even ships `extend_pe` for this) is bit-exact below 5000 and well-defined above. So the 200s wall is a config artifact, removable by a re-export.
- BUT Sber's own API refuses single-pass beyond 25 seconds: `LONGFORM_THRESHOLD = 25 * SAMPLE_RATE` and `transcribe()` raises "Too long wav file, use 'transcribe_longform' method" above it (gigaam/model.py). The supported long-audio path, `transcribe_longform`, does Silero VAD segmentation and transcribes each segment separately. The authors never validated single-pass anywhere near 200s; the 5000 table is a ceiling nobody is meant to reach.
- Even with a bigger table, single-pass attention is O(n^2) in positions: 10 minutes = 15000 positions x 16 heads x 16 layers, exactly the memory wall Parakeet hit (TASK-406: chunked because 380s single-pass peaked at 5GB). Removing the crash by re-export would trade it for an OOM and untested extrapolation quality.

### 4. Consequence for our fix

The 180s catalog chunk cap (commit 2126859, release/1.11.2) is the same strategy Sber ships (theirs: VAD segments; ours: fixed 180s windows, the established Whisper/Qwen3/Parakeet pattern), just coarser. A re-export with a larger table is possible but strictly worse engineering: unvalidated accuracy, O(n^2) memory, and we would own a non-official artifact (contradicting the byte-identical-official-artifact policy from the whisper-small investigation).

## Confidence Assessment

- HIGH: the 200.0s cap on the shipped artifact (source config + math + two-sided desktop repro); the config's Sber/NeMo provenance (read in both repos); LONGFORM_THRESHOLD = 25s and the VAD-based longform path (read in Sber's model.py); the deterministic nature of the rotary table (read in Sber's encoder.py).
- MEDIUM (mechanism certain, magnitude unmeasured): quality degradation of rotary extrapolation beyond 5000 positions if anyone re-exports; we did not test it because the O(n^2) memory argument makes it moot.

## Sources

1. sherpa-onnx v1.13.5, scripts/nemo/GigaAM/export-onnx-rnnt-v3.py (cfg printout; gigaam.load_model) - read locally at the pinned tag.
2. salute-developers/GigaAM, gigaam/encoder.py (RotaryPositionalEmbedding.create_pe, ConformerEncoder default pos_emb_max_len=5000, extend_pe) - via HF mirror waveletdeboshir/gigaam-rnnt (verbatim copy of Sber's file).
3. salute-developers/GigaAM, gigaam/model.py (LONGFORM_THRESHOLD = 25*SAMPLE_RATE, transcribe raise, transcribe_longform VAD segmentation).
4. NVIDIA NeMo Speech, nemo/collections/asr/modules/conformer_encoder.py (pos_emb_max_len default 5000).
5. Desktop reproduction: /tmp/gigaam-repro (190s OK / 205s fails "5000 by 5125"; 400s as 3x180s chunks all pass).
6. User report Dennis Matuk 2026-09-03 ("5000 by 5229" at 208.1s) and GH issue #76.
