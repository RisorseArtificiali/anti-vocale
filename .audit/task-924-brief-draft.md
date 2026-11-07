# TASK-924 implementation brief (draft; presence mechanics pending the subsystem map)

## Goal
Offline bundles must round-trip BUILT-IN catalog entries: export works for every built-in model type (Qwen3, streaming Nemotron, Gemma included), and importing a bundle that originated from a built-in entry restores it into the built-in slot so the catalog card shows it present, with no external twin and no redownload prompt. Bundles without built-in origin keep importing as external models exactly as today (backward compatible: old bundles, old behavior).

## Frozen design (to be confirmed against map)
1. Manifest v2 additive fields: an origin object with kind ("builtin" | "external"), entryId, variantName, modelType. Additive only: v1 manifests (no origin) import through today's external path unchanged.
2. Export side: drop the importFamilyForModelType gate at ModelTab.kt:1502; launchBundleExport stamps the origin into BundleMetadata. The zip stays the same shape (files + manifest).
3. Import side: when manifest.origin.kind == builtin AND the catalog (bundled models_catalog) contains that entryId+variant: restore files into the built-in dir (entryDirFor equivalent in non-UI code), run the same integrity check the downloader uses, and let the existing presence mechanism see it. No ExternalModelStore record for this path.
4. If origin is builtin but the receiving app's catalog has no such entry (older app): fall back to external import if a family exists, else a clear error naming the missing entry.

## Open questions for the map
- Exact presence mechanism per type (sherpa dir keying, qwen3, nemotron streaming, gemma/litertlm file sets).
- Integrity verification on restore (sha256 from catalog? the 660/661 heal chain reuse?).
- OrphanedModelDirCleaner live-set: does a restored dir survive startup without downloader records?
- Single-owner serialization point: ModelBundleCodec manifest vocabulary.
- UI strings needed (minimum; i18n across all locales per DoD).

## Success criteria
- Unit tests: manifest v2 round-trip (export builtin -> import restores dir + presence; v1 bundle -> external path unchanged; builtin-origin on entry-less app -> defined fallback).
- Export button visible for qwen3/nemotron/gemma variants when present.
- No ExternalModelStore record created for a builtin restore.
- Existing bundle/external-import tests stay green.
