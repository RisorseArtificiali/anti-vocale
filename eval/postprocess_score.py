#!/usr/bin/env python3
"""
Anti-Vocale post-processing (punctuation-pass) eval harness, TASK-278.

Scores the app's LLM punctuation pass (TASK-276, PunctuationPolicy +
TranscriptionOrchestrator.applyPunctuationPass) on transcript text, so any
correction pass ships with a measured guardrail: punctuation gained, WER not
degraded, content preserved.

Pipeline per sample (mirrors the app path, see the refs in each section):
  1. reference = the punctuated raw-ASR transcript (ground truth for marks)
  2. input     = reference stripped of punctuation + casing. This simulates
     the non-punctuating ASR model the pass exists for (the pass is skipped
     on punctuated output, so feeding the reference verbatim would never run)
  3. prompt    = "<instruction>\\n\\nTranscript:\\n<input>" built by
     final_prompt below (ChunkPromptPolicy.finalPrompt). The instruction is
     the localized punctuation_default_prompt resource, IT by default
  4. polished  = OpenAI-compatible chat completion with a bare user message
     and no system prompt (LlmTranscriptionBackend.generateText is a
     passthrough to LlmManager.generateText, which sends the prompt as the
     only user content)
  5. guards    = needsPunctuation / withinContextLimit / acceptablePolish /
     blank fallback, exactly PunctuationPolicy.kt; a rejected polish keeps
     the input, as in the orchestrator

Metrics: WER raw vs polished vs reference (run_baseline normalization),
punctuation mark F1 (see punct_items/punct_prf), content-preservation ratio
(difflib on normalized word lists, flagged below 0.98).

Sample sources (either one):
  --manifest PATH               TSV: <audio-path-or-id> TAB <transcript>, one
                                per line, '#' comments allowed (smallclass
                                format)
  --samples DIR + --ref-dir DIR clip/transcript pairing by basename, like
                                run_baseline.py discover_pairs (audio is not
                                needed by scoring itself; it documents
                                provenance and backs --transcribe)
With --transcribe, missing transcripts are FIRST generated from the audio by
the eval-venv parakeet (the app's default model) and then used as references.
That provenance ("ASR pseudo-reference", not human-verified) must be stated
in any report built on it.

Backend: a real OpenAI-compatible endpoint (default: the Mac's oMLX gemma) or
--mock (deterministic fake polish, labeled MOCK in every output).

Import purity: importing this module has no side effects and needs only the
stdlib; sherpa_onnx/soundfile are imported lazily and only by --transcribe.
normalize_it/tokenize/_levenshtein/wer, AUDIO_EXTS and the clip/transcript
pairing in discover are mirrored from run_baseline.py rather than imported:
that module sys.exits at import when sherpa-onnx is missing, which would
break the stdlib-only import contract of these scoring functions. One
documented deviation from the mirror: typographic apostrophes are
canonicalized before scoring (see _APOSTROPHE_MAP).
"""

from __future__ import annotations

import argparse
import csv
import difflib
import json
import os
import re
import sys
import time
import urllib.request
from pathlib import Path
from statistics import mean

import audio_loader  # eval/-local: shared ffmpeg+soundfile loader (TASK-461)

# ──────────────────────────────────────────────────────────────────────────────
# CONFIG. App-fidelity constants (PunctuationPolicy.kt, ChunkPromptPolicy.kt,
# values{-it}/strings.xml punctuation_default_prompt, LlmManager.kt).
# ──────────────────────────────────────────────────────────────────────────────

# values-it/strings.xml:436. The eval set is Italian; the IT resource is what
# an Italian-locale device resolves to.
PROMPT_IT = (
    "Aggiungi punteggiatura e maiuscole a questa trascrizione. Mantieni ogni "
    "parola esattamente come scritta; non correggere, tradurre, aggiungere o "
    "rimuovere nulla. Restituisci solo la trascrizione corretta."
)
# values/strings.xml:445 (EN locale).
PROMPT_EN = (
    "Add punctuation and capitalization to this transcript. Keep every word "
    "exactly as written; do not fix, translate, add, or remove anything. "
    "Output only the corrected transcript."
)
DEFAULT_PROMPTS = {"it": PROMPT_IT, "en": PROMPT_EN}


