#!/usr/bin/env bash
# Check that user-facing model size claims match the catalog (TASK-923).
# Run from anywhere; exits 1 listing every drift.
#
# TASK-942 split the app strings: the size/count prefix is a format argument
# fed from models_catalog.json at render time, so the app-string half of this
# check verifies the FORMAT CONTRACT (the %1$d size slot and %2$d count slot
# survive in every locale, bound to a unit token) instead of baked numbers;
# the format round-trip is pinned by ModelSizePlaceholderGuardTest. Store
# listings, README and docs tables still carry literal copies, and those are
# still re-derived from the catalog here. History: the fastlane listings said
# 464MB for a 640MB model, and the app strings kept pre-re-export Whisper
# sizes after TASK-938/940 repinned the catalog.
#
# Covered surfaces:
#   1. app/src/main/res/values*/strings.xml (format contract for the prefixed
#      descriptions AND the slot-bearing titles (whistle_name's size,
#      qwen3_asr_title's count), plus the non-catalog slotted whistle
#      best-for claim; the untranslatable speed_comparison_size_format)
#   2. fastlane/metadata/android/*/full_description.txt (Parakeet sizes and
#      the storage ceiling)
#   3. README.md "Supported Models" tables (ASR + LLM) and the prerequisites
#      line
#   4. docs/model-catalog.md bundled-model tables
#   5. docs/user-guide/*/ manual tables (whisper rows, localized unit
#      suffixes)
#   6. docs/play-store/store-listing.md storage ceilings
#   7. TASK-948 plural safety of the count slot: the baked count noun in the
#      ru/pl/uk locales (e.g. "%2$d языков") is the genitive plural, correct
#      only for counts whose CLDR cardinal form IS that plural (residue 0 or
#      5-9, or 11-14). A catalog edit that moves any countPlaceholder-bearing
#      languages array into 1-4, 21-24, 31-34, ... (the one/few bands) would
#      ship a grammatical error in every affected locale, so the guard fails
#      with a plurals-audit instruction before release.
# Whitelisted by design (no catalog counterpart): speaker_labels_description
# (the diarization bundle is not a catalog entry) and the historical Italian
# benchmark table in README (it cites its own run's sizes).
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"

python3 - "$REPO_DIR" <<'PYEOF'
import json, pathlib, re, sys

repo = pathlib.Path(sys.argv[1])
catalog = json.loads((repo / 'app/src/main/assets/models_catalog.json').read_text())

def size_of(entry_id, variant):
    for m in catalog['models']:
        if m['id'] == entry_id:
            for v in m.get('variants', []):
                if v['name'] == variant:
                    return v['estimatedSizeMB']
    raise SystemExit(f'catalog has no {entry_id}/{variant}')

# TASK-942: the size/count prefix is no longer baked into locale strings; the
# number arrives as a format argument fed from the catalog at render time. The
# prefixed target list is DERIVED from the catalog walk (description flags
# sizePlaceholder/countPlaceholder; the app parser normalizes the same two
# flags into one ordered slot list, TASK-946), so a new prefixed entry is
# covered the moment the catalog declares it; the guard verifies per locale:
#   - one positional specifier per declared slot survives, in the slot list's
#     order (size first = %1$d, count second = %2$d);
#   - no specifier beyond the declared slots survives;
#   - no OTHER digit run remains after the slots are stripped, except the
#     allowlisted claim literals below (a stray digit is a stale baked copy,
#     the drift class this file exists to catch). Unit-token vocabulary is
#     deliberately NOT checked here: the JVM round-trip test
#     (ModelSizePlaceholderGuardTest) feeds real catalog values, and a token
#     list rots with every new locale.
PREFIXED = {}
for m in catalog['models']:
    for holder in [m] + m.get('variants', []):
        # Titles live in the entry's `display` and a variant's `title`;
        # descriptions in `description` on both holders (TASK-947 added the
        # whistle size title, the first slotted title besides qwen3's).
        for dk in ('description', 'display', 'title'):
            disp = holder.get(dk, {})
            key = disp.get('resourceKey') if isinstance(disp, dict) else None
            if key and isinstance(key, str):
                # The same canonical order the app parser derives (size, count).
                slots = [s for s, flag in (('size', 'sizePlaceholder'),
                                           ('count', 'countPlaceholder')) if disp.get(flag)]
                if slots:
                    PREFIXED[key] = slots
