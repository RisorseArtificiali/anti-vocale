# Stress matrix: clip length x decode shape (GH #2, TASK-537)

Run 2026-09-19 (night session), desktop harness, `eval/.venv` sherpa-onnx Python
pinned to `.sherpa-version`. Backend: `parakeet-tdt-0.6b-v3-int8`, greedy search,
4 threads. Audio: German FLEURS clips (`eval/smallclass/de/`) tiled end to end
into 30/300/1200/3600-second wavs (16 kHz mono int16).

## Results

| Length | Shape | Decode s | RTF | Chunks | Chars | Outcome |
|---|---|---|---|---|---|---|
| 30 s | whole file | 2.3 | 0.069 | 1 | 349 | GREEN |
| 300 s | whole file | 98.8 | 0.328 | 1 | 3,826 | GREEN |
| 1200 s | whole file | n/a | n/a | 1 | n/a | harness OOM (exit 137) |
| 3600 s | whole file | n/a | n/a | 1 | n/a | harness OOM (exit 137) |
| 1200 s | 30 s chunks | 190.9 | 0.158 | 41 | 15,163 | GREEN |
| 3600 s | 30 s chunks | 777.6 | 0.216 | 121 | 44,917 | GREEN |

Transcript head identical across all green cells ("Ihr erstes war der Slalom..."),
as expected from deterministic tiling.

## Findings

1. **The whole-file failures are a harness artifact, not a model limit.** The
   Python harness converts the entire waveform to a Python list
   (`tolist()`): at 1200 s that is ~19M boxed floats, roughly 750 MB of Python
   objects; at 3600 s roughly 2.3 GB. The OOM killer takes the process
   (exit 137) before the recognizer sees a single sample. The app never does
   this: `AudioPreprocessor` chunks at the policy cap and each chunk feeds the
   recognizer incrementally.
2. **Chunked decode shows no degradation trend with total length.** RTF at
   1200 s (0.158) and 3600 s (0.216) sits inside the range the short cells
   show on a loaded machine (the same 300 s cell measured 0.856 during the
   day with parallel agents running, 0.328 tonight idle). Wall-clock scales
   linearly with audio length; there is no leak-shaped curve.
3. **The 30 s window is the app's shape and it holds at one hour.** 121
   chunks, 44,917 characters, no truncation, no repetition-loop signature.

## Scope limits

- Desktop, not device: no `dumpsys meminfo` telemetry. The memory story on
  device is owned by `AudioDurationPolicy` (TASK-432) and the TASK-575
  measured-footprint records now taken at every load.
- One backend class (offline transducer). The streaming (Nemotron) and
  external-import classes still need cells; the harness
  (`/var/tmp/chwork/task2-stress/subproc_chunked.py`) takes any sherpa model
  dir.

Artifacts: `/var/tmp/chwork/task2-stress/` (run_matrix.py, subproc.py,
subproc_chunked.py, results.json, the tiled wavs; 157 MB total).