def final_prompt(instruction: str, transcript: str) -> str:
    """ChunkPromptPolicy.finalPrompt, verbatim composition."""
    return f"{instruction}\n\nTranscript:\n{transcript}"


# PunctuationPolicy.kt thresholds; these are load-bearing app behavior.
MIN_LENGTH_CHARS = 12           # PunctuationPolicy.MIN_LENGTH_CHARS
TERMINALS_PER_WORDS_FLOOR = 40  # PunctuationPolicy.TERMINALS_PER_WORDS_FLOOR
MAX_TRANSCRIPT_CHARS = 12_000   # PunctuationPolicy.MAX_TRANSCRIPT_CHARS
MIN_POLISHED_FRACTION = 0.4     # PunctuationPolicy.MIN_POLISHED_FRACTION
TERMINALS = ".!?…"              # PunctuationPolicy.TERMINALS (Latin subset)

MAX_TOKENS = 2048               # LlmManager.MAX_TOKENS (caps the MediaPipe
                                 # fallback; the LiteRT path sets no cap, so
                                 # this is slightly stricter than the app)

CONTENT_FLAG_THRESHOLD = 0.98   # token-diff ratio below this = content flagged

DEFAULT_ENDPOINT = "http://mac.lan:8000/v1"
DEFAULT_MODEL = "gemma-4-26b-a4b-it-4bit"
DEFAULT_API_KEY_ENV = "OMLX_API_KEY"

HERE = Path(__file__).resolve().parent

# ──────────────────────────────────────────────────────────────────────────────
# App-policy mirrors (pure)
# ──────────────────────────────────────────────────────────────────────────────

def needs_punctuation(text: str) -> bool:
    """PunctuationPolicy.needsPunctuation: True = the pass should run.

    Length floor, then: fewer than one terminal mark per 40 words means the
    text is unpunctuated (occurrences, not distinct characters)."""
    trimmed = text.strip()
    if len(trimmed) < MIN_LENGTH_CHARS:
        return False
    words = len(trimmed.split())
    if words == 0:
        return False
    terminals = sum(trimmed.count(ch) for ch in TERMINALS)
    return terminals * TERMINALS_PER_WORDS_FLOOR < words


def within_context_limit(text: str) -> bool:
    """PunctuationPolicy.withinContextLimit (Gemma context guard)."""
    return len(text) <= MAX_TRANSCRIPT_CHARS


def acceptable_polish(polished: str, original: str) -> bool:
    """PunctuationPolicy.acceptablePolish: punctuation may add characters,
    never collapse them below 40% of the original length."""
    return len(polished) >= len(original) * MIN_POLISHED_FRACTION


# ──────────────────────────────────────────────────────────────────────────────
# Text transforms (pure)
# ──────────────────────────────────────────────────────────────────────────────

# Word-forming characters beyond alphanumerics, shared by the input
# simulation (strip_punct_case) and the mark scorer (punct_items): apostrophes
# and hyphens are orthography (l'acqua, un po'), not punctuation. Both layers
# must agree on what a word is, or marks anchor to different word sequences.
WORD_EXTRA_CHARS = "'-"


# Typographic apostrophes canonicalized to straight ones before scoring.
# One deliberate deviation from the run_baseline mirror: that version drops
# U+2019 with no space, so "l'acqua" (straight, from strip_punct_case or the
# model) would tokenize as "l acqua" while a curly-apostrophe reference
# tokenized as "lacqua", silently inflating the raw WER. Canonicalizing both
# sides keeps ref and hyp comparable; run_baseline itself still has the
# asymmetry (noted in TASK-278 for an upstream fix).
_APOSTROPHE_MAP = str.maketrans({"’": "'", "‘": "'"})