# Non-catalog slotted claims (no catalog display declares them): the whistle
# model-info best-for line renders the variant's catalog size and language
# count through ModelInfo.bestForPlaceholders at the overlay (TASK-947).
PREFIXED['model_info_best_for_whistle'] = ['size', 'count']
PREFIXED['model_info_best_for_qwen3'] = ['count']
# claim-text digits that are NOT catalog data (model names, layer counts)
DIGIT_ALLOWLIST = {
    'whisper_turbo_description': ['3'],        # "large-v3"
    'whisper_distil_large_v3_description': ['2'],  # "2 decoder layers"
    'qwen3_asr_title': ['3'],                  # "Qwen3-ASR"
}

string_re = re.compile(r'<string name="([^"]+)"[^>]*>([^<]*)</string>')

errors = []

for p in sorted((repo / 'app/src/main/res').glob('values*/strings.xml')):
    bodies = {m.group(1): m.group(2) for m in string_re.finditer(p.read_text())}
    for name, slots in PREFIXED.items():
        body = bodies.get(name)
        if body is None:
            errors.append(f'{p.relative_to(repo)}: {name} missing')
            continue
        stripped = body
        for i in range(len(slots) + 1):
            stripped = stripped.replace(f'%{i + 1}$d', '')
        for i, slot in enumerate(slots):
            if f'%{i + 1}$d' not in body:
                errors.append(f'{p.relative_to(repo)}: {name} lost the %{i + 1}$d {slot} specifier')
        stray = f'%{len(slots) + 1}$d'
        if stray in body:
            errors.append(f'{p.relative_to(repo)}: {name} carries a stray {stray} the catalog '
                          f'does not declare (renders as a literal "{stray}")')
        allowed = DIGIT_ALLOWLIST.get(name, [])
        # ASCII digits only: \d would match a locale's native-digit rendering
        # of an allowlisted literal (fa/hi) and fail a correct translation.
        for run in re.findall(r'[0-9]+', stripped):
            if run not in allowed:
                errors.append(f'{p.relative_to(repo)}: {name} carries a baked digit {run!r}; '
                              f'numbers must come from the catalog slots')
    # Speed-comparison copies must not exist in locales (one pass with the
    # bodies dict instead of a second file walk).
    if p.parent.name != 'values' and any(
            re.search(r'speed_comparison_(?:turbo|medium|small|distil_it|parakeet|qwen3|gigaam|nemotron)_size', name)
            for name in bodies):
        errors.append(f'{p.relative_to(repo)}: speed-comparison size copies must not exist in locales')

# The speed-comparison table: one untranslatable format in the default file,
# composed per row at render time.
values_xml = (repo / 'app/src/main/res/values/strings.xml').read_text()
m = re.search(r'<string name="speed_comparison_size_format" translatable="false">([^<]*)</string>', values_xml)
if not m:
    errors.append('values/strings.xml: speed_comparison_size_format missing or not translatable="false"')
elif m.group(1) != '~%1$dMB':
    errors.append(f'values/strings.xml: speed_comparison_size_format is {m.group(1)!r}, expected "~%1$dMB"')

# Store listings: the Parakeet line names both variants' sizes.
stock = size_of('sherpa-onnx', 'stock-int8')
smooth = size_of('sherpa-onnx', 'smoothquant')
for p in sorted((repo / 'fastlane/metadata/android').glob('*/full_description.txt')):
    t = p.read_text()
    line = next((l for l in t.splitlines() if 'arakeet' in l), None)
    if line is None:
        continue
    for want in (stock, smooth):
        if f'{want}MB' not in line:
            errors.append(f'{p.relative_to(repo)}: Parakeet line missing {want}MB')

