"""TASK-950 failure-branch pins: the freshness check's three failure arms
(corrupt UTF-8, LF-normalized-only drift, content drift) and the
missing-file arm are exercised against a scratch repo so a future refactor
of the comparison cannot silently break them (the LF arm exists because of
the real 2222-line CRLF incident).

The green path is pinned by the script's own nightly run; these tests pin
the RED paths.
"""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
CHECK = REPO / 'scripts' / 'check-translation-package.sh'
CSV = REPO / 'docs/i18n' / 'translation-package.csv'


class CheckTranslationPackageFailureBranchesTest(unittest.TestCase):
    def setUp(self):
        self.scratch = Path(tempfile.mkdtemp())
        docs = self.scratch / 'docs' / 'i18n'
        docs.mkdir(parents=True)
        shutil.copy(CSV, docs / 'translation-package.csv')
        # The check imports the generator and reads strings.xml from the
        # repo it is given, so the scratch tree needs those too.
        shutil.copytree(REPO / 'scripts', self.scratch / 'scripts')
        shutil.copytree(REPO / 'app/src/main/res/values',
                        self.scratch / 'app/src/main/res/values')

    def tearDown(self):
        shutil.rmtree(self.scratch, ignore_errors=True)

    def run_check(self):
        # The script derives REPO_DIR from its own location, so the scratch
        # copy (setUp copied scripts/ into it) checks the scratch tree.
        return subprocess.run(
            ['bash', str(self.scratch / 'scripts' / 'check-translation-package.sh')],
            capture_output=True, text=True)

    def test_green_on_a_faithful_copy(self):
        result = self.run_check()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_missing_csv_fails_with_the_incomplete_header(self):
        (self.scratch / 'docs/i18n/translation-package.csv').unlink()
        result = self.run_check()
        self.assertEqual(1, result.returncode)
        self.assertIn('translation package incomplete:', result.stdout)
        self.assertIn('missing', result.stdout)

    def test_lf_normalized_csv_gets_the_dedicated_message_not_a_diff(self):
        csv = self.scratch / 'docs/i18n/translation-package.csv'
        csv.write_bytes(csv.read_bytes().replace(b'\r\n', b'\n'))
        result = self.run_check()
        self.assertEqual(1, result.returncode)
        self.assertIn('only the line endings differ', result.stdout)
        self.assertNotIn('-no_translate', result.stdout)

    def test_invalid_utf8_fails_with_the_regenerate_message(self):
        csv = self.scratch / 'docs/i18n/translation-package.csv'
        csv.write_bytes(csv.read_bytes() + b'\xff\xfe broken')
        result = self.run_check()
        self.assertEqual(1, result.returncode)
        self.assertIn('not valid UTF-8', result.stdout)

    def test_content_drift_reports_the_changed_row(self):
        csv = self.scratch / 'docs/i18n/translation-package.csv'
        # newline='' keeps the CRLF bytes intact through the round trip.
        with open(csv, newline='') as fh:
            text = fh.read()
        self.assertIn('model_tab', text)
        corrupted = text.replace('model_tab', 'model_tabz', 1)
        csv.write_bytes(corrupted.encode('utf-8'))
        result = self.run_check()
        self.assertEqual(1, result.returncode)
        self.assertIn('translation package drift:', result.stdout)
        self.assertIn('Regenerate with:', result.stdout)


if __name__ == '__main__':
    unittest.main()
