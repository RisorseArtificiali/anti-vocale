#!/usr/bin/env bash
# Check that docs/i18n/translation-package.csv and every plurals-<tag>.md
# are fresh (TASK-950; plurals extended in TASK-959).
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
# every-line diff.
#
# The plurals-<tag>.md files (one per CLDR_CARDINALS tag) are held to the
# same byte contract since TASK-959: their headers are date-free (string
# and plural-key counts only), so a fresh regeneration is byte-identical
# until strings.xml actually changes. A regeneration for ONE locale
# rewrites only that locale's file, so the check verifies ALL tags, fails
# when a tag's file is missing entirely, and fails on a committed md whose
# tag the generator's table no longer lists (a dropped locale leaves an
# orphan that would otherwise pass forever). Every failure class is
# collected first and reported in one pass, so a mixed-drift run names
# everything instead of one class per CI round.
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


def regen(tag='zh'):
    return 'python3 scripts/extract-translation-package.py ' + tag


spec = importlib.util.spec_from_file_location(
    'extract_translation_package',
    repo / 'scripts/extract-translation-package.py')
gen = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gen)

# Collect every failure class first, then report them all in one pass, so
# a mixed-drift run (missing CSV plus a stale plurals md, say) names
# everything instead of making the contributor fix one class per CI round.
rows, plurals, _warnings = gen.extract()
csv_missing = not committed_path.exists()
md_missing = []
md_drift = []
md_orphan = []
with tempfile.TemporaryDirectory() as td:
    fresh = gen.write_package_csv(rows, plurals, pathlib.Path(td)).read_bytes()
    for tag in sorted(gen.CLDR_CARDINALS):
        fresh_md = gen.write_plurals_md(tag, rows, plurals, pathlib.Path(td))
        committed_md = repo / 'docs/i18n' / fresh_md.name
        if not committed_md.exists():
            md_missing.append(tag)
        elif fresh_md.read_bytes() != committed_md.read_bytes():
            md_drift.append(tag)
    for committed_md in (repo / 'docs/i18n').glob('plurals-*.md'):
        tag = committed_md.name[len('plurals-'):-len('.md')]
        if tag not in gen.CLDR_CARDINALS:
            md_orphan.append(tag)
committed = None if csv_missing else committed_path.read_bytes()
csv_drift = committed is not None and fresh != committed

if not csv_missing and not csv_drift and not md_missing and not md_drift \
        and not md_orphan:
    plural_rows = sum(len(items) for _, items in plurals)
    print(f'translation package OK: {len(rows)} string rows and '
          f'{plural_rows} plural rows match strings.xml byte for byte')
    print(f'plurals OK: {len(gen.CLDR_CARDINALS)} locale files '
          f'({", ".join(sorted(gen.CLDR_CARDINALS))}) match byte for byte')
    sys.exit(0)

if csv_missing or md_missing:
    print('translation package incomplete:')
    if csv_missing:
        print(' - docs/i18n/translation-package.csv is missing; generate '
              'it with ' + regen())
    for tag in md_missing:
        print(f' - docs/i18n/plurals-{tag}.md is missing; regenerate it '
              'with ' + regen(tag))
if md_drift:
    print('plural rule drift: these files do not match a fresh '
          'regeneration:')
    for tag in md_drift:
        print(f' - plurals-{tag}.md: regenerate with ' + regen(tag))
if md_orphan:
    print('plural rule drift: the generator no longer lists these tags; '
          'delete the orphaned files:')
    for tag in md_orphan:
        print(f' - docs/i18n/plurals-{tag}.md')

if csv_drift:
    if fresh.replace(b'\r\n', b'\n') == committed.replace(b'\r\n', b'\n'):
        print('translation package drift:')
        print(' - only the line endings differ: the committed CSV is '
              'LF-normalized but the generator emits CRLF. Regenerate with '
              + regen() + '; never rewrite the file with read_text/write_text '
              '(2222-line CRLF incident)')
        sys.exit(1)
    try:
        old = committed.decode('utf-8').splitlines()
    except UnicodeDecodeError:
        print('translation package drift:')
        print(' - docs/i18n/translation-package.csv is not valid UTF-8; '
              'regenerate it with ' + regen())
        sys.exit(1)
    new = fresh.decode('utf-8').splitlines()
    diff = list(difflib.unified_diff(old, new, fromfile='committed',
                                     tofile='regenerated', lineterm=''))
    print('translation package drift: strings.xml changed without '
          'regenerating docs/i18n/translation-package.csv (the volunteer '
          'hand-off).')
    print('Regenerate with: ' + regen() + ' (the CSV does not depend on the tag)')
    print(f'{len(old) - 1} committed rows vs {len(new) - 1} regenerated rows '
          f'(plus the header); diff summary (first 20 lines):')
    for line in diff[:20]:
        print(' ', line)
    if len(diff) > 20:
        print(f'  ... {len(diff) - 20} more diff lines')
sys.exit(1)
PYEOF