# README "Supported Models" ASR tables (the historical benchmark table cites
# its own run's sizes and is intentionally out of scope).
readme_rows = {
    'Whisper Small': ('whisper', 'small'),
    'Whisper Turbo': ('whisper', 'turbo'),
    'Whisper Medium': ('whisper', 'medium'),
    'Distil Italian': ('whisper', 'distil-large-v3-it'),
    'Parakeet TDT (stock int8)': ('sherpa-onnx', 'stock-int8'),
    'Parakeet TDT (SmoothQuant)': ('sherpa-onnx', 'smoothquant'),
    'GigaAM v3': ('gigaam', 'v3'),
    'Qwen3-ASR 0.6B': ('qwen3-asr', '0.6b-int8'),
}
readme = (repo / 'README.md').read_text()
for label, (e, v) in readme_rows.items():
    m = re.search(r'^\| \*\*' + re.escape(label) + r'\*\* \| ~?(\d+)MB', readme, re.M)
    if not m:
        errors.append(f'README.md: model-table row for {label} not found')
        continue
    if int(m.group(1)) != size_of(e, v):
        errors.append(f'README.md: {label} says {m.group(1)}MB, catalog says {size_of(e, v)}MB')

# README LLM table (GB rows) and the prerequisites ceiling.
llm_rows = {'Gemma 4 E2B': ('llm', 'gemma-4-e2b'), 'Gemma 4 E4B': ('llm', 'gemma-4-e4b'),
            'Gemma 3n E2B': ('llm', 'gemma-3n-e2b'), 'Gemma 3n E4B': ('llm', 'gemma-3n-e4b')}
for label, (e, v) in llm_rows.items():
    m = re.search(r'^\| \*\*' + re.escape(label) + r'\*\* \| ~?([\d.]+)GB', readme, re.M)
    want = size_of(e, v) / 1000
    if not m:
        errors.append(f'README.md: LLM row for {label} not found')
    elif abs(float(m.group(1)) - want) > 0.05:
        errors.append(f'README.md: {label} says {m.group(1)}GB, catalog says {want:.1f}GB')

# Storage ceilings: a storage-requirement line carries both an MB token and
# a GB token (the RAM recommendation also reads in GB); the LARGEST GB value
# on the line must equal the catalog's largest download. Comparing the max
# sidesteps the RAM token without locale-specific parsing, and a stale-low
# ceiling (4.2 vs 4.7) fails instead of passing under a threshold. The
# advertised floors cite the smallest mainstream model on purpose; the
# experimental 17MB whistle entry is deliberately not a floor.
max_mb = max(v['estimatedSizeMB'] for m in catalog['models'] for v in m.get('variants', []))
max_gb = f'{max_mb / 1000:.1f}'
GB_TOKEN = re.compile(r'(\d+(?:[.,]\d+)?)\s*(?:GB|Go|ГБ|جيجابايت)')
MB_TOKEN = re.compile(r'\d+\s*(?:MB|МБ|Мб|Mt|Mo|ميجابايت)')

def check_ceiling(rel, line):
    toks = [float(tok.replace(',', '.')) for tok in GB_TOKEN.findall(line)]
    if not toks:
        errors.append(f'{rel}: storage line has no GB ceiling')
        return
    if abs(max(toks) - max_mb / 1000) > 0.05:
        errors.append(f'{rel}: storage ceiling {max(toks)}GB, catalog max is {max_gb}GB')

for p in [repo / 'docs/play-store/store-listing.md'] + sorted((repo / 'fastlane/metadata/android').glob('*/full_description.txt')):
    for line in p.read_text().splitlines():
        if MB_TOKEN.search(line) and GB_TOKEN.search(line):
            check_ceiling(p.relative_to(repo), line)
m = re.search(r'up to ([\d.]+)GB for Gemma 3n E4B', readme)
if not m:
    errors.append('README.md: prerequisites ceiling not found')
else:
    check_ceiling('README.md (prerequisites)', f'up to {m.group(1)}GB for Gemma 3n E4B')

# BUILD.md is @import-ed into the project CLAUDE.md; its Gemma rows must
# match the catalog like README's.
build = (repo / 'docs/BUILD.md').read_text()
for label, (e, v) in llm_rows.items():
    m = re.search(r'\*\*' + re.escape(label) + r'\*\* \(([\d.]+)GB\)', build)
    want = size_of(e, v) / 1000
    if not m:
        errors.append(f'docs/BUILD.md: Gemma row for {label} not found')
    elif abs(float(m.group(1)) - want) > 0.05:
        errors.append(f'docs/BUILD.md: {label} says {m.group(1)}GB, catalog says {want:.1f}GB')

