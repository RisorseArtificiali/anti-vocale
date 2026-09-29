# FLEURS fixture data (eval/smallclass/*/)

All `*.wav` + `*.txt` pairs under the per-language directories are
clips of google/fleurs (validation split), fetched via the HF parquet
endpoint (the rows API 500s for some configs; the parquet fallback is
the documented method since 2026-09-22). FLEURS is CC-BY-4.0; the
per-clip transcriptions are the dataset's own. Attribution: Google
("The FLEURS Dataset", google/fleurs on Hugging Face).

- `fetch_fleurs.py` (2026-09-22): ar/de/en/es/fa/fr/he/ru/uk/vi, wavs
  only (no transcripts saved).
- `fetch_fleurs_it.py` + the inline parquet fallback (2026-09-29, the
  script documents both arms): `it/` and `de_gt/`, wavs AND
  transcripts (de_gt exists because the 2026-09-22 de/ dir has no
  transcripts; TASK-410's cut-placement A/B needs ground truth).

The fixtures are small committed eval data on purpose (the private
11-clip Italian voice-message set stays gitignored under eval/clips).
