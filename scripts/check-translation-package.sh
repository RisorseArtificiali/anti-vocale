#!/usr/bin/env bash
# Check that docs/i18n/translation-package.csv is fresh (TASK-950).
# Run from anywhere; exits 1 listing the drift.
#
# The CSV is the hand-off package for volunteer translators, derived from
# values/strings.xml by scripts/extract-translation-package.py. When a
# string key is added, edited or removed, the package drifted silently
# until someone remembered to regenerate it (it last happened on TASK-918
# only because the author knew the flow). This check regenerates the CSV
# through the generator's OWN write path into a temp file and compares
# BYTES with the committed one, so it can never diverge from what a real
# regeneration produces.
#
# Byte-stability contract (the 2222-line CRLF incident): the generator
# writes with newline="" so csv.writer's CRLF terminators survive, the CSV
# carries no timestamps, and row order is strings.xml document order. A
# read_text/write_text rewrite of the file normalizes CRLF to LF; that
# case is detected and named explicitly instead of drowning in a full
# every-line diff. The sibling plurals-<tag>.md files are deliberately
# NOT compared: they embed date.today(), so a byte comparison would be
# pure daily noise; plural-key drift is still caught here because the CSV
# carries one row per plural quantity.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"

python3 - "$REPO_DIR" <<'PYEOF'
import difflib
import importlib.util
import pathlib
import sys
import tempfile

repo = pathlib.Path(sys.argv[1])
committed_path = repo / 'docs/i18n/translation-package.csv'


def fail(msg, header='translation package drift:'):
    print(header)
    print(' -', msg)
    sys.exit(1)


REGENERATE = 'python3 scripts/extract-translation-package.py zh'


if not committed_path.exists():
    fail('docs/i18n/translation-package.csv is missing; generate it with '
         + REGENERATE, header='translation package incomplete:')

spec = importlib.util.spec_from_file_location(
    'extract_translation_package',
    repo / 'scripts/extract-translation-package.py')
gen = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gen)

rows, plurals, _warnings = gen.extract()
with tempfile.TemporaryDirectory() as td:
    fresh_path = gen.write_package_csv(rows, plurals, pathlib.Path(td))
    fresh = fresh_path.read_bytes()
committed = committed_path.read_bytes()

if fresh == committed:
    plural_rows = sum(len(items) for _, items in plurals)
    print(f'translation package OK: {len(rows)} string rows and '
          f'{plural_rows} plural rows match strings.xml byte for byte')
    sys.exit(0)

if fresh.replace(b'\r\n', b'\n') == committed.replace(b'\r\n', b'\n'):
    fail('only the line endings differ: the committed CSV is LF-normalized '
         'but the generator emits CRLF. Regenerate with ' + REGENERATE + '; never rewrite '
         'the file with read_text/write_text (2222-line CRLF incident)')

try:
    old = committed.decode('utf-8').splitlines()
except UnicodeDecodeError:
    fail('docs/i18n/translation-package.csv is not valid UTF-8; regenerate '
         'it with ' + REGENERATE)
new = fresh.decode('utf-8').splitlines()
diff = list(difflib.unified_diff(old, new, fromfile='committed',
                                 tofile='regenerated', lineterm=''))
print('translation package drift: strings.xml changed without regenerating '
      'docs/i18n/translation-package.csv (the volunteer hand-off).')
print('Regenerate with: ' + REGENERATE + ' (the CSV does not depend on the tag)')
print(f'{len(old) - 1} committed rows vs {len(new) - 1} regenerated rows '
      f'(plus the header); diff summary (first 20 lines):')
for line in diff[:20]:
    print(' ', line)
if len(diff) > 20:
    print(f'  ... {len(diff) - 20} more diff lines')
sys.exit(1)
PYEOF
