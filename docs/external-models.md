# External Models

Anti-Vocale supports importing user-provided sherpa-onnx models alongside the built-in backends. Five model families are supported: Transducer, Whisper, CTC, SenseVoice, and Canary. This document describes the import formats and how to share models with other users.

## What import is for, and what it does not promise

The import feature exists to decouple app releases from model releases: a new, validated model can reach every user as a one-tap catalog entry without waiting for an app update. The community catalog (Model tab > Advanced > Import from URL opens the catalog picker) is the list of models we have actually validated; [the model catalog page](model-catalog.md) mirrors it with sizes and links to the original models.

That an import dialog exists does not mean every model on HuggingFace will work. A model is importable only if it matches one of the supported families below: the expected file layout per architecture, and the ONNX metadata sherpa-onnx reads to configure its engine. A model with a different shape is rejected at import time with an error, and that is by design: a clean refusal here is better than a native crash at transcription time. For example, a NeMo encoder-decoder export that ships encoder + decoder + tokens with no joiner does not fit the Transducer family (which requires the joiner), and does not fit Whisper either (different metadata), so the import says no.

So the flow, in practice:

- **Catalog entries** are validated: they install with one tap, their integrity is pinned by SHA-256, and they are the recommended path for everyone.
- **A URL or folder import** of a model that happens to match a family can work, but you are the tester: nothing guarantees the export was sane. If an import lands broken, delete its entry and import again; a fresh import adds a new entry rather than replacing the old one.
- **A model that fits no family**, or a new architecture worth supporting, needs work on our side first (a compatible sherpa-onnx export, validation on device, then a catalog entry). If you want a specific model supported, open an issue: that is how the catalog grows.
- **You do not have to wait for us.** Producing a compatible export is the same work we would do, and anyone can start it: get the model into a sherpa-onnx export matching one of the families above, import it by URL or folder, and test it on real audio. If you use a coding agent, the repo ships a skill that codifies this whole pipeline, gotchas included: [`.claude/skills/community-model-conversion/SKILL.md`](https://github.com/RisorseArtificiali/anti-vocale/blob/main/.claude/skills/community-model-conversion/SKILL.md). If the export works, open an issue proposing it for the community catalog (links to the files and what you changed), or skip us entirely: the catalog picker reads its index from a JSON URL, and the dialog's "change" option lets you point the app at any index you host, with "Restore the official catalog" always one tap away. The [mirrored-upstreams section](#mirrored-upstreams-test-catalog) shows working examples of how entries are packaged.

## Model families

The family selector above the import buttons picks the architecture; expected files, the record's `modelType`, and family options per family:

| Family | Expected files | Record `modelType` | Options |
|---|---|---|---|
| Transducer (NeMo/Zipformer) | `encoder` + `decoder` + `joiner`/`joint` + tokens `.onnx`/`.txt` | `nemo_transducer` (default), `""` (zipformer), `conformer_transducer` | none |
| Whisper | `encoder` + `decoder` + tokens | `""` | `whisper.language` (optional; blank = auto, falls back to the record's first language) |
| CTC | `encoder` + tokens | `nemo_ctc` or `zipformer_ctc` (explicit, no default) | none |
| SenseVoice | `model` + tokens | `""` | `sensevoice.language` (optional), `sensevoice.itn` (`true`/`false`) |
| Canary (NeMo Flash) | `encoder` + `decoder` + tokens | `""` | `canary.language` (one of `en`, `es`, `de`, `fr`; conditions the recognizer itself: there is no auto-detection) |

Exact file names don't matter; roles are matched by keyword (CTC prefers `ctc`-hinted candidates, Transducer prefers `rnnt`-hinted tokens). A joiner/joint file in the candidate pool is rejected for Whisper and CTC as a transducer signature, so a wrong family fails at import time instead of crashing at transcription.

**ONNX split files**: any sibling `<file>.onnx.data` (or `.onnx.weights`) external-data sidecar of a planned `.onnx` file is imported too, keeping its source base name so the ONNX runtime resolves it by co-location. Catalog entries list sidecars as separate `files` entries.

The optional languages field (comma or space separated codes, all families) is stored on the record; for Whisper it also doubles as the default language when no explicit option is set.

## Import sources

### 1. Folder import (SAF)

Pick a directory containing the family's files (exact names don't matter, roles are matched by keyword):

| Role | Matches | Example |
|---|---|---|
| Encoder | any `.onnx` containing `encoder` | `gigaam_v3_e2e_rnnt_encoder_int8.onnx` |
| Decoder | any `.onnx` containing `decoder` | `decoder.int8.onnx` |
| Joiner | any `.onnx` containing `joiner` or `joint` | `joiner.int8.onnx` |
| Tokens | `tokens.txt`, `vocab.txt`, or any `.txt` containing `tokens`/`vocab` (preferring `rnnt`-hinted, non-`ctc`) | `tokens.txt` |

Files are copied to app storage under canonical names (`encoder.int8.onnx`, `decoder.int8.onnx`, `joiner.int8.onnx`, `tokens.txt`) and pinned by SHA-256.

### 2. HuggingFace repo URL

Paste a repo URL; the app lists the files via the HF API and downloads them:

```
https://huggingface.co/pantinor/gigaam-v3
https://huggingface.co/istupakov/gigaam-v3-onnx
```

LFS-backed files are verified against the server-side SHA-256. Plain (non-LFS) files get a computed trust-on-first-use pin (marked as unverified; upgraded on re-import).

### 3. Catalog-entry JSON URL

A single-model manifest with integrity pins. This is how third parties share a model:

```json
{
  "name": "GigaAM v3",
  "modelType": "nemo_transducer",
  "languages": ["ru"],
  "files": [
    {
      "name": "gigaam_v3_e2e_rnnt_encoder_int8.onnx",
      "url": "https://huggingface.co/pantinor/gigaam-v3/resolve/main/gigaam_v3_e2e_rnnt_encoder_int8.onnx",
      "sha256": "2cac62d0c270bd128f898f2be1a2d34780d524a6e9483888ebac7b00f97410f1",
      "size": 318995997
    },
    {
      "name": "gigaam_v3_e2e_rnnt_decoder.onnx",
      "url": "https://huggingface.co/pantinor/gigaam-v3/resolve/main/gigaam_v3_e2e_rnnt_decoder.onnx",
      "sha256": "781971998e6a355d6a714f6932a30eab295e7ba0d14fd7e0f78c83b87e811860",
      "size": 4600058
    },
    {
      "name": "gigaam_v3_e2e_rnnt_joint.onnx",
      "url": "https://huggingface.co/pantinor/gigaam-v3/resolve/main/gigaam_v3_e2e_rnnt_joint.onnx",
      "sha256": "602ff7017a93311aad34df1437c8d7f49911353c13d6eae7a6ee7b041339465c",
      "size": 2712896
    },
    {
      "name": "gigaam_v3_e2e_rnnt_tokens.txt",
      "url": "https://huggingface.co/pantinor/gigaam-v3/resolve/main/gigaam_v3_e2e_rnnt_tokens.txt",
      "sha256": "7ddf22514c42c531358182c81446a8159771e9921019f09ae743ea622d40221d",
      "size": 13353
    }
  ]
}
```

#### Schema

| Field | Required | Description |
|---|---|---|
| `name` | yes | Display name shown in the Model tab |
| `family` | no (default `TRANSDUCER`) | one of `TRANSDUCER`, `WHISPER`, `CTC`, `SENSE_VOICE`, `CANARY`; unknown values are rejected |
| `modelType` | no (family-aware default) | `nemo_transducer` for TRANSDUCER without the field, `""` for WHISPER/SENSE_VOICE/CANARY; CTC requires `nemo_ctc` or `zipformer_ctc` |
| `languages` | yes for new entries (`family` present) | normalized ISO codes (`["ar"]`); doubles as the Whisper default language |
| `options` | no | flat map of family options (`{"whisper.language": "ar"}`) |
| `streaming` | no (default `false`) | `true` for streaming zipformer transducers (decoded via the online recognizer, whole-clip batch); `TRANSDUCER` family only, rejected otherwise |
| `files` | yes | Array, one entry per file |
| `files[].name` | yes | Source file name (role-matched by keyword) |
| `files[].url` | yes | Direct download URL (must support HTTP Range for resume) |
| `files[].sha256` | yes | 64-hex SHA-256 pin; **hashless entries are rejected** |
| `files[].size` | yes | Size in bytes (feeds the disk pre-flight) |

Host the JSON anywhere reachable (GitHub gist, HF repo, personal site); share the URL.

To have a model curated for everyone (bundled into the app, or listed in the
import dialog's autocomplete catalog), see [model-integration.md](model-integration.md).

### Mirrored upstreams (test catalog)

The `docs/test-catalog/*.json` fixtures for the OpenVoiceOS NVIDIA conformer-transducer models point at the mirror [pantinor/ovos-conformer-mirrors](https://huggingface.co/pantinor/ovos-conformer-mirrors), not at the upstream OpenVoiceOS repos. The upstream exports cannot be used directly: their encoders carry no k2-fsa metadata (sherpa-onnx exits 255 with `No model_type in the metadata!`) and the prediction/joint networks ship as one combined `decoder_joint-model.int8.onnx` graph, while sherpa's `nemo_transducer` loader requires separate decoder and joiner files. The mirror keeps the weights byte-identical, injects the metadata sherpa requires (`model_type=nemo_transducer`, `vocab_size`, `subsampling_factor=4`, `normalize_type=per_feature`, `pred_rnn_layers`, `pred_hidden`), and splits the combined graph into `decoder-model.onnx` + `joiner-model.onnx` at the prediction-network output tensor. The mirror README documents the full provenance.

## Massaging custom models (field guide)

Random ONNX exports of good ASR models usually do NOT import as-is: sherpa-onnx has
hard requirements that many publisher repos do not satisfy. Everything below was
verified empirically against sherpa-onnx 1.13.x (desktop harness in `eval/`); use it
as a checklist before reporting an import failure.

### 1. The loader decides by metadata, not by file names

sherpa-onnx picks its model loader from the `model_type` string inside the ENCODER's
ONNX `metadata_props`. A missing `model_type` aborts the process (`No model_type in
the metadata!`, exit 255) regardless of what the config says. If your export has no
k2-fsa metadata at all (common for HuggingFace optimum exports), you must inject it
with the `onnx` Python package:

```python
import onnx
m = onnx.load("encoder-model.int8.onnx", load_external_data=False)
entry = m.metadata_props.add()
entry.key, entry.value = "model_type", "nemo_transducer"   # or "" for icefall-style
onnx.save(m, "encoder-model.int8.onnx", save_as_external_data=True,
          location="encoder-model.int8.onnx.data")
```

Per-family required keys (the app validates these at import time):

| model_type / family | Required encoder metadata |
|---|---|
| `nemo_transducer` | `vocab_size`, `subsampling_factor`, `model_type` |
| icefall transducer (`""` / zipformer) | `vocab_size`, `model_type` |
| `whisper` | `model_type` whose value starts with `whisper` (value-checked, not just key-present) |
| `nemo_ctc` / `zipformer_ctc` (CTC family) | none (structural discriminators only) |

Note `vocab_size` is the vocab file line count MINUS one (sherpa adds the blank).
`subsampling_factor` comes from the original training config (the NVIDIA conformers
use 4, not the more common 8: check the source repo's `config.json`).
For NeMo models also set `normalize_type=per_feature`, otherwise transcripts come
out EMPTY even though the model loads fine.

### 2. Combined decoder_joint graphs must be split

NeMo transducer exports often ship decoder and joint as ONE `decoder_joint-*.onnx`
graph. sherpa's `nemo_transducer` loader creates three separate sessions, so the
combined graph cannot be used. Split it at the prediction-network output tensor
with `onnx.utils.extract_model` (weights stay identical, this is a pure graph cut):

```python
from onnx.utils import extract_model
extract_model("decoder_joint.onnx", "decoder.onnx",
              ["...decoder inputs..."], ["/joint/Transpose_1_output_0"])
extract_model("decoder_joint.onnx", "joiner.onnx",
              ["/joint/Transpose_1_output_0", "..."], ["...joiner outputs..."])
```

The exact tensor names differ per export: inspect the graph (`netron` or
`onnx.load` + print node outputs) and cut at the boundary between the prediction
network and the joint network.

### 3. Exports that can never work (know when to stop)

- **optimum/HuggingFace whisper exports** (`encoder_model.onnx` +
  `decoder_model_merged.onnx` with `past_key_values` inputs and a fixed 3000-frame
  mel input): sherpa's whisper loader has a different decoder signature. Injecting
  metadata is not enough; the decoder graph itself is incompatible. A re-export
  with k2-fsa tooling is required (see the mirrors below for the pattern).
- **MMS 1B-all**: the graph has 290 per-language adapter inputs injected at runtime
  by the `onnx-asr` loader; sherpa has no adapter API. Unusable as-is.

### 4. Tokens file formats differ per architecture

- sherpa whisper: `base64(token) id` per line (NOT plain tokens).
- transducer/CTC: plain token text per line or `token id`.
The conversion from a publisher's `vocab.json` is a few lines of Python. CAREFUL
with whisper: the format is base64 of RAW BYTES (the official csukuangfj turbo
tokens are the reference), not base64 of gpt2-decoded characters - an earlier
mirror using the gpt2 character encoding produced mojibake and was deleted.

### 5. The mirror pattern

When an upstream export needs any of the massaging above, host the fixed files in a
public mirror (HF repo) and point your entry JSON at the mirror with fresh sha256
pins, documenting provenance and exactly what was changed. Working examples:

- [pantinor/ovos-conformer-mirrors](https://huggingface.co/pantinor/ovos-conformer-mirrors):
  metadata injection + graph split, six languages, transcripts verified.
- [pantinor/whisper-arabic-dialectal-sherpa](https://huggingface.co/pantinor/whisper-arabic-dialectal-sherpa):
  full Arabic dialectal Whisper Turbo (sherpa int8 encoder+decoder, k2-fsa metadata,
  transcripts verified against the oddadmix samples). The upstream OpenVoiceOS optimum
  export of the same weights is NOT sherpa-loadable (see TASK-332). An earlier tokens-only
  mirror was deleted: its 51,866-line tokens used the gpt2 byte-level-unicode character
  encoding, which sherpa does not understand; sherpa whisper models use the 50,257-line
  raw-byte base64 format (identical to the official csukuangfj turbo tokens).

Always verify the massaged model actually LOADS AND TRANSCRIBES with sherpa-onnx on
desktop before publishing the entry (see `eval/multifamily_probe.py` for a harness);
the app's import validation cannot prove a model transcribes, only that it is
structurally loadable.

## Family selector

The dropdown above the import buttons sets the model family (see the table above). Below it, a conditional options panel: Whisper gets an optional language field, SenseVoice an optional language plus an inverse-text-normalization switch, CTC a subtype selector (`nemo_ctc` / `zipformer_ctc`), Canary a fixed four-language field (en/es/de/fr, defaulting to en). The languages field applies to all families.

The URL import dialog also offers autocomplete suggestions from a small bundled catalog (searchable by name or language code, e.g. "ar" or "arabic"); tapping a suggestion fills the URL and the family.

A wrong family fails cleanly at import time (metadata validation or the structural discriminators) or at first transcription (native crash with no error). If the latter happens, delete and re-import with the other family selected.

## Verification

Every import is verified:

- All family files (plus any `.onnx.data` sidecars) present and complete before registration
- Metadata file (per family) checked against the selected family's requirements
- SHA-256 pins verified on download (or computed trust-on-first-use for HF plain files)
- Disk space pre-flight before any download or copy

Re-importing the same files (same hashes) updates the existing record instead of creating a duplicate.