# docs/model-catalog.md bundled tables.
mc = (repo / 'docs/model-catalog.md').read_text()
for label, (e, v) in [('turbo', ('whisper', 'turbo')), ('medium', ('whisper', 'medium')),
                      ('small', ('whisper', 'small')), ('distil-large-v3-it', ('whisper', 'distil-large-v3-it'))]:
    m = re.search(r'^\| ' + re.escape(label) + r' \| (\d+) MB', mc, re.M)
    if not m:
        errors.append(f'docs/model-catalog.md: row {label} not found')
    elif int(m.group(1)) != size_of(e, v):
        errors.append(f'docs/model-catalog.md: {label} says {m.group(1)}MB, catalog says {size_of(e, v)}MB')
if f'int8 ({size_of("sherpa-onnx", "stock-int8")} MB), SmoothQuant ({size_of("sherpa-onnx", "smoothquant")} MB)' not in mc:
    errors.append('docs/model-catalog.md: Parakeet variants line does not match the catalog')

# User-guide manuals: whisper rows keep English labels; the size and the
# language-count cells must both match the catalog (the count is whatever
# the catalog declares, not a hand-pinned number).
UG_SIZE = re.compile(r'\|\s*(\d+)\s*(MB|Mo|МБ|Mt|Мб)\b')
ug_rows = [('Turbo', 'turbo'), ('Medium', 'medium'), ('Small', 'small'),
           ('Distil Italian', 'distil-large-v3-it')]
ug_want_size = {r: size_of('whisper', v) for r, v in ug_rows}
ug_want_count = {r: len(next(x for x in catalog['models'] if x['id'] == 'whisper')
                          and next(v for v in next(m for m in catalog['models'] if m['id'] == 'whisper')['variants']
                                   if v['name'] == r_)['languages'])
                 for r, r_ in ug_rows}
for p in sorted((repo / 'docs/user-guide').glob('*/*.md')):
    if p.name == 'README.md':
        continue
    for line in p.read_text().splitlines():
        m = re.match(r'^\|\s*Whisper (Turbo|Medium|Small|Distil Italian)\s*\|', line)
        if not m:
            continue
        label = m.group(1)
        s = UG_SIZE.search(line)
        want = ug_want_size[label]
        if not s:
            errors.append(f'{p.relative_to(repo)}: Whisper {label} row has no size')
        elif int(s.group(1)) != want:
            errors.append(f'{p.relative_to(repo)}: Whisper {label} says {s.group(1)}, catalog says {want}')
        cells = [c.strip() for c in line.split('|')]
        count_cell = cells[3] if len(cells) > 3 else ''
        cm = re.match(r'^(\d+)', count_cell)
        # The Distil row names its language instead of a count; only the
        # three numeric rows carry a checkable count.
        if label != 'Distil Italian':
            if not cm:
                errors.append(f'{p.relative_to(repo)}: Whisper {label} row has no language count')
            elif int(cm.group(1)) != ug_want_count[label]:
                errors.append(f'{p.relative_to(repo)}: Whisper {label} count {cm.group(1)}, catalog says {ug_want_count[label]}')

# Claim pins: the app's model-info WER strings must carry the matrix numbers
# emitted in the generated claim-pins comment of docs/quality-matrix.md (the
# committed artifact; the raw JSON lives outside git). Locale decimal commas
# are allowed; the default locale must carry the number at all. Any number
# in the string may match so a later added figure cannot break the pin.
CLAIM_PINS = {
    'model_info_best_for_distil_it': 'whisper-distil-it-attn',
    'model_info_best_for_parakeet': 'parakeet-stock-int8',
}
pins_line = next((l for l in (repo / 'docs/quality-matrix.md').read_text().splitlines()
                  if '<!-- claim-pins:' in l), '')
pin_vals = dict(re.findall(r'(\S+)=(\d+\.\d+)', pins_line))
if not pin_vals:
    errors.append('docs/quality-matrix.md: generated claim-pins comment missing; '
                  'regenerate with matrix_report.py --docs-page')
