# Anti-Vocale Italian Voice-Message Eval Harness

A reproducible, **desktop-CPU** baseline harness that measures our shipped ASR models
on **real Italian voice messages**, so we can tell whether any fine-tune actually helps.

This is **Step 1** of the self-fine-tuning plan (see
`docs/scout-reports/2026-06-29.md` + memory `research_free_finetuning_feasibility`):
*before* training anything, establish a measured baseline. No GPU required — the
bottleneck this unblocks is **measurement**, which we currently don't have.

## Why this exists

We have no hand-curated Italian voice-message test set. Without it:
- We can't prove the "hallucinated repetition" failure mode is real (LocalAI-io's
  claim is unverified — no WER, only a loop-count proxy on one private file).
- We can't tell whether a Whisper LoRA fine-tune improved anything.
- We can't compare Distil-IT vs Parakeet vs Nemotron on our *actual* input domain
  (spontaneous speech), only on clean read-speech benchmarks.

This harness fixes that. It runs the **same ONNX int8 models the app ships**, via
sherpa-onnx's Python API, with the **same recognition config** the app uses
(`greedy_search`, `tailPaddings=1000`, etc.) — so desktop numbers track on-device
behavior.

## Directory layout

```
eval/
├── README.md              ← this spec
├── run_baseline.py        ← the runner (metrics + orchestration)
├── requirements.txt
├── manifest.example.csv   ← copy → manifest.csv, fill metadata (gitignored)
├── .gitignore             ← protects audio/transcripts/results/models (PII)
├── clips/                 ← YOU DROP audio here (*.opus/.m4a/.mp3/.wav) — gitignored
├── transcripts/           ← YOU DROP <clip_id>.txt here — gitignored
├── models/                ← local model dirs (symlink ok) — gitignored
└── results/               ← output reports — gitignored
```

## Collecting the eval set (the human part — cannot be automated)

Target **30–50 clips**. Quality of the set matters more than size.

**Clip selection — maximize diversity, not convenience:**
- **Real voice messages** (WhatsApp/Telegram/Signal exports), not audiobook/read speech.
  This is the whole point — we're measuring the spontaneous-speech failure mode.
- **Length spread:** a few short (<10s), most medium (10–60s), a few long (>60s).
  Long clips are where repetition loops surface.
- **Speakers:** ≥10 different speakers if possible; mixed gender/age/region.
  Italian dialects/accented speech are valuable, not noise.
- **Conditions:** quiet + noisy + music-in-background + car + hands-free. The hard
  cases are where models diverge.
- **Exclude:** anything with identifiable personal data you can't consent to, music
  tracks, non-Italian (keep this set monolingual Italian for clean WER).

**Consent / PII — non-negotiable:**
- Only use clips where you have consent from the speaker(s), OR fully anonymized clips.
- **Never commit audio or transcripts.** `.gitignore` enforces this — verify with
  `git status` before any commit. Real voice messages are other people's private speech.

**Clip format:**
- Any format ffmpeg can decode (`.opus`, `.m4a`, `.mp3`, `.wav`). WhatsApp exports are
  typically Opus — that's ideal, it *is* the production input.
- Name clips with a stable `clip_id`: `vm001.opus`, `vm002.m4a`, …
- Place in `eval/clips/`.

## Transcript schema (one `.txt` per clip)

For each `clips/<clip_id>.<ext>`, create `transcripts/<clip_id>.txt` containing the
**verbatim manual transcript**. Rules:
- **Verbatim, including disfluencies** you want scored ("ehm", "cioè", false starts).
  If you don't want a disfluency scored, mark it `[inaudible]` or `[noise]` — the
  normalizer strips bracketed tags, so they won't penalize WER.
- **Don't pre-normalize** — write natural Italian with normal punctuation/case. The
  script lowercases + strips punctuation so ref and hyp are compared consistently.