def normalize_it(text: str) -> str:
    """WER normalization, run_baseline.normalize_it plus apostrophe
    canonicalization (see _APOSTROPHE_MAP): lowercase, strip [annotations],
    drop punctuation, apostrophes become spaces (l'acqua becomes
    "l acqua"), keep accented letters, collapse whitespace."""
    s = text.lower().translate(_APOSTROPHE_MAP)
    s = re.sub(r"\[[^\]]*\]", " ", s)
    out = []
    for ch in s:
        if ch.isalnum() or ch.isspace():
            out.append(ch)
        elif ch == "'":
            out.append(" ")
    s = "".join(out)
    return re.sub(r"\s+", " ", s).strip()


def tokenize(text: str) -> list[str]:
    return normalize_it(text).split()


def strip_punct_case(text: str) -> str:
    """Simulate the non-punctuating ASR model the pass exists for: lowercase,
    punctuation removed. Apostrophes (l'acqua, un po') and intra-word hyphens
    are orthography, not punctuation, and stay. Curly quotes normalize to
    straight so surface forms match across ref and polished."""
    text = (text.replace("’", "'").replace("‘", "'")
                .replace("”", '"').replace("“", '"'))
    out = []
    for ch in text.lower():
        if ch.isalnum() or ch.isspace() or ch in WORD_EXTRA_CHARS:
            out.append(ch)
    s = "".join(out)
    s = re.sub(r"\s+", " ", s).strip()
    return s.strip("-")


# ──────────────────────────────────────────────────────────────────────────────
# Metrics (pure)
# ──────────────────────────────────────────────────────────────────────────────

def _levenshtein(a: list[str], b: list[str]) -> int:
    """Unit-cost edit distance over token lists = (S + D + I). Mirrors
    run_baseline._levenshtein."""
    if len(a) < len(b):
        a, b = b, a
    if not b:
        return len(a)
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(
                prev[j] + 1,
                cur[j - 1] + 1,
                prev[j - 1] + (ca != cb),
            ))
        prev = cur
    return prev[-1]


def wer(ref_tokens: list[str], hyp_tokens: list[str]) -> float:
    """WER = (S+D+I)/len(ref). Mirrors run_baseline.wer (may exceed 1.0)."""
    if not ref_tokens:
        return 0.0 if not hyp_tokens else float("inf")
    return _levenshtein(ref_tokens, hyp_tokens) / len(ref_tokens)


# Mark model: the two mark classes the pass is asked to add. ';' and ':' group
# with commas (pause class); '.', '!', '?', '…' are terminals. Quotes,
# brackets and dashes are not scored marks and are ignored. A run of
# same-class marks ("...", "?!") counts once.
_MARK_CLASSES = {",": "comma", ";": "comma", ":": "comma",
                 ".": "term", "!": "term", "?": "term", "…": "term"}


def punct_items(text: str) -> list[str]:
    """Alternate word / mark item sequence, e.g.
    "Ciao Paolo, come va?" -> ["ciao", "paolo", "M:comma", "come", "va",
    "M:term"]. Words keep their surface form (lowercased, apostrophes
    canonicalized like normalize_it): mark placement is judged on the words
    the model actually emitted. Marks before the first word are dropped (no
    anchor to score against)."""
    text = text.lower().translate(_APOSTROPHE_MAP)
    items: list[str] = []
    buf: list[str] = []
    seen_word = False
    last_mark: str | None = None

    def flush() -> None:
        nonlocal buf, seen_word, last_mark
        if buf:
            word = "".join(buf).lower()
            if word.strip(WORD_EXTRA_CHARS):
                items.append(word)
                seen_word = True
                last_mark = None
            buf = []

    for ch in text:
        if ch.isalnum() or ch in WORD_EXTRA_CHARS:
            buf.append(ch)
        else:
            flush()
            cls = _MARK_CLASSES.get(ch)
            if cls and seen_word and cls != last_mark:
                items.append("M:" + cls)
                last_mark = cls
    flush()
    return items


