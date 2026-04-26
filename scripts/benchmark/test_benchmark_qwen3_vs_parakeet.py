#!/usr/bin/env python3
"""Tests for benchmark_qwen3_vs_parakeet.py utilities.

Run: python -m pytest scripts/benchmark/test_benchmark_qwen3_vs_parakeet.py -v
"""

import json

import numpy as np
import pytest
import soundfile as sf

# Import functions under test
from benchmark_qwen3_vs_parakeet import (
    load_audio,
    model_size_mb,
    normalize,
    results_to_json,
    wer,
    wer_detail,
)
from benchmark_qwen3_vs_parakeet import SampleResult


# ---------------------------------------------------------------------------
# WER tests
# ---------------------------------------------------------------------------

class TestWER:
    def test_identical_strings(self):
        assert wer("ciao mondo", "ciao mondo") == 0.0

    def test_completely_different(self):
        assert wer("ciao mondo", "addio terra") == 1.0

    def test_single_substitution(self):
        result = wer("ciao mondo", "ciao terra")
        assert result == pytest.approx(0.5)

    def test_insertion(self):
        result = wer("ciao mondo", "ciao bel mondo")
        assert result == pytest.approx(0.5)

    def test_deletion(self):
        result = wer("ciao bel mondo", "ciao mondo")
        assert result == pytest.approx(1 / 3)

    def test_empty_reference(self):
        assert wer("", "ciao") == 0.0

    def test_empty_hypothesis(self):
        assert wer("ciao mondo", "") == 1.0

    def test_both_empty(self):
        assert wer("", "") == 0.0

    def test_case_insensitive(self):
        assert wer("Ciao Mondo", "ciao mondo") == 0.0

    def test_punctuation_stripped_in_normalized_mode(self):
        assert wer("ciao, mondo!", "ciao mondo") == 0.0

    def test_raw_mode_no_normalization(self):
        result = wer("Ciao, mondo!", "Ciao mondo", normalized=False)
        assert result > 0  # punctuation counted as different

    def test_italian_with_accents(self):
        assert wer("perché così è", "perche cosi e") > 0  # accents matter

    def test_word_order_matters(self):
        result = wer("ciao mondo bello", "bello mondo ciao")
        assert result > 0


class TestWERDetail:
    def test_perfect_match(self):
        detail = wer_detail("ciao mondo", "ciao mondo")
        assert detail["wer"] == 0.0
        assert detail["insertions"] == 0
        assert detail["deletions"] == 0
        assert detail["substitutions"] == 0
        assert detail["ref_words"] == 2

    def test_substitution_counted(self):
        detail = wer_detail("ciao mondo", "ciao terra")
        assert detail["substitutions"] == 1
        assert detail["insertions"] == 0
        assert detail["deletions"] == 0

    def test_insertion_counted(self):
        detail = wer_detail("ciao mondo", "ciao bel mondo")
        assert detail["insertions"] == 1

    def test_deletion_counted(self):
        detail = wer_detail("ciao bel mondo", "ciao mondo")
        assert detail["deletions"] == 1

    def test_empty_reference(self):
        detail = wer_detail("", "ciao")
        assert detail["wer"] == 0.0
        assert detail["ref_words"] == 0

    def test_error_types_sum_to_wer(self):
        ref = "il gatto nero mangia il pesce"
        hyp = "il gatto bianco mangia pesce"
        detail = wer_detail(ref, hyp)
        expected_errors = detail["insertions"] + detail["deletions"] + detail["substitutions"]
        assert expected_errors / detail["ref_words"] == pytest.approx(detail["wer"])


# ---------------------------------------------------------------------------
# Normalize tests
# ---------------------------------------------------------------------------

class TestNormalize:
    def test_lowercase(self):
        assert normalize("CIAO") == "ciao"

    def test_punctuation_removed(self):
        assert normalize("ciao, mondo!") == "ciao mondo"

    def test_extra_whitespace_collapsed(self):
        assert normalize("ciao   mondo") == "ciao mondo"

    def test_parentheses_removed(self):
        assert normalize("ciao (mondo)") == "ciao mondo"

    def test_leading_trailing_whitespace(self):
        assert normalize("  ciao  ") == "ciao"


