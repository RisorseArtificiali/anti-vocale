"""Tests for benchmark_mel_config_ab.py.

Verifies WER computation, audio loading, and recognizer creation logic.
"""

from pathlib import Path
from unittest.mock import MagicMock, patch

import numpy as np
import pytest
import soundfile as sf

import sys
sys.path.insert(0, str(Path(__file__).parent.parent / "benchmark"))
from benchmark_mel_config_ab import normalize, wer, load_audio, create_recognizer


class TestNormalize:
    def test_basic(self):
        assert normalize("Hello, World!") == "hello world"

    def test_punctuation_removal(self):
        assert normalize("a, b; c: d. e! f? g(h)i-j") == "a b c d e f g h i j"

    def test_whitespace_collapse(self):
        assert normalize("  multiple   spaces  ") == "multiple spaces"

    def test_case_folding(self):
        assert normalize("UPPER Case") == "upper case"

    def test_italian_text(self):
        text = "L'iniziativa contro le oscenita e stata finanziata"
        assert normalize(text) == "l'iniziativa contro le oscenita e stata finanziata"

    def test_empty_string(self):
        assert normalize("") == ""

    def test_numbers_preserved(self):
        assert normalize("il 15 metri") == "il 15 metri"


class TestWER:
    def test_perfect_match(self):
        assert wer("hello world", "hello world") == 0.0

    def test_complete_mismatch(self):
        assert wer("hello", "world") == 1.0

    def test_insertion(self):
        # 1-word ref, 2-word hyp: 1 insertion / 1 ref word = 100%
        assert wer("hello", "hello world") == pytest.approx(1.0)

    def test_deletion(self):
        assert wer("hello world", "hello") == pytest.approx(0.5)

    def test_substitution(self):
        # 1-word ref, 1-word hyp with 1 substitution: 1/1 = 100%
        assert wer("hello", "hallo") == pytest.approx(1.0)

    def test_empty_reference(self):
        assert wer("", "hello") == 0.0

    def test_italian_real_world(self):
        ref = "sotto il ponte lo spazio in verticale libero e di 15 metri"
        hyp = "Sotto il ponte, lo spazio in verticale libero e di 15 metri,"
        assert wer(ref, hyp) == 0.0

    def test_italian_with_error(self):
        ref = "l'iniziativa contro le oscenita"
        hyp = "l'iniziativa contro le uscinita"
        result = wer(ref, hyp)
        assert 0 < result < 1

    def test_case_insensitive(self):
        assert wer("HELLO WORLD", "hello world") == 0.0


class TestLoadAudio:
    def test_load_wav(self, tmp_path):
        sr = 8000
        duration = 1.0
        audio = np.sin(2 * np.pi * 440 * np.linspace(0, duration, int(sr * duration))).astype(np.float32)
        wav_path = tmp_path / "test.wav"
        sf.write(str(wav_path), audio, sr)

        loaded, loaded_sr = load_audio(str(wav_path), target_sr=16000)
        assert loaded_sr == 16000
        assert len(loaded) == int(len(audio) * 16000 / 8000)
        assert loaded.dtype == np.float32

    def test_load_stereo(self, tmp_path):
        sr = 16000
        audio_l = np.ones(sr, dtype=np.float32)
        audio_r = np.zeros(sr, dtype=np.float32)
        stereo = np.column_stack([audio_l, audio_r])
        wav_path = tmp_path / "stereo.wav"
        sf.write(str(wav_path), stereo, sr)

        loaded, loaded_sr = load_audio(str(wav_path))
        assert loaded_sr == sr
        assert loaded.ndim == 1
        assert len(loaded) == sr


class TestCreateRecognizer:
    @patch("benchmark_mel_config_ab.sherpa_onnx.OfflineRecognizer")
    def test_default_config(self, mock_cls):
        mock_cls.from_transducer.return_value = MagicMock()

        rec = create_recognizer("/fake/model/dir")
        mock_cls.from_transducer.assert_called_once()
        call_kwargs = mock_cls.from_transducer.call_args[1]
        assert call_kwargs["feature_dim"] == 80
        assert call_kwargs["model_type"] == "nemo_transducer"

    @patch("benchmark_mel_config_ab.sherpa_onnx.OfflineRecognizer")
    def test_explicit_128(self, mock_cls):
        mock_cls.from_transducer.return_value = MagicMock()

        rec = create_recognizer("/fake/model/dir", feature_dim=128)
        call_kwargs = mock_cls.from_transducer.call_args[1]
        assert call_kwargs["feature_dim"] == 128
