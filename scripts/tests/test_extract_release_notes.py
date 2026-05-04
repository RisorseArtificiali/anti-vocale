"""Unit tests for scripts/extract-release-notes.py."""

from __future__ import annotations

import importlib.util
import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

_SCRIPT_PATH = str(Path(__file__).resolve().parent.parent / "extract-release-notes.py")
_spec = importlib.util.spec_from_file_location("extract_release_notes", _SCRIPT_PATH)
mod = importlib.util.module_from_spec(_spec)
sys.modules["extract_release_notes"] = mod
_spec.loader.exec_module(mod)

_SAMPLE_XML = """\
<en-US>
What's new in 1.4.0:

- Feature A
- Feature B

What's new in 1.3.1:

- Feature C
</en-US>

<it-IT>
Novità della versione 1.4.0:

- Funzionalità A
- Funzionalità B

Novità della versione 1.3.1:

- Funzionalità C
</it-IT>
"""

_XML_NO_EN_CONTENT = """\
<en-US>
</en-US>

<it-IT>
Novità della versione 1.4.0:

- Funzionalità A
</it-IT>
"""


def _write_xml(tmpdir: str, content: str = _SAMPLE_XML) -> str:
    """Write XML content to a temp file and return its path."""
    path = os.path.join(tmpdir, "release-notes.xml")
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    return path


class TestParseLocaleSections(unittest.TestCase):
    """Tests for parse_locale_sections."""

    def test_extracts_both_locales(self):
        result = mod.parse_locale_sections(_SAMPLE_XML)
        self.assertIn("en-US", result)
        self.assertIn("it-IT", result)

    def test_excludes_tags_from_content(self):
        result = mod.parse_locale_sections(_SAMPLE_XML)
        self.assertFalse(result["en-US"].startswith("<en-US>"))
        self.assertFalse(result["en-US"].endswith("</en-US>"))


class TestExtractLatestVersion(unittest.TestCase):
    """Tests for extract_latest_version."""

    def test_extracts_first_version_only(self):
        notes = (
            "What's new in 1.4.0:\n\n- Feature A\n\n"
            "What's new in 1.3.1:\n\n- Feature B"
        )
        result = mod.extract_latest_version(notes)
        self.assertIn("1.4.0", result)
        self.assertNotIn("1.3.1", result)
        self.assertNotIn("Feature B", result)

    def test_returns_all_if_single_version(self):
        notes = "What's new in 1.4.0:\n\n- Feature A"
        result = mod.extract_latest_version(notes)
        self.assertEqual(result, notes)

    def test_italian_heading_recognized(self):
        notes = (
            "Novità della versione 1.4.0:\n\n- A\n\n"
            "Novità della versione 1.3.1:\n\n- B"
        )
        result = mod.extract_latest_version(notes)
        self.assertIn("1.4.0", result)
        self.assertNotIn("1.3.1", result)

    def test_returns_input_if_no_heading(self):
        notes = "- Just some notes"
        result = mod.extract_latest_version(notes)
        self.assertEqual(result, notes)

    def test_strips_trailing_whitespace(self):
        notes = "What's new in 1.4.0:\n\n- Feature A\n\n"
        result = mod.extract_latest_version(notes)
        self.assertFalse(result.endswith("\n"))


class TestTruncate(unittest.TestCase):
    """Tests for truncate."""

    def test_short_text_unchanged(self):
        text = "Short text"
        self.assertEqual(mod.truncate(text), text)

    def test_long_text_truncated_to_500(self):
        text = "x" * 600
        result = mod.truncate(text)
        self.assertEqual(len(result), 500)

    def test_exact_500_unchanged(self):
        text = "x" * 500
        result = mod.truncate(text)
        self.assertEqual(len(result), 500)


