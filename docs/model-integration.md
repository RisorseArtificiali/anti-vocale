# Integrating a Model Permanently

This document describes the JSON schemas Anti-Vocale uses to bundle models, and
how to add a model so it ships with the app (or is curated for every user). If
you only want to share a model with another person over a URL, see
`docs/external-models.md` instead — that is the ad-hoc path that needs no app
changes.

There are two permanent-integration paths, one per JSON document:

| Path | Asset | Result |
|---|---|---|
| **Built-in backend** | `app/src/main/assets/models_catalog.json` | Model appears in the Model tab like Parakeet/Whisper/GigaAM, with its own backend id, share target and strings |
| **Curated external entry** | `app/src/main/assets/external-catalog/index.json` | Model appears as an autocomplete suggestion in the URL-import dialog (searches by name/language), downloads via the user-import pipeline |

The parser is **strict** (`data/catalog/ModelCatalog.kt`): any structural error
throws with the offending entry/file named, so a wrong document fails at startup
(built-ins) or at import time (external), never mid-flight. Flag keys the engine
does not consume are rejected at parse time too (`parseFlags` only accepts the
exact keys below), so a typo'd workaround cannot silently no-op.

---

## 1. Built-in backend (`models_catalog.json`)

Top-level shape:

```json
{
  "schemaVersion": 1,
  "models": [ /* ... one entry per model ... */ ]
}
```

`schemaVersion` must equal `1`; anything else is rejected. Each entry:

```json
{
  "id": "sherpa-onnx",
  "runtime": "offline",
  "modelType": "nemo_transducer",
  "family": "TRANSDUCER",
  "display": { "resourceKey": "parakeet_name" },
  "description": { "resourceKey": "parakeet_description" },
  "noteKey": "model_info_best_for_parakeet",
  "shareAlias": "com.antivocale.app.ShareParakeet",
  "storageDir": "parakeet-tdt",
  "speedComparison": false,
  "flags": {
    "defaultVariant": "smoothquant",
    "tailPadSeconds": 1,
    "metaKeys": ["vocab_size", "subsampling_factor", "model_type"]
  },
  "languages": ["de", "en", "es", "fr", "it"],
  "variants": [
    {
      "name": "smoothquant",
      "title": { "resourceKey": "parakeet_smoothquant_title" },
      "description": { "resourceKey": "parakeet_smoothquant_description" },
      "badgeKey": null,
      "dirName": "parakeet-tdt-0.6b-v3-smoothquant",
      "estimatedSizeMB": 862,
      "languages": [],
      "source": { "kind": "huggingface", "repo": "pantinor/parakeet-tdt-0.6b-v3-smoothquant" },
      "files": ["encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"]
    }
  ]
}
```

### Entry fields

| Field | Required | Description |
|---|---|---|
| `id` | yes | Backend id, also a `BuiltInBackendIds` constant (`"sherpa-onnx"`, `"whisper"`, ...). The registry derives descriptors from this |
| `runtime` | yes | `"offline"` (OfflineRecognizer) or `"online"` (OnlineRecognizer, streaming). Anything else is rejected |
| `modelType` | yes | sherpa-onnx modelType: `"nemo_transducer"`, `"whisper"`, `"qwen3_asr"`, `""` (Nemotron online) |
| `family` | yes | `"TRANSDUCER"`, `"ENCODER_DECODER"` (Whisper), `"ENCODER_ONLY_CTC"` (Qwen3-ASR) |
| `display` | no* | Fixed localized name as `{ "resourceKey": "<R.string.name>" }`; falls back to the first variant `title` when absent |
| `description` | no | Localized description, same shape |
| `noteKey` | no | Info-note resource key (ModelInfoProvider "best for" notes) |
| `speedComparison` | no | `true` shows the speed-comparison dialog on the model's section header |
| `shareAlias` | no | Manifest activity-alias of the share target (`com.antivocale.app.Share<Model>`) |
| `storageDir` | no | `filesDir` subdir the model is stored under |
| `flags` | no | Per-model tuning, see the flags table below |
| `languages` | no | Entry-level ISO codes (variants may override) |
| `variants` | yes | At least one; each is one downloadable model size/config |

\* An entry must either declare `display` or have variants with `title`.

### Variant fields