- **No timestamps needed.** Plain text, one line (or multi-line; script joins).
- One file per clip, **exact same basename** as the audio (`vm001.opus` ↔ `vm001.txt`).

The script auto-discovers clip↔transcript pairs by basename. A clip without a
transcript (or vice versa) is reported as a pairing error and skipped.

## Metrics

All computed on **normalized** text (lowercase, strip punctuation/brackets, collapse
whitespace, keep accented chars à è é ì ò ù, keep apostrophes out so `l'acqua` →
`l acqua` → tokens `l`, `acqua` consistently for ref and hyp).

| Metric | What it captures | Why we track it |
|---|---|---|
| **WER** (word error rate) | Overall transcription accuracy: (S+D+I)/ref_words | The headline number. Compare models on the same set. |
| **CER** (character error rate) | Fine-grained accuracy, less sensitive to word-boundary calls | Sanity check; useful for agglutinative/compound divergences. |
| **repetition-loops** | Count of maximal runs where the same token repeats ≥ N times (default N=4) | The **hallucination proxy**. This is what LocalAI-io measured (badly, on one file). Here it's computed on every clip, properly. A clip with `loops ≥ 1` is flagged `has_loop`. |
| **per-model mean WER / CER** | Aggregate | The summary row that lets us rank models + future fine-tunes. |

`LOOP_THRESHOLD` defaults to **4** (matches LocalAI-io's definition). Tune via `--loop-threshold`.

**Known normalization limitations (intentional, documented):**
- Numbers not expanded: `"3"` vs `"tre"` counts as an error. Acceptable for
  relative comparison across models on the same refs.
- No diacritic folding: `"perché"` ≠ `"perche"`. We keep accents (they're
  meaningful in Italian). If a model drops them it shows as substitution errors.

## Running

```bash
# 1. Install (Python 3.10+). ffmpeg must be on PATH for opus/m4a decode:
#    sudo dnf install ffmpeg   # or: brew install ffmpeg / apt install ffmpeg
pip install -r eval/requirements.txt

# 2. Point the runner at your local model dirs (the same ONNX int8 models the app
#    downloads). Set roots in run_baseline.py CONFIG, or pass --models-root.
#
#    Expected per-model files (adjust names in CONFIG if yours differ):
#      distil_it: encoder.int8.onnx, decoder.int8.onnx, tokens.txt
#      parakeet : encoder.int8.onnx, decoder.int8.onnx, joiner.int8.onnx, tokens.txt
#      nemotron : encoder.int8.onnx, decoder.int8.onnx, joiner.int8.onnx, tokens.txt

# 3. Validate the data side first (no model loading):
python eval/run_baseline.py --dry-run

# 4. Run the baseline (enable whatever subset of models you have locally):
python eval/run_baseline.py --backends distil_it,parakeet,nemotron

# 5. Output:
#    eval/results/per_clip_<timestamp>.csv   (clip_id, backend, wer, cer, loops, ref, hyp)
#    eval/results/summary_<timestamp>.md     (mean WER/CER per backend, loop rates)
```

## Limitations (be honest when reading results)

- **Desktop CPU ≠ device latency.** WER/CER/loops are comparable across models;
  do **not** read desktop wall-time as on-device RTF. The Realme device is the only
  source for latency.
- **Sample size 30–50 is directional, not statistically tight.** A 1-point WER
  difference between models on 40 clips is *suggestive*, not conclusive. Report with
  per-clip spread, not just means.
- **Recognizer configs mirror the app but sherpa-onnx Python API nesting can vary
  by version.** The script is pinned to `sherpa-onnx==1.13.3` to match the shipped
  AAR. If a backend fails to construct, the config field shape is the first thing
  to check against the installed version's `OfflineRecognizerConfig` / `OnlineRecognizerConfig`.
- **This measures current models.** The payoff: once a Whisper LoRA fine-tune
  exists, point `run_baseline.py` at it as a 4th backend and the same harness shows
  whether it beat Distil-IT on *real* voice messages.
