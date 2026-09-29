#!/usr/bin/env python3
"""TASK-410 AC material: fetch FLEURS it_it clips WITH transcripts (the
original fetch_fleurs.py saved only wavs; the word-recovery metric needs
ground truth). Rows API, 16k mono, 3-25s clips, ~20 rows."""
import json
import os
import subprocess
import urllib.request

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "it")
N = 20


def main():
    os.makedirs(BASE, exist_ok=True)
    rows = []
    offset = 0
    while len(rows) < N and offset < 200:
        url = (f"https://datasets-server.huggingface.co/rows?dataset=google/fleurs"
               f"&config=it_it&split=validation&offset={offset}&length=10")
        d = json.load(urllib.request.urlopen(url, timeout=60))
        if "error" in d or not d.get("rows"):
            print("rows API:", d.get("error", "no rows"))
            return 1
        for r in d["rows"]:
            if len(rows) >= N:
                break
            row = r["row"]
            audio = row["audio"]
            src = audio[0]["src"] if isinstance(audio, list) else audio["src"]
            idx = r.get("row_idx", len(rows))
            wav = f"{BASE}/it_{idx:03d}.wav"
            rc = subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", src,
                                 "-ar", "16000", "-ac", "1", wav]).returncode
            if rc != 0:
                continue
            dur = subprocess.run(["ffprobe", "-v", "quiet", "-show_entries", "format=duration",
                                  "-of", "csv=p=0", wav], capture_output=True, text=True).stdout.strip()
            if not dur or float(dur) < 3.0 or float(dur) > 25.0:
                os.remove(wav)
                continue
            text = (row.get("transcription") or "").strip()
            if not text:
                os.remove(wav)
                continue
            open(f"{BASE}/it_{idx:03d}.txt", "w", encoding="utf-8").write(text + "\n")
            rows.append(idx)
            print(f"kept it_{idx:03d} {float(dur):.1f}s: {text[:60]}")
        offset += 10
    print(f"total {len(rows)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
