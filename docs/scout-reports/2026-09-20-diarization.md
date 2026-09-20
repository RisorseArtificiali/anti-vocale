# Scout report: speaker diarization (GH #83 quality follow-up)

Date: 2026-09-20. Scope: diarization-specific run (TASK-591); the standard tracked
items (VibeVoice, Cohere, LiteRT, llama-bro) were not re-checked in this scoped run.

Question: is our shipped diarization (sherpa-onnx OfflineSpeakerDiarization,
pyannote-segmentation-3.0 int8 + nemo_en_titanet_small, auto clustering at 0.8)
leaving quality on the table, and is the maintainer's memory that "the Mac is
much better" real?

## Method

One common 90 s two-voice Italian interview clip (yt-dlp podcast audio, 16 kHz
mono), run through every candidate stack, compared at the segment level. The
device run is the shipped build on the RMX3853; desktop runs use eval/.venv
(sherpa-onnx Python 1.13.8, matching the AAR); the Mac run uses the omnivoice
deployment's pyannote 3.4 pipeline (speaker-diarization-3.1).

## Result 1: the device is faithful; the clip is the problem

Device ≡ desktop at every threshold (0.7-0.9) and every min_duration_off
(0.5 down to 0.1): 25/25 cue assignments identical, same 2-speaker structure
(question 0-5.9 s, answer 5.9-68.7 s, closing 69-90 s). No platform, threading
or precision issue, and no hidden turn alternation being suppressed by the
duration gates. The trial clip is simply a weak showcase: a broadcast interview
with a 63 s monologue at its center.

## Result 2: the Mac agrees with the phone

pyannote speaker-diarization-3.1 (the full pipeline, via the omnivoice venv on
the Mac, weights: segmentation-3.0 + wespeaker-voxceleb-resnet34-LM) on the same
clip returns the SAME structure: SPEAKER_00 0.03-5.92, SPEAKER_01 5.95-68.66,
SPEAKER_00 69.20-89.97. Identical turn boundaries and attribution; only the
internal fragmentation differs (12 tracks vs our 8, no speaker consequence).
The "much better on Mac" memory does not reproduce on this audio. (Also
established: video-to-transcript.sh on the Mac never diarized; it is
parakeet-mlx only. The remembered experience likely comes from omnivoice's
dub pipeline, which does carry pyannote 3.1.)

## Result 3: the model matrix (same clip, all stacks)

| Stack | Speakers | Turn switches | vs shipped |
|---|---|---|---|
| pyannote-3.0 + titanet_small (shipped) | 2 | 2 | baseline |
| pyannote-3.0 + titanet_large (96.7 MB) | 2 | 2 | identical output, 2.4x size, much slower |
| reverb-v1 + titanet_small/large | 2 | 2 | coarser (3 blocks); Rev non-production license |
| pyannote-3.0 + wespeaker CAM++ @0.5 | 2 | 3 | one extra turn at 61.7-66 vs 66-68.7 |
| pyannote-3.0 + wespeaker CAM++ @0.6 | 1 | 0 | COLLAPSES: threshold scale is fragile |
| pyannote-3.0 + wespeaker resnet34_LM (the Mac pipeline's embedder) | 1 | 0 | COLLAPSES at 0.6/0.7155/0.8: without pyannote's tuned clustering (min_cluster_size 12, centroid linkage) and overlap masking, the embedder alone merges everything |
| pyannote-3.1 full (Mac) | 2 | 2 | identical to shipped |

Threshold insensitivity: titanet variants give the same clustering from 0.7 to
0.9. CAM++ operates on a different scale (docs say 0.5-0.6) and one notch
merges everything into one speaker; if we ever ship it, the threshold must be
model-specific.

## Landscape (what is actually better, and whether we can have it)

Premise correction (verified against the model card and changelog):
speaker-diarization-3.1 has NO ASR-refinement stage; it is the same
segmentation+embedding+clustering recipe as ours, with a different embedder
(wespeaker resnet34-LM, overlap-masked) and tuned clustering (threshold 0.7155,
min_cluster_size 12). No published DER comparison exists between sherpa's
OfflineSpeakerDiarization and the pyannote pipeline; nobody has scored them
side by side.

The measured frontier (DIHARD-III full DER): pyannote 3.1 at 21.7;
pyannote community-1 (2025, VBx refinement, CC-BY-4.0, gated) at 20.2;
DiariZen (BUT-FIT, WavLM EEND, CC-BY-NC-4.0) at 14.5, best open per the ETH
benchmark (arXiv 2509.26177); NVIDIA Sortformer streaming v2 (CC-BY-4.0,
commercial-friendly, 147 MB quantized) near the top and fastest, with an
unmerged community fork adding it to sherpa-onnx (k2-fsa #3497, claiming
97-99.5 percent parity). The ETH benchmark's key finding: the dominant
residual error is boundary precision, which is where word-timestamp
reconciliation (the whisperX pattern, already possible with our cue times)
would help.

## Licensing reality (the reverb question)

The two Rev "reverb" segmentation models are the only newer segmentation
options in sherpa's catalog, and both are non-production licensed (testing,
research, Personal use only; distribution permitted with license passthrough).
Shipping them embedded is out. The maintainer asked whether user-opt-in
download sidesteps this: reading the license, the obligations bind the user,
not the app (we would never distribute or possess the weights), so a
bring-your-own import with an explicit license acknowledgment gate
("personal, non-commercial use only" + full text link) is defensible and is
the standard pattern for gated/non-commercial weights. However: on our audio
reverb-v1 adds nothing (coarser than pyannote), the external-models platform
imports ASR backends only (a diarization-model import surface would be new
work), and the inducement concern remains for work-use transcribers. Verdict:
not worth it until a measurable win exists.

## Verdict

1. Ship nothing new today: the shipped stack reproduces the Mac reference
   exactly on the trial audio, and both titanet_large and reverb are no-ops or
   regressions here.
2. The quality lever with actual evidence behind it is clustering/embedding
   refinement (community-1's VBx, DiariZen), none of which is available in
   sherpa-onnx today; Sortformer streaming v2 is the one commercially licensed
   candidate, and it lands via upstream issue #3497 if ever.
3. For perceived quality on real conversations, the practical next step is a
   better trial sample (fast-alternating two-voice call), not a model swap.
   The resnet34_LM collapse also shows the upgrade levers live in the
   clustering layer (sherpa exposes none of pyannote's knobs beyond threshold
   and cluster count), not in swapping embedding checkpoints.
4. Free wins sitting in our current build: per-segment confidence is computed
   and discarded (v1.13.8 computeConfidence), and the sherpa-vs-pyannote
   mismatch reports upstream (#1708) are worth watching.

Report for TASK-591 / GH #83. Evidence files: /tmp/diar_trial.wav (clip),
Mac command output in the session log, matrix scripts in the session log.