| Field | Required | Description |
|---|---|---|
| `name` | yes | Internal variant id, referenced by `flags.defaultVariant` |
| `title` / `description` | no | Localized (`{ "resourceKey": ... }` or literal string) |
| `badgeKey` | no | R.string resource for a badge next to the card title |
| `dirName` | yes | Model dir name under `filesDir/<storageDir>/` |
| `estimatedSizeMB` | yes | Size estimate shown in the UI |
| `languages` | no | Overrides the entry `languages` when non-empty |
| `source` | yes | See below |
| `files` | yes | At least one; plain name string, or `{ "name": ..., "sha256": ... }` to pin integrity |

### `source`

```json
{ "kind": "huggingface", "repo": "owner/name" }
{ "kind": "url", "template": "https://example.com/download/{file}" }
```

- `huggingface` resolves each file to `https://huggingface.co/<repo>/resolve/main/<file>`.
- `url` substitutes `{file}` per file; the template MUST contain `{file}`.
- Unknown `kind` is rejected.

### Flags

Formalized per-model workarounds (all optional). **This table is exhaustive**:
`ModelCatalog.parseFlags` rejects any key not listed here with a named parse
error, so adding a new workaround requires extending both `CatalogFlags` and the
parser (pinned by `ModelCatalogTest` "flag keys the engine does not consume are
rejected at parse time").

| Flag | Type | Meaning |
|---|---|---|
| `defaultVariant` | string | Variant auto-selected by default |
| `ensureParentDirs` | bool | Create parent dirs for each file (tokenizer/ subdir, e.g. Qwen3-ASR) |
| `tailPadSeconds` | number | Silence appended before decode (transducer tail-token fix; Parakeet/GigaAM 1, Nemotron 1.5) |
| `languageOption` | bool | Expose per-stream language option (Nemotron online) |
| `passLanguage` | bool | Pass the transcription-language code to the recognizer (Whisper) |
| `metaKeys` | [string] | ONNX metadata keys required on the encoder; default `vocab_size` (+ nemo keys for `nemo_transducer`) |
| `skipMetadataCheck` | bool | Skip the pre-native ONNX metadata scan (Whisper) |
| `whisperTailPaddings` | int | Whisper native tailPaddings, 10ms units (e.g. 1000 = 10s) |
| `blankPenalty` | number | blankPenalty for the offline recognizer (Qwen3-ASR 1.0) |
| `maxNewTokens` | int | maxNewTokens for the offline recognizer (Qwen3-ASR 2048) |
| `chunkDurationSeconds` | int | Max chunk duration for the orchestrator (Whisper/Qwen3-ASR 30; 0 = whole clip) |

### Wiring a built-in beyond the JSON

A built-in backend is more than the catalog entry. The catalog is the single
source of truth (the registry derives descriptors from it), but six touch
points must stay in lockstep — treat this as the pre-PR checklist:

1. **`transcription/BuiltInBackendIds.kt`** — add a constant for the new id and
   include it in `ALL`. The registry builds every static backend from these ids,
   so a model without a constant never appears in the backend list.
2. **`di/AppModule.kt`** — add a `@Provides`/`@IntoSet` sherpa backend binding
   for the id (`SherpaBackend(entryId)`); this is what makes the id loadable
   through `TranscriptionBackendManager`.
3. **Localization — `res/values/strings.xml` (+ translated `values-*/`)** — add
   the resource keys referenced by the catalog entry: `display`, `description`,
   every variant `title`/`description`, plus any `noteKey`/`badgeKey`. Keys are
   referenced BY NAME in the catalog, so the string files and the catalog must
   agree.
4. **`data/catalog/CatalogStringKeys.kt`** — map every new resource-key NAME to
   its `R.string` id. This map is deliberately complete: an unmapped key fails
   the catalog test suite at startup, and a key that no longer exists in
   `R.string` fails compilation.
5. **`AndroidManifest.xml`** — add the share-target `activity-alias` named by
   `shareAlias` (the alias class routes shared audio to the new backend). The
   alias `android:name` is a literal string, pinned by `BackendRegistryTest`.
6. **Tests** — extend the pinned suites so the new entry cannot silently drift:
   - `BundledModelCatalogTest` pins the entry ids to `BuiltInBackendIds.ALL` and
     asserts every resource key resolves through `CatalogStringKeys`
   - `SherpaBackendRolesTest` resolves the real export namings to roles

**Display-name contract.** Every user-visible backend label must come from the
registry descriptor (`BackendDescriptor.displayNameResId`, else
`deriveDisplayName`), never from a raw backend id. Consumers that render backend
names route through this contract — `ActiveModelRepository` for the active-model
line, `LogsViewModel.getAvailableAudioBackendsWithModels` for the retranscribe
picker (pinned by `LogsViewModelRetranscribeNamesTest`). A new dispatch site that
shows a backend's raw `displayName` (`entryId`, e.g. "sherpa-onnx") is a
regression; the registry contract is the only place that derives the
user-visible label.

---

## 2. Curated external entry (`external-catalog/index.json`)

Top-level shape:

```json
{
  "entries": [
    {
      "name": "Arabic Whisper",
      "languages": ["ar"],
      "family": "WHISPER",
      "entryUrl": "https://example.com/arabic-whisper.json"
    }
  ]
}
```

| Field | Required | Description |
|---|---|---|
| `name` | yes | Shown in the suggestion list; searchable by substring |
| `languages` | no | ISO codes; searchable by exact/prefix code |
| `family` | no (default `TRANSDUCER`) | One of `TRANSDUCER`, `WHISPER`, `CTC`, `SENSE_VOICE`, `CANARY`, `MOONSHINE`, `DOLPHIN`; unknown values skip the entry |
| `entryUrl` | yes | URL of the single-model JSON below |

`entryUrl` must point at the single-model document from section 3. Malformed or
unknown-family entries are skipped (never crash); a blank `name`/`entryUrl`
skips the entry too.

The index is a search assist only — it fills the URL and family in the import
dialog; the actual download/validation runs through the external-import
pipeline.

---

## 3. The single-model JSON (both paths point here)

This is the document a catalog `entryUrl` (and a third party sharing a model)
points at. Same object minus the built-in-only fields (`id`, `variants`,
per-variant `source`, `storageDir`, `shareAlias`):

```json
{
  "name": "GigaAM v3",
  "family": "TRANSDUCER",
  "modelType": "nemo_transducer",
  "languages": ["ru"],
  "options": {},
  "files": [
    {
      "name": "gigaam_v3_e2e_rnnt_encoder_int8.onnx",
      "url": "https://huggingface.co/pantinor/gigaam-v3/resolve/main/gigaam_v3_e2e_rnnt_encoder_int8.onnx",
      "sha256": "2cac62d0c270bd128f898f2be1a2d34780d524a6e9483888ebac7b00f97410f1",
      "size": 318995997
    }
  ]
}
```

| Field | Required | Description |
|---|---|---|
| `name` | yes | Literal display name (external strings are never localized; the UI shows the exact text) |
| `family` | no (default `TRANSDUCER`) | `TRANSDUCER`, `WHISPER`, `CTC`, `SENSE_VOICE`, `CANARY`, `MOONSHINE`, `DOLPHIN`; unknown values are rejected |
| `modelType` | no | Omitted means the family default (`ModelFamilySupport.defaultModelType`); any other value must appear in `ModelFamilySupport.validModelTypes` for the family or the import is rejected |
| `languages` | required when `family` present | Normalized ISO codes; doubles as the Whisper default language |
| `options` | no | Flat map of family options (e.g. `{"whisper.language": "ar"}`) |
| `description` | no | Literal string |
| `files` | yes | At least one |

### `files[]`

| Field | Required | Description |
|---|---|---|
| `name` | yes | Source file name; role-matched by keyword (encoder/decoder/joiner-joint/tokens), so exact names don't matter |
| `url` | yes | Direct download URL (must support HTTP Range for resume) |
| `sha256` | yes | 64-hex SHA-256 pin — **hashless entries are rejected** |
| `size` | yes | Bytes — **missing size is rejected** (the disk pre-flight is unconditional) |

ONNX split-file sidecars (`<file>.onnx.data` / `.onnx.weights`) are listed as
separate `files` entries and land next to their `.onnx` by base name.

---

## Validation checklist (both paths)

- `schemaVersion == 1` (built-in document only)
- every `files[]` entry has `name`, `url`, 64-hex `sha256`, `size`
- built-in `runtime` ∈ {`offline`, `online`}; external entries are always `offline`
- built-in `source.kind` ∈ {`huggingface`, `url`}; `url` templates contain `{file}`
- `family` values are the exact enum names above
- the family's file set is complete: Transducer = encoder+decoder+joiner/joint+tokens, Whisper = encoder+decoder+tokens, CTC = encoder+tokens, SenseVoice = model+tokens
