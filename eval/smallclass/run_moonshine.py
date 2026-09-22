#!/usr/bin/env python3
"""TASK-619: moonshine v2 (.ort pair) eval on the FLEURS 10-clip sets.

Separate from run_eval.py so the existing results.json stays untouched (that
script also runs its whole eval at import time, so no shared import is
possible). v2 uses the wheel's public from_moonshine_v2 factory.

Baseline reference points: the models are the "base" size; the comparison
that matters for the catalog decision is against what the app offers in that
language today (nothing for uk/vi; whisper-arabic 1087MB and canary-es 207MB
are device-side, so here the gate is absolute quality on voice-message-length
clips, not a head-to-head).
"""
import json, os, re, sys, time, wave

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
import sherpa_onnx

BASE = os.path.dirname(os.path.abspath(__file__))
MODELS = f"{BASE}/models"
NUM_THREADS = 4
GEN = "2026-02-27"

# FLEURS transcription ground truth is unnormalized; WER is computed with the
# same norm() as run_eval.py so numbers are comparable across scripts.
def norm(text):
    text = text.lower()
    text = re.sub(r"[^\w\s']", " ", text)
    return text.split()

def wer(ref, hyp):
    m, n = len(ref), len(hyp)
    d = list(range(n + 1))
    for i in range(1, m + 1):
        prev = d[0]
        d[0] = i
        for j in range(1, n + 1):
            cur = d[j]
            d[j] = min(d[j] + 1, d[j - 1] + 1, prev + (ref[i - 1] != hyp[j - 1]))
            prev = cur
    return d[n], m, n

def load_wav(path):
    with wave.open(path) as w:
        assert w.getframerate() == 16000, f"{path}: {w.getframerate()}"
        data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
    return (data.astype(np.float32) / 32768.0).tolist()

def recognizer_for(lang):
    d = f"{MODELS}/sherpa-onnx-moonshine-base-{lang}-quantized-{GEN}"
    return sherpa_onnx.OfflineRecognizer.from_moonshine_v2(
        encoder=f"{d}/encoder_model.ort",
        decoder=f"{d}/decoder_model_merged.ort",
        tokens=f"{d}/tokens.txt", num_threads=NUM_THREADS, provider="cpu")

def read_manifest(lang):
    with open(f"{BASE}/{lang}/manifest.tsv") as f:
        return [line.rstrip("\n").split("\t", 1) for line in f]

# --capped re-decodes in fixed CAP-second chunks (the app's family chunk cap;
# no VAD emulation: RESULTS.md labels this the fixed-cut approximation). CAP
# mirrors the app value: the decode path pads every chunk with 1s of silence
# and the measured empty-decode ceiling is ~9.25s TOTAL input, so 8+1 fits.
CAP = 8
CAPPED = "--capped" in sys.argv

def decode(rec, samples):
    if not CAPPED:
        s = rec.create_stream()
        s.accept_waveform(16000, samples)
        rec.decode_stream(s)
        return getattr(s.result, "text", "") or ""
    pieces = []
    for i in range(0, len(samples), CAP * 16000):
        s = rec.create_stream()
        s.accept_waveform(16000, samples[i:i + CAP * 16000])
        rec.decode_stream(s)
        pieces.append((getattr(s.result, "text", "") or "").strip())
    return " ".join(p for p in pieces if p)

results = []
for lang in ("uk", "ar", "es", "vi"):
    entry = {"name": f"moonshine-base-{lang} ({GEN}){' capped' if CAPPED else ''}", "lang": lang}
    try:
        rec = recognizer_for(lang)
    except Exception as e:
        entry["load"] = f"FAIL: {e}"
        results.append(entry)
        continue
    entry["load"] = "OK"
    total_err = total_ref = total_hyp = 0
    total_audio = total_decode = 0.0
    hyps = []
    for path, ref in read_manifest(lang):
        with wave.open(path) as w:
            assert w.getframerate() == 16000, f"{path}: {w.getframerate()}"
            data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
            total_audio += w.getnframes() / 16000
        samples = (data.astype(np.float32) / 32768.0).tolist()
        t0 = time.perf_counter()
        hyp = decode(rec, samples)
        total_decode += time.perf_counter() - t0
        e, r, h = wer(norm(ref), norm(hyp))
        total_err += e; total_ref += r; total_hyp += h
        hyps.append({"file": os.path.basename(path), "ref": ref, "hyp": hyp})
    if total_hyp == 0:
        # run_eval.py's guard: an all-blank run is a decode FAIL (the
        # over-ceiling failure mode of this very family), never a plain WER.
        entry["error"] = "decode FAIL: all hypotheses empty"
        results.append(entry)
        continue
    entry.update(
        wer=100.0 * total_err / max(total_ref, 1),
        rtf=total_decode / total_audio,
        audio_s=round(total_audio, 1),
        detail=hyps,
    )
    results.append(entry)

out = f"{BASE}/results_moonshine_capped.json" if CAPPED else f"{BASE}/results_moonshine.json"
with open(out, "w") as f:
    json.dump(results, f, indent=1, ensure_ascii=False)
    f.write("\n")
for r in results:
    if r.get("load") != "OK":
        print(r)
        continue
    print(f"{r['name']}: WER {r['wer']:.1f}%  RTF {r['rtf']:.2f}  ({r['audio_s']}s)")
