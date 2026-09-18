#!/usr/bin/env python3
"""Smoke + eval small-class monolingual sherpa-onnx candidates on FLEURS 10-clip sets."""
import json, os, re, sys, time, wave

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sherpa_onnx

BASE = os.path.dirname(os.path.abspath(__file__))
MODELS = f"{BASE}/models"
NUM_THREADS = 4


import numpy as np

def load_wav(path):
    with wave.open(path) as w:
        assert w.getframerate() == 16000, f"{path}: {w.getframerate()}"
        data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
    return (data.astype(np.float32) / 32768.0).tolist()


def norm(text):
    text = text.lower()
    text = re.sub(r"[^\w\s']", " ", text)  # keep letters/digits/apostrophes
    return text.split()


def wer(ref, hyp):
    # Levenshtein on word lists
    m, n = len(ref), len(hyp)
    d = list(range(n + 1))
    for i in range(1, m + 1):
        prev = d[0]
        d[0] = i
        for j in range(1, n + 1):
            cur = d[j]
            d[j] = min(d[j] + 1, d[j - 1] + 1, prev + (ref[i - 1] != hyp[j - 1]))
            prev = cur
    return d[n], m, n  # errors, ref_len, hyp_len


def read_manifest(lang):
    clips = []
    with open(f"{BASE}/{lang}/manifest.tsv") as f:
        for line in f:
            path, text = line.rstrip("\n").split("\t", 1)
            clips.append((path, text))
    return clips


def decode_offline(recognizer, samples):
    s = recognizer.create_stream()
    s.accept_waveform(16000, samples)
    recognizer.decode_stream(s)
    return getattr(s.result, "text", "") or ""


def decode_online(recognizer, samples):
    s = recognizer.create_stream()
    # simulate streaming in 100ms chunks
    chunk = 1600
    tail = ""
    for i in range(0, len(samples), chunk):
        s.accept_waveform(16000, samples[i:i + chunk])
        while recognizer.is_ready(s):
            recognizer.decode_stream(s)
        tail = recognizer.get_result(s)
    s.input_finished()
    while recognizer.is_ready(s):
        recognizer.decode_stream(s)
    return recognizer.get_result(s) or tail


def eval_model(name, lang, loader, streaming):
    clips = read_manifest(lang)
    total_err = total_ref = total_hyp = 0
    total_audio = total_decode = 0.0
    hyps = []
    for path, ref in clips:
        samples = load_wav(path)
        with wave.open(path) as w:
            total_audio += w.getnframes() / 16000
        t0 = time.perf_counter()
        try:
            hyp = loader(samples)
        except Exception as e:
            return {"name": name, "lang": lang, "load": "OK", "error": f"decode fail {path}: {e}"}
        dt = time.perf_counter() - t0
        total_decode += dt
        e, r, h = wer(norm(ref), norm(hyp))
        total_err += e; total_ref += r; total_hyp += h
        hyps.append({"file": os.path.basename(path), "ref": ref, "hyp": hyp})
    if total_hyp == 0:
        return {"name": name, "lang": lang, "load": "OK",
                "error": "decode FAIL: all hypotheses empty (onnxruntime error in decode, see stderr)"}
    return {
        "name": name, "lang": lang, "load": "OK",
        "wer": 100.0 * total_err / max(total_ref, 1),
        "rtf": total_decode / total_audio,
        "audio_s": round(total_audio, 1),
        "detail": hyps,
    }


ES = f"{MODELS}/sherpa-onnx-zipformer-streaming-robust-es-v0"
DE_W = f"{MODELS}/sherpa-onnx-whisper-tiny-de"
RU = f"{MODELS}/sherpa-onnx-zipformer-ru-int8-2025-04-20"
FR = f"{MODELS}/sherpa-onnx-streaming-zipformer-fr-kroko-2025-08-06"
FA = f"{MODELS}/shenava-koochik-v1.5-rnnt"

results = []

def run(name, lang, streaming, build):
    try:
        rec = build()
    except Exception as e:
        results.append({"name": name, "lang": lang, "load": f"FAIL: {e}"})
        return
    fn = (lambda s: decode_online(rec, s)) if streaming else (lambda s: decode_offline(rec, s))
    results.append(eval_model(name, lang, fn, streaming))

run("bookbot zipformer-streaming-robust-es (int8)", "es", True, lambda: sherpa_onnx.OnlineRecognizer.from_transducer(
    encoder=f"{ES}/encoder-epoch-80-avg-3-chunk-16-left-128.int8.onnx",
    decoder=f"{ES}/decoder-epoch-80-avg-3-chunk-16-left-128.int8.onnx",
    joiner=f"{ES}/joiner-epoch-80-avg-3-chunk-16-left-128.int8.onnx",
    tokens=f"{ES}/tokens.txt", num_threads=NUM_THREADS, provider="cpu"))

run("wanderer51 whisper-tiny-de (int8)", "de", False, lambda: sherpa_onnx.OfflineRecognizer.from_whisper(
    encoder=f"{DE_W}/whisper-tiny-de-encoder.int8.onnx",
    decoder=f"{DE_W}/whisper-tiny-de-decoder.int8.onnx",
    tokens=f"{DE_W}/whisper-tiny-de-tokens.txt", num_threads=NUM_THREADS, provider="cpu",
    language="de", task="transcribe", tail_paddings=2000))

run("csukuangfj zipformer-ru int8", "ru", False, lambda: sherpa_onnx.OfflineRecognizer.from_transducer(
    encoder=f"{RU}/encoder.int8.onnx",
    decoder=f"{RU}/decoder.onnx",
    joiner=f"{RU}/joiner.int8.onnx",
    tokens=f"{RU}/tokens.txt", num_threads=NUM_THREADS, provider="cpu"))

run("csukuangfj streaming-zipformer-fr-kroko (fp32)", "fr", True, lambda: sherpa_onnx.OnlineRecognizer.from_transducer(
    encoder=f"{FR}/encoder.onnx", decoder=f"{FR}/decoder.onnx", joiner=f"{FR}/joiner.onnx",
    tokens=f"{FR}/tokens.txt", num_threads=NUM_THREADS, provider="cpu"))

# fr cross-check on de
run("fr-kroko on de clips (cross-check)", "de", True, lambda: sherpa_onnx.OnlineRecognizer.from_transducer(
    encoder=f"{FR}/encoder.onnx", decoder=f"{FR}/decoder.onnx", joiner=f"{FR}/joiner.onnx",
    tokens=f"{FR}/tokens.txt", num_threads=NUM_THREADS, provider="cpu"))

# TASK-550: the Shenava Persian catalog entry. NeMo FastConformer RNNT, so
# model_type="nemo_transducer" is REQUIRED (same as Parakeet/GigaAM: the NeMo
# decoder carries no vocab_size metadata and the plain-transducer decoder-init
# path exits 255).
run("shenava-koochik-v1.5-rnnt (int8)", "fa", False, lambda: sherpa_onnx.OfflineRecognizer.from_transducer(
    encoder=f"{FA}/encoder.int8.onnx",
    decoder=f"{FA}/decoder.int8.onnx",
    joiner=f"{FA}/joiner.int8.onnx",
    tokens=f"{FA}/tokens.txt", num_threads=NUM_THREADS, provider="cpu",
    model_type="nemo_transducer"))

with open(f"{BASE}/results.json", "w") as f:
    json.dump(results, f, indent=1, ensure_ascii=False)
for r in results:
    print({k: v for k, v in r.items() if k != "detail"})
