"""Unit tests for scripts/extract-translation-package.py (the sibling
extract-release-notes.py pattern: importlib load, tempfile fixture)."""
import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent.parent / "extract-translation-package.py"


def load_module(tmp_values: Path):
    src = SCRIPT.read_text()
    # point the module's VALUES at the fixture without touching the script
    src = src.replace(
        'VALUES = REPO / "app/src/main/res/values/strings.xml"',
        f'VALUES = Path("{tmp_values}")')
    with tempfile.NamedTemporaryFile("w", suffix=".py", delete=False) as f:
        f.write(src)
        mod_path = f.name
    spec = importlib.util.spec_from_file_location("etp_test", mod_path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


FIXTURE = """<resources>
    <string name="app_name">Anti-Vocale</string>
    <string name="plain_one">Hello</string>
    <string name="fmt_positional">%1$d of %2$d done</string>
    <string name="fmt_plain">%d selected</string>
    <string name="fmt_float">Rate: %.1f%%</string>
    <string name="no_translate" translatable="false">DO_NOT_TRANSLATE</string>
    <plurals name="cats">
        <item quantity="one">%d cat</item>
        <item quantity="other">%d cats</item>
    </plurals>
</resources>
"""


class ExtractTranslationPackageTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.NamedTemporaryFile("w", suffix=".xml", delete=False)
        self.tmp.write(FIXTURE)
        self.tmp.close()
        self.mod = load_module(Path(self.tmp.name))

    def test_format_classification_covers_non_positional(self):
        rows = {r[0]: r for r in self.mod.extract()[0]}
        self.assertEqual("plain", rows["plain_one"][2])
        self.assertEqual("formatted", rows["fmt_positional"][2])
        # TASK-771 review F1: bare %d and %.1f must be 'formatted' too
        self.assertEqual("formatted", rows["fmt_plain"][2])
        self.assertEqual("formatted", rows["fmt_float"][2])
        self.assertIn("%d", rows["fmt_plain"][3])
        self.assertIn("%.1f", rows["fmt_float"][3])
        self.assertIn("%%", rows["fmt_float"][3])

    def test_translatable_false_skipped_and_plurals_collected(self):
        rows, plurals, _ = self.mod.extract()
        names = [r[0] for r in rows]
        self.assertNotIn("no_translate", names)
        self.assertEqual(["cats"], [n for n, _ in plurals])
        self.assertEqual({"one", "other"}, plurals[0][1].keys())

    def test_unknown_tag_exits_2_before_anything(self):
        with self.assertRaises(SystemExit) as ctx:
            self.mod.main("de")
        self.assertEqual(2, ctx.exception.code)

    def test_cldr_tables_cover_the_three_targets(self):
        self.assertEqual(["other"], self.mod.CLDR_CARDINALS["zh"])
        self.assertEqual(["one", "other"], self.mod.CLDR_CARDINALS["fi"])
        self.assertEqual(6, len(self.mod.CLDR_CARDINALS["ar"]))


if __name__ == "__main__":
    unittest.main()