for res, key in CLAIM_PINS.items():
    if key not in pin_vals:
        # A partially regenerated comment would silently unpin one claim.
        errors.append(f'docs/quality-matrix.md: claim-pins comment lacks {key}')
        continue
    want = f'{float(pin_vals[key]):.1f}'
    for p in sorted((repo / 'app/src/main/res').glob('values*/strings.xml')):
        text = p.read_text()
        bodies = [m.group(2) for m in string_re.finditer(text)
                  if f'name="{res}"' in m.group(0)]
        if not bodies:
            if p.parent.name == 'values':
                errors.append(f'{p.relative_to(repo)}: {res} missing')
            continue
        nums = re.findall(r'(\d+[.,]\d+)\s*%%', bodies[0])
        if not nums:
            if p.parent.name == 'values':
                errors.append(f'{p.relative_to(repo)}: {res} carries no WER number')
            continue
        if not any(abs(float(n.replace(',', '.')) - float(want)) <= 0.05 for n in nums):
            errors.append(f'{p.relative_to(repo)}: {res} numbers {nums} do not include the '
                          f'matrix value {want}%')

# TASK-948: the count slot's baked noun is frozen as the genitive plural
# ("языков" family) in ru/pl/uk. That form is correct only where the CLDR
# cardinal rules pick it: residue 0 or 5-9, or 11-14. Counts in the one/few
# bands (1-4, 21-24, 31-34, ...) need a different noun, and converting the
# phrase to Android plurals resources across 17 locales is the TASK-948
# audit, not a mechanical edit. Fail here so no release ships with a wrong
# form; a count below 5 fails outright for the same reason. Scope note:
# this band rule covers ru/pl/uk only. ar (and hi, weakly) bake
# count-sensitive nouns whose form depends on the per-string baked word,
# so ANY change to a counted language count needs an ar/hi copy audit too.
def count_slot_unsafe(n):
    # One/few bands for n >= 5 (the caller owns the n < 5 outright case);
    # keep in sync with the JVM pin in ModelSizePlaceholderGuardTest.
    return (n % 10 == 1 and n % 100 != 11) \
        or (2 <= n % 10 <= 4 and n % 100 not in (12, 13, 14))

for m in catalog['models']:
    holders = [m] + m.get('variants', [])
    for holder in holders:
        count_declared = any(
            isinstance(holder.get(dk), dict) and holder[dk].get('countPlaceholder')
            for dk in ('description', 'display', 'title'))
        if not count_declared:
            continue
        languages = holder.get('languages') or m.get('languages') or []
        n = len(languages)
        where = f"{m['id']}" + (f"/{holder['name']}" if holder is not m else '')
        if n == 0:
            errors.append(f'{where}: a count slot with NO languages declared '
                          f'(variant languages empty and no entry-level list): '
                          f'this is a catalog bug, not a plurals question')
        elif n < 5:
            errors.append(f'{where}: count slot renders {n} languages; counts below 5 '
                          f'take a non-plural noun form in ru/pl/uk: run the TASK-948 '
                          f'plurals audit (plurals resources or a native-reviewed copy) '
                          f'before any release')
        elif count_slot_unsafe(n):
            errors.append(f'{where}: count slot renders {n} languages, a one/few band '
                          f'where ru/pl/uk do not use the baked genitive plural noun: '
                          f'run the TASK-948 plurals audit before any release')

if errors:
    print('model size drift:')
    for e in errors:
        print(' -', e)
    sys.exit(1)
print(f'model sizes OK: {len(PREFIXED)} format-contract descriptions and titles '
      f'in every locale, speed size_format, listings, guides and docs match the catalog')
PYEOF

# TASK-949 call-site contract: a catalog title or description resource may
# carry format slots, so a bare getString on its resId renders a literal %1$d
# the moment a catalog flag turns on. The pattern keys on any identifier
# ending in TitleResId/DescriptionResId (renamed locals stay covered), and
# the comment filter keeps this guard's own KDoc mentions out.
if grep -rnE 'getString\(([a-zA-Z]+\.)?[a-zA-Z]*(Title|Description)ResId\)' app/src/main/java \
    --include='*.kt' \
  | grep -vE '[/*] .*getString'; then
  echo 'call-site drift: bare getString on a catalog title/description resId (a slot-bearing string renders a literal %1$d).'
  echo 'Route it through placeholderAwareString/variantTitle (transcription/CatalogPlaceholders.kt).'
  exit 1
fi