def punct_prf(ref_items: list[str], hyp_items: list[str]) -> tuple[int, int, int]:
    """(TP, FP, FN) over mark items via difflib alignment: matching_blocks
    pairs identical items in order; a matched "M:<class>" item is a TP, an
    unmatched mark in hyp is a FP, an unmatched mark in ref is a FN. Word
    items participate in the alignment (they anchor the marks) but are not
    counted. A misplaced mark (comma where ref has a terminal) counts as
    both FP and FN, which is the desired behavior."""
    matcher = difflib.SequenceMatcher(a=ref_items, b=hyp_items, autojunk=False)
    matched_ref = set()
    matched_hyp = set()
    for block in matcher.get_matching_blocks():
        for k in range(block.size):
            matched_ref.add(block.a + k)
            matched_hyp.add(block.b + k)
    tp = sum(1 for i in matched_ref if ref_items[i].startswith("M:"))
    fp = sum(1 for i, it in enumerate(hyp_items)
             if it.startswith("M:") and i not in matched_hyp)
    fn = sum(1 for i, it in enumerate(ref_items)
             if it.startswith("M:") and i not in matched_ref)
    return tp, fp, fn


def f1(tp: int, fp: int, fn: int) -> float:
    """Micro F1 from counts. No marks anywhere = perfect (nothing to add,
    nothing added)."""
    if tp + fp == 0 and tp + fn == 0:
        return 1.0
    if tp == 0:
        return 0.0
    precision = tp / (tp + fp)
    recall = tp / (tp + fn)
    return 2 * precision * recall / (precision + recall)


def content_ratio(raw_text: str, polished_text: str) -> float:
    """Token-level preservation: difflib ratio on normalized word lists.
    Below 0.98 the sample is flagged (the pass must not rewrite content)."""
    return difflib.SequenceMatcher(
        a=tokenize(raw_text), b=tokenize(polished_text), autojunk=False
    ).ratio()


# ──────────────────────────────────────────────────────────────────────────────
# Backends: real OpenAI-compatible endpoint, or deterministic mock
# ──────────────────────────────────────────────────────────────────────────────