# ---------------------------------------------------------------------------
# Audio loading tests
# ---------------------------------------------------------------------------

class TestLoadAudio:
    def test_load_mono_wav(self, tmp_path):
        sr = 16000
        audio = np.random.randn(sr).astype(np.float32) * 0.1
        wav_path = tmp_path / "test.wav"
        sf.write(str(wav_path), audio, sr)

        loaded, loaded_sr = load_audio(str(wav_path))
        assert loaded_sr == sr
        assert len(loaded) == len(audio)
        assert loaded.dtype == np.float32

    def test_resample_to_16k(self, tmp_path):
        sr = 44100
        audio = np.random.randn(sr).astype(np.float32) * 0.1
        wav_path = tmp_path / "test44k.wav"
        sf.write(str(wav_path), audio, sr)

        loaded, loaded_sr = load_audio(str(wav_path), target_sr=16000)
        assert loaded_sr == 16000
        assert len(loaded) == pytest.approx(16000, rel=0.05)

    def test_stereo_to_mono(self, tmp_path):
        sr = 16000
        audio = np.random.randn(sr, 2).astype(np.float32) * 0.1
        wav_path = tmp_path / "stereo.wav"
        sf.write(str(wav_path), audio, sr)

        loaded, _ = load_audio(str(wav_path))
        assert loaded.ndim == 1
        assert len(loaded) == sr


# ---------------------------------------------------------------------------
# Model size tests
# ---------------------------------------------------------------------------

class TestModelSize:
    def test_empty_dir(self, tmp_path):
        assert model_size_mb(str(tmp_path)) == 0.0

    def test_counts_files(self, tmp_path):
        (tmp_path / "encoder.onnx").write_bytes(b"x" * (1024 * 1024))
        assert model_size_mb(str(tmp_path)) == pytest.approx(1.0, rel=0.01)

    def test_nested_files(self, tmp_path):
        sub = tmp_path / "tokenizer"
        sub.mkdir()
        (sub / "vocab.json").write_bytes(b"x" * 1024)
        assert model_size_mb(str(tmp_path)) == pytest.approx(1024 / (1024 * 1024), rel=0.01)


# ---------------------------------------------------------------------------
# Results serialization tests
# ---------------------------------------------------------------------------

class TestResultsToJson:
    def _make_result(self, index=0, wer_val=0.1):
        return SampleResult(
            index=index, wer_raw=wer_val,
            wer_detail={"wer": wer_val, "insertions": 1, "deletions": 0, "substitutions": 1, "ref_words": 10},
            rtf=0.05, duration_s=5.0, infer_time_s=0.25,
            reference="test reference", hypothesis="test hypothesis",
            model_label="Test",
        )

    def test_basic_serialization(self):
        p = [self._make_result(0, 0.1)]
        q = [self._make_result(0, 0.2)]
        data = results_to_json(p, q, 464.0, 300.0, [{"path": "test.wav", "reference": "test"}])

        assert data["parakeet"]["avg_wer"] == pytest.approx(0.1)
        assert data["qwen3"]["avg_wer"] == pytest.approx(0.2)
        assert data["parakeet"]["model_size_mb"] == 464.0
        assert data["qwen3"]["model_size_mb"] == 300.0
        assert data["num_samples"] == 1
        assert "timestamp" in data

    def test_json_roundtrip(self):
        p = [self._make_result(0, 0.15)]
        q = [self._make_result(0, 0.08)]
        data = results_to_json(p, q, 464.0, 300.0, [{"path": "t.wav", "reference": "r"}])
        text = json.dumps(data, ensure_ascii=False)
        parsed = json.loads(text)
        assert parsed["parakeet"]["avg_wer"] == pytest.approx(0.15)
        assert parsed["qwen3"]["avg_wer"] == pytest.approx(0.08)

    def test_empty_results(self):
        data = results_to_json([], [], 100.0, 200.0, [])
        assert data["parakeet"]["avg_wer"] == 0.0
        assert data["qwen3"]["avg_wer"] == 0.0
        assert data["num_samples"] == 0
