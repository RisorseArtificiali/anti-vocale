"""Tests for scripts/aab_locale_check.py (TASK-976), the pure half of the
AAB locale gate. The gate exists because Play's translation validation
rejects bundles carrying untranslatable LIBRARY locales (v1.16.0's "te").
"""

import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from aab_locale_check import (  # noqa: E402
    aab_locales,
    allowed_locales,
    normalize_locale,
    res_locales,
    unexpected_locales,
)


class ResLocalesTest(unittest.TestCase):
    def test_values_folders_become_locale_list(self):
        with TemporaryDirectory() as tmp:
            for name in ("values", "values-it", "values-pt-rBR", "values-iw",
                         "values-b+sr+Latn"):
                (Path(tmp) / name).mkdir()
            # Non-locale values-* folders must be skipped: they are not
            # languages and must never feed a filter or a comparison.
            (Path(tmp) / "values-night").mkdir()
            (Path(tmp) / "values-v26").mkdir()
            (Path(tmp) / "not-a-dir.txt").write_text("x")
            self.assertEqual(res_locales(tmp), ["b+sr+Latn", "it", "iw", "pt-rBR"])
            # The b+ BCP-47 notation normalizes to dash form (code-review
            # round 2026-11-10: a future locale folder must not become a
            # phantom offender).
            self.assertEqual(normalize_locale("b+sr+Latn"), "sr-Latn")

    def test_normalize_maps_legacy_and_region_notations(self):
        self.assertEqual(normalize_locale("pt-rBR"), "pt-BR")
        self.assertEqual(normalize_locale("iw"), "he")
        self.assertEqual(normalize_locale("in"), "id")
        self.assertEqual(normalize_locale("de"), "de")

    def test_allowed_adds_english_default(self):
        with TemporaryDirectory() as tmp:
            (Path(tmp) / "values-fr").mkdir()
            self.assertEqual(allowed_locales(tmp), {"fr", "en"})


class AabLocaleGateTest(unittest.TestCase):
    RES = Path(__file__).resolve().parent.parent / ".." / "app/src/main/res"

    def test_dump_parsing(self):
        dump = 'x\n  locale: "de"\n  locale: "pt-BR"\n'
        self.assertEqual(aab_locales(dump), {"de", "pt-BR"})

    def test_the_v1_16_0_incident_shape_fails(self):
        # Telugu from a library, everything else ours: the exact shape that
        # killed the first v1.16.0 publish.
        dump = 'locale: "de"\nlocale: "te"\nlocale: "it"\n'
        self.assertEqual(unexpected_locales(dump, self.RES), ["te"])

    def test_parent_language_leakage_is_fine(self):
        # The app ships pt-rBR only; generic AndroidX values-pt strings ride
        # along and pt is a language Play can translate: not an offender.
        dump = 'locale: "pt"\nlocale: "pt-BR"\n'
        self.assertEqual(unexpected_locales(dump, self.RES), [])

    def test_numeric_region_b_plus_folder_and_es_419_dump(self):
        # Code-review round 2026-11-10: values-b+es+419 (numeric UN M.49
        # region) must count as an app locale, and a dumped es-419 must not
        # be a phantom offender.
        with TemporaryDirectory() as tmp:
            (Path(tmp) / "values-b+es+419").mkdir()
            self.assertIn("b+es+419", res_locales(tmp))
            dump = 'locale: "es-419"\nlocale: "en"'
            self.assertEqual(unexpected_locales(dump, tmp), [])

    def test_legacy_language_with_region_is_mapped(self):
        # Older bundletool dumps legacy codes with a region (iw-IL, in-ID);
        # the app ships values-in: the gate must not flag id-ID.
        with TemporaryDirectory() as tmp:
            (Path(tmp) / "values-in").mkdir()
            dump = 'locale: "in-ID"'
            self.assertEqual(unexpected_locales(dump, tmp), [])

    def test_our_own_full_set_passes(self):
        dump = "\n".join(f'  locale: "{loc}"' for loc in
                         ("ar", "de", "es", "fa", "fi", "fr", "he", "hi", "it",
                          "iw", "nl", "pl", "pt", "pt-BR", "ru", "tr", "uk", "zh"))
        self.assertEqual(unexpected_locales(dump, self.RES), [])


if __name__ == "__main__":
    unittest.main()
