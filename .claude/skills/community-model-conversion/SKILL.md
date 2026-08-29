---
name: community-model-conversion
description: Convert a HuggingFace ASR fine-tune into a sherpa-onnx external model, publish it, and add it to the Anti-Vocale community catalog. Use when a user requests an importable model for a language we don't cover, or when catalog-listed repos fail to import.
---

# Community model conversion (HF fine-tune -> sherpa-onnx -> catalog entry)

This pipeline turns "user wants language X" into a one-URL import in the app.
Every step below was learned from a real conversion (Arabic 2026-08, Swiss
German 2026-08-24, GH #63). The gotchas are the point of this file: each one
cost hours because the failure mode is silent (models load but transcribe
garbage, or decode to empty strings).

## 0. Triage the user's repo FIRST (before any conversion work)

If the user points at `onnx-community/*` or any transformers.js-layout export
(`onnx/encoder_model*.onnx` + `tokenizer.json`, inputs named `input_features`
/ `last_hidden_state`): STOP. It is structurally incompatible with
sherpa-onnx (no `n_mels`/`model_type` metadata, wrong tensor names, merged
decoder KV cache). Our app's rejection message is correct; relaxing
validation would only turn it into a native exit 255. Verify cheaply with the
eval harness: `OfflineRecognizer.from_whisper(...)` fails at
`InitEncoder: 'n_mels' does not exist in the metadata`. The fix is a
re-export from the fine-tuned PyTorch checkpoint, not the ONNX files.

Then find the PyTorch source of the fine-tune (check the HF model card's
base_model, search `?search=<model name>` on the HF API). Swiss German had
`Flurin17/whisper-large-v3-turbo-swiss-german` behind the onnx-community
mirror.

## 1. Environment (CPU only is fine, ~40 min end to end)

- Workspace on REAL disk (NOT /tmp: it is tmpfs, the fp32 graphs are ~3 GB
  and eat RAM twice), e.g. /var/tmp/chwork.
- venv: torch+cpu, transformers, onnx, onnxruntime, onnxscript, openai-whisper,
  safetensors, numpy<2. Python 3.14 + onnx may need `pip install -U ml_dtypes`
  or onnx<1.20 (float4 attribute error).
- sherpa-onnx sources at the pinned tag (`git clone --branch v1.13.5 ...`;
  the tag equals the `.sherpa-version` srclib commit) for
  `scripts/whisper/export-onnx.py`.
- Validate with the eval harness venv (eval/.venv, sherpa-onnx Python).

## 2. HF transformers checkpoint -> openai-whisper .pt

The export script consumes openai-whisper models. Write a key remapper
(kept at the workspace; see memory checkpoint for the working one):
encoder/decoder layer subkeys map (`self_attn.q_proj`->`attn.query`,
`layer_norm1`->`attn_ln`, `final_layer_norm`->`mlp_ln`, bare `fc1`/`fc2`->
`mlp.0`/`mlp.2`, `encoder_attn.*`->`cross_attn.*`), drop `lm_head` (tied),
drop `k_proj.bias` (softmax-invariant: adds q.b, constant across keys).

**GOTCHA 1 (the big one): turbo's decoder positional embedding is LEARNED,
not sinusoid.** Download the official turbo.pt from the URL in
`whisper/__init__.py` and check: decoder.positional_embedding has std ~0.0066
(near-zero learned table), while a sinusoid has std ~0.7. You MUST copy
`model.decoder.embed_positions.weight` (and the encoder one) from the HF
checkpoint; injecting a hand-computed sinusoid produces models that load,
run, and emit total garbage. Symptom if you get this wrong: weights verified
identical, architecture verified identical, output still gibberish.

Wrap the result as `{'dims': {...}, 'model_state_dict': sd}` with dims read
off the shapes (turbo: n_mels 128, n_audio_* 1500/1280/20/32, n_text_*
448/1280/20/4, n_vocab from token_embedding). whisper.load_model infers from
dims.

Validate BEFORE exporting: transcribe a short wav with openai-whisper from
the converted .pt AND with the original HF pipeline; they must agree. If you
see NaN logits, you probably read a 22 kHz int16 wav as float32 (use
whisper.load_audio / ffmpeg), not a model bug.

## 3. sherpa export (export-onnx.py, patched for a local checkpoint)

Local patches needed (keep them in the working copy):
- add the model name to `--model` choices and to a `load_model` branch
  returning `whisper.load_model("./<name>.pt")`;
- the n_mels branch (`large`/`turbo` -> 128) must include your name;
- the external-data save branches (`"large" in filename`) must include your
  name: the fp32 graphs exceed the 2 GB protobuf limit (FileExistsError on
  rerun: delete `*.weights` too, not just `*.onnx*`). NEVER delete these
  files while the export is still running: the `.weights` beside the `.onnx`
  is the canonical external data the proto points at, and deleting it
  mid-run (2026-08-28, "looked like a duplicate") crashes quantization and
  forces a full re-export. Clean up only after the process exits.

**GOTCHA 2a (int8 truncation): per-tensor int8 quantization of a FINE-TUNED
whisper decoder causes premature EOT after ~1 phrase per 30s chunk.** The
fine-tune shifts weight distributions; per-tensor crushes outlier channels and
the greedy decoder stops early. Short audio looks correct, 30s chunks yield 1
phrase instead of 15. FIX: pass per_channel=True to quantize_dynamic() for
the decoder (ORT docs recommend it when accuracy loss is large). Verified:
per-channel int8 = identical output to fp32 at the same size. The encoder is
unaffected. MODEL-DEPENDENT (2026-08-27, primeline German): a light fine-tune
(3 epochs, lr 1e-6) moved distributions so little that per-tensor survived a
62s tiled test with no truncation (only a token-casing wobble); the Swiss
fine-tune truncated at 30s. You cannot predict which kind you have: always
quantize the decoder per-channel, and always test with a tiled ~30s audio
file (60s is better) before publishing.

**GOTCHA 2b: torch >= 2.9 defaults torch.onnx.export to the dynamo exporter,
which bakes a wrong cross-KV reshape in the whisper decoder.** Symptom:
sherpa decodes to EMPTY text and logs `Caught exception ... Reshape ...
input_shape_size == requested_shape_size was false, Input shape {1,4,1280}
requested {1280}` (hidden behind the misleading "tail_paddings" message).
Fix: pass `dynamo=False` for the decoder export. The encoder is unaffected.

Quantization (int8, MatMul-only) is produced by the script itself.

Validate: decode the same wav via `OfflineRecognizer.from_whisper` in
eval/.venv; must match the HF ground truth. Use audio >= 10 s or sherpa's
"Return an empty result ... input frames" fires (frames must exceed
tail_paddings, 1000 default). Load the wav with ffmpeg resampling, not
np.frombuffer.

## 4. Publish + catalog

- HF repo under `pantinor/` (hf CLI, HUGGINGFACE token at
  ~/.cache/huggingface/token). README yaml: `language:` needs ISO codes
  (de, gsw), NOT `de-CH` (use `language_bcp47:` for that) or the upload is
  rejected.
- Entry JSON in `app/src/main/assets/external-catalog/<lang>.json` (mirror
  arabic.json exactly: family, per-file url/sha256/size; whisper options use
  the keys `"whisper.language"`/`"whisper.task"` from ModelFamilySupport).
- index.json: add the entry. **GOTCHA 3: the catalog matcher does substring
  search over names; "Large" contains "ar" and breaks the arabic by-code
  test in ExternalCatalogTest** (it asserts filter("ar") equals the arabic
  entry alone). Name entries without "ar"-containing words (we used
  "Whisper v3 Turbo Swiss German int8 ...").
- The entryUrl points at raw.githubusercontent.com main: push BEFORE any
  released client can use it, and curl the URL to 200 after push.
- Full suite (test count asserts in ExternalCatalogTest), /review-local,
  commit, push.

## 5. Device validation (REQUIRED before advertising: we promised on GH)

Import path: Model tab > Advanced > ONNX Sherpa > family=Whisper >
"Import from URL" (NOT "Import from Hugging Face URL", that is the
LiteRT-LM importer). Type a unique language code (e.g. "gsw") to filter the
catalog suggestion; tap the suggestion NAME line; verify the field shows the
entry URL; Import; confirm the file list; wait for the ~1 GB download; run a
transcription in that language.

Driving this via adb is fragile: urlText persists across dialog opens
(force-stop resets it), BACK dismisses the dialog (never use it to hide the
keyboard), and `input text` renders fine but verify with a screenshot read
via the zai MCP image tools, not by eyeballing. If the form fights you more
than twice, ask the user to do the manual step and verify the result from
`run-as ... ls files/models/external` + the datastore record instead.

## 6. Close out

- Reply on the originating GH issue with the analysis + catalog link.
- Backlog task final summary; delete the multi-GB workspace only after the
  device pass.