class TestExtractNotes(unittest.TestCase):
    """Integration tests for extract_notes."""

    def setUp(self):
        self.tmpdir = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.tmpdir)

    def _output_dir(self) -> str:
        return os.path.join(self.tmpdir, "output")

    def test_successful_extraction(self):
        xml_path = _write_xml(self.tmpdir)
        out = self._output_dir()
        exit_code = mod.extract_notes(xml_path, out)

        self.assertEqual(exit_code, 0)
        self.assertTrue(Path(out, "whatsnew-en-US").exists())
        self.assertTrue(Path(out, "whatsnew-it-IT").exists())

    def test_latest_version_only_in_output(self):
        xml_path = _write_xml(self.tmpdir)
        out = self._output_dir()
        mod.extract_notes(xml_path, out)

        en = Path(out, "whatsnew-en-US").read_text()
        self.assertIn("1.4.0", en)
        self.assertNotIn("1.3.1", en)
        self.assertNotIn("Feature C", en)

        it = Path(out, "whatsnew-it-IT").read_text()
        self.assertIn("1.4.0", it)
        self.assertNotIn("1.3.1", it)

    def test_truncation(self):
        long_notes = "What's new in 2.0.0:\n\n" + "- " + "x" * 100 + "\n"
        xml = f"<en-US>\n{long_notes}\n</en-US>\n<it-IT>\n{long_notes}\n</it-IT>\n"
        xml_path = _write_xml(self.tmpdir, xml)
        out = self._output_dir()
        mod.extract_notes(xml_path, out)

        en = Path(out, "whatsnew-en-US").read_text()
        self.assertLessEqual(len(en), 500)

    def test_fallback_for_en(self):
        xml_path = _write_xml(self.tmpdir, _XML_NO_EN_CONTENT)
        out = self._output_dir()
        mod.extract_notes(xml_path, out, fallback="Bug fixes and improvements")

        en = Path(out, "whatsnew-en-US").read_text()
        self.assertEqual(en, "Bug fixes and improvements")

    def test_fallback_not_used_when_en_has_content(self):
        xml_path = _write_xml(self.tmpdir)
        out = self._output_dir()
        mod.extract_notes(xml_path, out, fallback="Should not appear")

        en = Path(out, "whatsnew-en-US").read_text()
        self.assertNotIn("Should not appear", en)

    def test_fallback_only_applies_to_en(self):
        xml = "<en-US>\n</en-US>\n<it-IT>\n</it-IT>\n"
        xml_path = _write_xml(self.tmpdir, xml)
        out = self._output_dir()
        mod.extract_notes(xml_path, out, fallback="Fallback text")

        it = Path(out, "whatsnew-it-IT").read_text()
        self.assertEqual(it, "")

    def test_missing_xml_file(self):
        exit_code = mod.extract_notes("/nonexistent/path.xml", self._output_dir())
        self.assertEqual(exit_code, 1)

    def test_empty_locale_section_writes_empty_file(self):
        xml = "<en-US>\n</en-US>\n<it-IT>\n</it-IT>\n"
        xml_path = _write_xml(self.tmpdir, xml)
        out = self._output_dir()
        mod.extract_notes(xml_path, out)

        en = Path(out, "whatsnew-en-US").read_text()
        self.assertEqual(en, "")

    def test_custom_output_dir(self):
        xml_path = _write_xml(self.tmpdir)
        custom = os.path.join(self.tmpdir, "custom", "nested", "dir")
        exit_code = mod.extract_notes(xml_path, custom)

        self.assertEqual(exit_code, 0)
        self.assertTrue(Path(custom, "whatsnew-en-US").exists())
        self.assertTrue(Path(custom, "whatsnew-it-IT").exists())

    def test_no_locale_sections_returns_1(self):
        xml = "just some text without locale tags"
        xml_path = _write_xml(self.tmpdir, xml)
        exit_code = mod.extract_notes(xml_path, self._output_dir())
        self.assertEqual(exit_code, 1)


class TestMainCLI(unittest.TestCase):
    """Tests for the CLI entry point."""

    def setUp(self):
        self.tmpdir = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.tmpdir)

    def test_main_with_defaults(self):
        xml_path = _write_xml(self.tmpdir)
        out = os.path.join(self.tmpdir, "out")
        exit_code = mod.main(["--xml-path", xml_path, "--output-dir", out])
        self.assertEqual(exit_code, 0)

    def test_main_missing_xml_returns_1(self):
        exit_code = mod.main(
            ["--xml-path", "/nonexistent.xml", "--output-dir", self.tmpdir]
        )
        self.assertEqual(exit_code, 1)


if __name__ == "__main__":
    unittest.main()
