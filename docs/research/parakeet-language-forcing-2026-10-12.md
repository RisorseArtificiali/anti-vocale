# Parakeet TDT v3: why language cannot be forced (experiment record)

Investigation: 2026-10-11/12, opened by the maintainer's idea after GH #146
(French audio transcribed as English). Question: can Parakeet TDT 0.6B v3 be
steered into a target language, the way Whisper's language token or Canary's
src/tgt tokens do? Answer, measured: no. The language decision is acoustic,
local to each segment, made on the encoder side; nothing upstream of a segment
changes it.

## Why the idea was reasonable

The exported vocabulary (tokens.txt) carries the full control-token set:
`<|startoftranscript|>`, `<|startofcontext|>`, `<|predict_lang|>` /
`<|nopredict_lang|>`, punctuation/ITN/timestamp/diarize flags, and the ISO
language tokens (`<|fr|>` = 71, `<|it|>` = 99, `<|en|>` = 64...). This is
exactly the machinery Canary uses for forcing. The gap: sherpa-onnx 1.13.8
(the app's pinned runtime) exposes no decoder priming surface (only
hotwords), so the hypothesis had to be tested outside the app.

## Setup

`eval/priming_poc.py`: a greedy NeMo TDT decoder in onnxruntime, transcribed
line by line from the pinned sherpa sources (DecodeOneTDT: blank at vocab
index 8192, 5 duration heads, LSTM predictor 2x640 stepped one token at a
time; features via kaldi-native-fbank at 128 bins with per-feature CMVN, the
same recipe sherpa applies). Sanity gate: its no-priming decode of a clean
Italian clip is sane and tracks sherpa's own output (2 tokens differ out of
~60, a feature-normalization residue; both decodes are plausible and the
PoC's is arguably the more accurate one). All experiment arms share the same
features, so every comparison below is internally consistent.

Test material, generated locally: French TTS (clean, telephone-band, pink
noise from 0.003 to 0.160 amplitude, echo), an accented case built by having
an English TTS voice read French text (this reproduces the #146 symptom:
baseline decode comes out anglicized), and English TTS as the forcing probe.

## What was tried, and what happened

1. Control-token priming, seven prefix shapes: `[fr]`, `[sot,fr]`,
   `[sot,pnc,fr]`, `[sot,nopnc,fr]`, `[ctx,sot,fr]`, plus the bare state.
   The priming is mechanically live (on Italian audio, `[sot,it]` alters the
   first tokens of the decode), but on the accented case every shape leaves
   the output anglicized. On genuinely English audio, no shape produces
   French.
2. Text prompts: a 2-token and an 8-token French prompt fed as predictor
   context (the Whisper initial_prompt analogue). No language change.
3. Acoustic markers, prepended with silence at the joint: a single clean
   "Bonjour.", a 2.6 s bounded French phrase, and a 9 s French lead. The
   marker itself is transcribed faithfully at the start (predictable,
   strippable pollution), and everything after it still comes out in the
   language of its own acoustics. The 9 s lead leaks at most an occasional
   French word into the first following sentence; the dominant language of
   the content never flips.
4. Robustness probe, the other direction: clean French degraded with the
   full noise ladder plus telephone band plus echo. The model never switches
   language on acoustically French audio; it degrades content words
   ("n'est-ce pas" becomes "mais ce pain") and stays French. The flip to
   English happens only when the pronunciation itself is mostly English
   (the English-voice case).

## Conclusion

v3's per-segment language choice rides on local acoustic evidence. Decoder
history (control tokens or text) cannot override it, and leading audio only
buys its own transcription, not momentum for later segments. Consequences:

- No sherpa-onnx contribution: an upstream `language` parameter would have
  no working mechanism to expose for this export (unlike Canary, where the
  lang tokens enter the decoder input by design).
- The app's language pin stays scoped to the models that honor it (Whisper
  forced decoding, Nemotron per-stream, language-capable external families
  such as Canary), which is the honest UI the picker already implements.
- The definitive test for real-world reports like #146 is the user's actual
  audio; the harness above runs the full matrix on any clip in minutes.

Rerun: `eval/.venv/bin/python priming_poc.py <wav...>` (needs
kaldi-native-fbank in the venv; not added to the pinned requirements.txt).
The full outputs are in the TASK-810 comments on the backlog and in the
session transcript of 2026-10-11/12.