def call_endpoint(endpoint: str, model: str, prompt: str, *,
                  api_key: str, temperature: float, top_p: float,
                  max_tokens: int = MAX_TOKENS, timeout: int = 180) -> str:
    """One chat completion, bare user message (the app sends no system prompt
    on this path). Returns the content string; raises on HTTP/parse error."""
    body = json.dumps({
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "temperature": temperature,
        "top_p": top_p,
        "max_tokens": max_tokens,
    }).encode()
    req = urllib.request.Request(
        endpoint.rstrip("/") + "/chat/completions",
        data=body,
        headers={"Content-Type": "application/json",
                 "Authorization": f"Bearer {api_key}"},
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        payload = json.load(resp)
    content = payload["choices"][0]["message"]["content"]
    return content if isinstance(content, str) else ""


def mock_polish(text: str) -> str:
    """Deterministic stand-in for the LLM: sentence-cases, one period every 9
    words, a comma after the 4th word of each sentence. Exercises the full
    pipeline (guards, metrics, report) with zero network; every output it
    produces is labeled MOCK."""
    words = text.split()
    out: list[str] = []
    for i, w in enumerate(words):
        if i % 9 == 0 and i > 0:
            out[-1] = out[-1].rstrip(",.") + "."
        out.append(w.capitalize() if i % 9 == 0 else w)
        if i % 9 == 3:
            out[-1] += ","
    if out:
        out[-1] = out[-1].rstrip(",.") + "."
    return " ".join(out)


# ──────────────────────────────────────────────────────────────────────────────
# Sample discovery
# ──────────────────────────────────────────────────────────────────────────────

AUDIO_EXTS = {".opus", ".m4a", ".mp3", ".wav", ".flac", ".ogg", ".wma"}


def _audio_by_stem(directory: Path) -> dict[str, Path]:
    """Audio clips in a directory by basename stem. First extension wins on
    duplicate stems, like run_baseline.discover_pairs."""
    audio: dict[str, Path] = {}
    for p in sorted(directory.glob("*")):
        if p.suffix.lower() in AUDIO_EXTS:
            audio.setdefault(p.stem, p)
    return audio


def discover(samples_dir: Path, ref_dir: Path):
    """Pair audio with transcripts by basename (run_baseline.discover_pairs
    semantics, mirrored; see the module docstring). Returns
    ([(clip_id, audio_path, transcript), ...], audio without a transcript,
    transcripts without audio)."""
    audio = _audio_by_stem(samples_dir)
    transcripts = {p.stem: p for p in ref_dir.glob("*.txt")}
    pairs = []
    for cid in sorted(set(audio) & set(transcripts)):
        tx = transcripts[cid].read_text(encoding="utf-8").strip()
        pairs.append((cid, audio[cid], tx))
    return pairs, sorted(set(audio) - set(transcripts)), sorted(set(transcripts) - set(audio))


def load_manifest(path: Path):
    """TSV manifest: <audio-path-or-id> TAB <transcript> per line ('#'
    comments and blank lines skipped). Returns [(clip_id, audio_path_or_None,
    transcript), ...]."""
    pairs = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        left, _, tx = line.partition("\t")
        tx = tx.strip()
        if not tx:
            sys.exit(f"manifest line without TAB-separated transcript: {left!r}")
        p = Path(left.strip())
        audio_path = p if p.suffix.lower() in AUDIO_EXTS else None
        pairs.append((p.stem, audio_path, tx))
    return pairs


# ──────────────────────────────────────────────────────────────────────────────
# Optional: generate references from audio with the eval-venv parakeet
# ──────────────────────────────────────────────────────────────────────────────

def transcribe_missing(samples_dir: Path, ref_dir: Path) -> int:
    """Write ref_dir/<id>.txt for every clip missing one, using the app's
    default model (parakeet-tdt-0.6b-v3 int8, greedy) via run_baseline. These
    transcripts are ASR output, not human ground truth: reports built on them
    must say so. Returns the number written. Audio is decoded by the shared
    ffmpeg+soundfile loader (audio_loader.load_audio), the same one
    run_baseline uses."""
    audio = _audio_by_stem(samples_dir)
    todo = [cid for cid in audio if not (ref_dir / f"{cid}.txt").exists()]
    if not todo:
        return 0

    sys.path.insert(0, str(HERE))
    import run_baseline as rb  # noqa: PLC0415 (lazy: it sys.exits if sherpa is missing)

    rec, _ = rb.build_recognizer(rb.BACKENDS["parakeet"])
    ref_dir.mkdir(parents=True, exist_ok=True)
    written = 0
    for cid in todo:
        # Per-clip guard mirrors run_baseline's loop: one undecodable clip
        # must not kill the whole --transcribe run (review finding, TASK-461)
        try:
            samples = audio_loader.load_audio(audio[cid], sample_rate=rb.SAMPLE_RATE)
            text = rb.recognize_offline(rec, samples).strip()
        except Exception as e:  # noqa: BLE001 (decode/recognize both fail soft here)
            print(f"  SKIP {cid}: {e}")
            continue
        (ref_dir / f"{cid}.txt").write_text(text + "\n", encoding="utf-8")
        print(f"  transcribed {cid} ({len(text)} chars) -> {ref_dir / (cid + '.txt')}")
        written += 1
    return written


# ──────────────────────────────────────────────────────────────────────────────
# Run + report
# ──────────────────────────────────────────────────────────────────────────────

def run_pass(pairs, *, polish_fn) -> list[dict]:
    """Drive the pipeline over samples with the given polish function (a
    call_endpoint closure or mock_polish). Pure scoring, no I/O."""
    rows = []
    for cid, audio_path, ref_text in pairs:
        row: dict = {
            "clip_id": cid,
            "source": str(audio_path) if audio_path else "",
            "ref": ref_text,
            "error": "",
        }
        stripped = strip_punct_case(ref_text)
        row["input"] = stripped
        # App gates, in the orchestrator's order. Every rejection keeps the
        # input, exactly as the orchestrator delivers the raw transcript.
        row["needs_punct"] = needs_punctuation(stripped)
        row["in_context"] = within_context_limit(stripped)
        polished_text, guard = stripped, "skipped(app-policy)"
        if row["needs_punct"] and row["in_context"]:
            try:
                candidate = polish_fn(stripped)
            except Exception as e:  # noqa: BLE001 (one sample must not sink the run)
                row["error"] = str(e)
                guard = "error->raw"
            else:
                # App order (TranscriptionOrchestrator): the collapse guard
                # runs first; a blank polish is caught by it too (0 < 0.4*len).
                candidate = candidate.strip()
                if not acceptable_polish(candidate, stripped):
                    guard = "collapse->raw"
                elif not candidate:
                    guard = "blank->raw"
                else:
                    polished_text, guard = candidate, "applied"
        row["polished"], row["guard"] = polished_text, guard
        # Metrics, always computed; skipped samples score the identity pass.
        ref_tokens = tokenize(ref_text)
        ref_items = punct_items(ref_text)
        row["wer_raw"] = wer(ref_tokens, tokenize(stripped))
        row["wer_polished"] = wer(ref_tokens, tokenize(polished_text))
        row["wer_delta"] = row["wer_polished"] - row["wer_raw"]
        tp, fp, fn = punct_prf(ref_items, punct_items(polished_text))
        row.update(punct_tp=tp, punct_fp=fp, punct_fn=fn, punct_f1=f1(tp, fp, fn))
        # Contrast: how the unpolished input itself scores on marks.
        itp, ifp, ifn = punct_prf(ref_items, punct_items(stripped))
        row["punct_f1_input"] = f1(itp, ifp, ifn)
        row["content_ratio"] = content_ratio(stripped, polished_text)
        row["content_flag"] = int(row["content_ratio"] < CONTENT_FLAG_THRESHOLD)
        row["n_words"] = len(ref_tokens)
        rows.append(row)
    return rows


def aggregate(rows: list[dict]) -> dict:
    wers_raw = [r["wer_raw"] for r in rows if r["wer_raw"] != float("inf")]
    wers_pol = [r["wer_polished"] for r in rows if r["wer_polished"] != float("inf")]
    mean_raw = mean(wers_raw) if wers_raw else 0.0
    mean_pol = mean(wers_pol) if wers_pol else 0.0
    tp = sum(r["punct_tp"] for r in rows)
    fp = sum(r["punct_fp"] for r in rows)
    fn = sum(r["punct_fn"] for r in rows)
    return {
        "n": len(rows),
        "n_applied": sum(1 for r in rows if r["guard"] == "applied"),
        "guards": {g: sum(1 for r in rows if r["guard"] == g)
                   for g in sorted({r["guard"] for r in rows})},
        "mean_wer_raw": mean_raw,
        "mean_wer_polished": mean_pol,
        "wer_delta": (mean_pol - mean_raw) if (wers_pol and wers_raw) else 0.0,
        "punct_tp": tp, "punct_fp": fp, "punct_fn": fn,
        "punct_precision": tp / (tp + fp) if tp + fp else 0.0,
        "punct_recall": tp / (tp + fn) if tp + fn else 0.0,
        "punct_f1_micro": f1(tp, fp, fn),
        "mean_punct_f1": mean(r["punct_f1"] for r in rows) if rows else 0.0,
        "mean_content_ratio": mean(r["content_ratio"] for r in rows) if rows else 0.0,
        "min_content_ratio": min((r["content_ratio"] for r in rows), default=1.0),
        "n_content_flagged": sum(r["content_flag"] for r in rows),
    }


def fmt_pct(x: float) -> str:
    return f"{100 * x:.1f}%"


def write_report(path: Path, *, label: str, backend: str, model: str,
                 temperature: float, top_p: float, prompt_lang: str,
                 ref_provenance: str, rows: list[dict], agg: dict,
                 include_text: bool) -> None:
    """Markdown report. Transcript text is PII: it is included ONLY when
    include_text is set, and that variant is expected to stay in the
    gitignored results/ directory."""
    lines = [
        f"# Post-processing eval, {label}",
        "",
        f"- Backend: {backend} (model `{model}`, temperature {temperature}, top_p {top_p})",
        f"- Prompt: localized punctuation_default_prompt, lang `{prompt_lang}`, "
        f"composed via ChunkPromptPolicy.finalPrompt",
        f"- Samples: {agg['n']} (pass applied on {agg['n_applied']})",
        f"- Guards: {agg['guards']}",
        f"- Reference provenance: {ref_provenance}",
        "",
        "## Aggregates",
        "",
        f"- Mean WER raw vs ref:      {fmt_pct(agg['mean_wer_raw'])}",
        f"- Mean WER polished vs ref: {fmt_pct(agg['mean_wer_polished'])}",
        f"- WER delta (polished minus raw): {fmt_pct(agg['wer_delta'])}",
        f"- Punctuation micro P/R/F1: {fmt_pct(agg['punct_precision'])} / "
        f"{fmt_pct(agg['punct_recall'])} / {fmt_pct(agg['punct_f1_micro'])}"
        f" (TP {agg['punct_tp']}, FP {agg['punct_fp']}, FN {agg['punct_fn']})",
        f"- Mean per-sample punct F1: {fmt_pct(agg['mean_punct_f1'])}",
        f"- Content ratio mean/min:   {agg['mean_content_ratio']:.4f} / "
        f"{agg['min_content_ratio']:.4f}; flagged below {CONTENT_FLAG_THRESHOLD}: "
        f"{agg['n_content_flagged']}/{agg['n']}",
        "",
        "## Per sample",
        "",
        "| clip | words | WER raw | WER polished | delta WER | punct F1 (input to polished) | content | guard |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for r in rows:
        lines.append(
            f"| {r['clip_id']} | {r['n_words']} | {r['wer_raw']:.4f} | "
            f"{r['wer_polished']:.4f} | {r['wer_delta']:+.4f} | "
            f"{r['punct_f1_input']:.3f} to {r['punct_f1']:.3f} | "
            f"{r['content_ratio']:.4f}{' FLAG' if r['content_flag'] else ''} | {r['guard']} |"
        )
    if include_text:
        lines += ["", "## Transcript detail (PII; keep this file out of git)", ""]
        for r in rows:
            lines += [f"### {r['clip_id']} [{r['guard']}]",
                      f"- ref:      {r['ref']}",
                      f"- input:    {r['input']}",
                      f"- polished: {r['polished']}", ""]
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    ap = argparse.ArgumentParser(
        description="Score the app's LLM punctuation pass (TASK-276) on transcripts (TASK-278).")
    ap.add_argument("--samples", default=str(HERE / "clips"),
                    help="Dir of audio clips (provenance and --transcribe source).")
    ap.add_argument("--ref-dir", default=str(HERE / "transcripts"),
                    help="Dir of <clip_id>.txt reference transcripts.")
    ap.add_argument("--manifest", default=None,
                    help="TSV manifest (path-or-id TAB transcript) instead of samples+ref-dir.")
    ap.add_argument("--transcribe", action="store_true",
                    help="Generate missing references from audio via the eval-venv "
                         "parakeet (ASR pseudo-references; document provenance).")
    ap.add_argument("--endpoint", default=DEFAULT_ENDPOINT,
                    help=f"OpenAI-compatible base URL (default {DEFAULT_ENDPOINT}).")
    ap.add_argument("--model", default=DEFAULT_MODEL,
                    help=f"Model id (default {DEFAULT_MODEL}).")
    ap.add_argument("--api-key-env", default=DEFAULT_API_KEY_ENV,
                    help=f"Env var holding the endpoint key (default {DEFAULT_API_KEY_ENV}).")
    ap.add_argument("--mock", action="store_true",
                    help="No network: deterministic fake polish, labeled MOCK.")
    ap.add_argument("--temperature", type=float, default=0.0,
                    help="Sampling temperature, 0 = greedy. Note: the app's pass runs "
                         "on the chat-tuned conversation (temperature 0.8); the "
                         "committed baseline report evaluates both.")
    ap.add_argument("--top-p", type=float, default=1.0)
    ap.add_argument("--prompt-lang", choices=sorted(DEFAULT_PROMPTS), default="it")
    ap.add_argument("--label", default=None, help="Run label for the report title.")
    ap.add_argument("--report-out", default=None,
                    help="Markdown report path (default results/postprocess_<stamp>.md).")
    ap.add_argument("--results-dir", default=str(HERE / "results"))
    ap.add_argument("--include-text", action="store_true",
                    help="Include verbatim transcripts in the report (PII: only for "
                         "gitignored results/).")
    args = ap.parse_args()

    if args.manifest:
        pairs = load_manifest(Path(args.manifest))
        ref_provenance = "manifest as given"
    else:
        samples_dir, ref_dir = Path(args.samples), Path(args.ref_dir)
        if args.transcribe:
            n = transcribe_missing(samples_dir, ref_dir)
            if n:
                print(f"Generated {n} ASR reference transcript(s); pseudo-references, not human truth.")
        pairs, orphan_audio, orphan_tx = discover(samples_dir, ref_dir)
        if orphan_audio:
            print(f"  WARNING: {len(orphan_audio)} clip(s) without a transcript: {orphan_audio[:5]}")
        if orphan_tx:
            print(f"  WARNING: {len(orphan_tx)} transcript(s) without audio: {orphan_tx[:5]}")
        ref_provenance = ("ASR pseudo-references (parakeet-tdt-0.6b-v3 int8 greedy, --transcribe)"
                          if args.transcribe else "transcripts dir as given")
    if not pairs:
        sys.exit("No samples. Give --manifest, or clips+transcripts (see eval/README.md).")

    if args.mock:
        backend, model = "MOCK (deterministic, no network)", "mock"
        polish_fn = mock_polish
    else:
        api_key = os.environ.get(args.api_key_env, "")
        if not api_key:
            sys.exit(f"{args.api_key_env} not set; export it or pass --api-key-env.")
        backend, model = args.endpoint, args.model
        instruction = DEFAULT_PROMPTS[args.prompt_lang]

        def polish_fn(text: str) -> str:
            return call_endpoint(args.endpoint, args.model,
                                 final_prompt(instruction, text),
                                 api_key=api_key, temperature=args.temperature,
                                 top_p=args.top_p)

    stamp = time.strftime("%Y%m%d-%H%M%S")
    label = args.label or f"{model} t={args.temperature} {stamp}"
    print(f"Samples: {len(pairs)} | backend: {backend} | model: {model} "
          f"| temp {args.temperature} | prompt-lang {args.prompt_lang}")

    rows = run_pass(pairs, polish_fn=polish_fn)
    agg = aggregate(rows)

    results_dir = Path(args.results_dir)
    results_dir.mkdir(parents=True, exist_ok=True)
    csv_path = results_dir / f"postprocess_{stamp}.csv"
    # Schema follows run_pass's rows; only the transcript tail is reordered.
    text_tail = ("ref", "input", "polished")
    fields = [k for k in rows[0] if k not in text_tail] + list(text_tail)
    with csv_path.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)

    report_path = (Path(args.report_out) if args.report_out
                   else results_dir / f"postprocess_{stamp}.md")
    write_report(report_path, label=label, backend=backend, model=model,
                 temperature=args.temperature, top_p=args.top_p,
                 prompt_lang=args.prompt_lang, ref_provenance=ref_provenance,
                 rows=rows, agg=agg, include_text=args.include_text)

    print(f"\nWrote {csv_path}")
    print(f"Wrote {report_path}\n")
    print(f"Aggregate over {agg['n']} samples ({agg['n_applied']} pass-applied):")
    print(f"  WER raw {fmt_pct(agg['mean_wer_raw'])} | polished {fmt_pct(agg['mean_wer_polished'])} "
          f"| delta {fmt_pct(agg['wer_delta'])}")
    print(f"  punct micro P/R/F1 {fmt_pct(agg['punct_precision'])}/{fmt_pct(agg['punct_recall'])}/"
          f"{fmt_pct(agg['punct_f1_micro'])} (TP {agg['punct_tp']} FP {agg['punct_fp']} FN {agg['punct_fn']})")
    print(f"  content ratio mean {agg['mean_content_ratio']:.4f}, min {agg['min_content_ratio']:.4f}, "
          f"flagged {agg['n_content_flagged']}/{agg['n']}")
    if args.mock:
        print("  *** MOCK RUN: numbers exercise the pipeline only, not the model ***")


if __name__ == "__main__":
    main()
