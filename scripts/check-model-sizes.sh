#!/usr/bin/env bash
# Check that user-facing model size claims match the catalog (TASK-923).
# Run from anywhere; exits 1 listing every drift.
#
# The download size a user reads (app strings, store listings, README model
# tables) is a copy of models_catalog.json estimatedSizeMB. Two classes have
# shipped stale: the fastlane listings said 464MB for a 640MB model, and the
# app strings kept pre-re-export Whisper sizes after TASK-938/940 repinned the
# catalog. This check re-derives every copy from the catalog so the next one
# is caught instead of published.
#
# Covered surfaces:
#   1. app/src/main/res/values*/strings.xml (every locale that carries a
#      mapped resource)
#   2. fastlane/metadata/android/*/full_description.txt (Parakeet sizes and
#      the storage ceiling)
#   3. README.md "Supported Models" tables (ASR + LLM) and the prerequisites
#      line
#   4. docs/model-catalog.md bundled-model tables
#   5. docs/user-guide/*/ manual tables (whisper rows, localized unit
#      suffixes)
#   6. docs/play-store/store-listing.md storage ceilings
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

# resource key -> (entry id, variant name)
MAPPED = {
    'parakeet_stock_description': ('sherpa-onnx', 'stock-int8'),
    'parakeet_smoothquant_description': ('sherpa-onnx', 'smoothquant'),
    'speed_comparison_parakeet_size': ('sherpa-onnx', 'stock-int8'),
    'whisper_small_description': ('whisper', 'small'),
    'whisper_turbo_description': ('whisper', 'turbo'),
    'whisper_medium_description': ('whisper', 'medium'),
    'whisper_distil_large_v3_description': ('whisper', 'distil-large-v3-it'),
    'speed_comparison_small_size': ('whisper', 'small'),
    'speed_comparison_turbo_size': ('whisper', 'turbo'),
    'speed_comparison_medium_size': ('whisper', 'medium'),
    'speed_comparison_distil_it_size': ('whisper', 'distil-large-v3-it'),
    'qwen3_asr_0_6b_description': ('qwen3-asr', '0.6b-int8'),
    'speed_comparison_qwen3_size': ('qwen3-asr', '0.6b-int8'),
    'nemotron_description': ('nemotron-streaming', '1120ms-int8'),
    'speed_comparison_nemotron_size': ('nemotron-streaming', '1120ms-int8'),
    'gigaam_description': ('gigaam', 'v3'),
    'speed_comparison_gigaam_size': ('gigaam', 'v3'),
    'whistle_description': ('whistle', 'v1'),
    'model_info_best_for_whistle': ('whistle', 'v1'),
}
expected = {k: size_of(e, v) for k, (e, v) in MAPPED.items()}
MAPPED['whistle_name'] = ('whistle', 'v1')
expected['whistle_name'] = size_of('whistle', 'v1')

# Localized megabyte suffixes seen in the tree: en/it MB, uk МБ, fi Mt, fr Mo.
SIZE_RE = re.compile(r'(\d+)\s*(?:MB|МБ|Мб|Mt|Mo)\b')
string_re = re.compile(r'<string name="([^"]+)"[^>]*>([^<]*)</string>')

errors = []

for p in sorted((repo / 'app/src/main/res').glob('values*/strings.xml')):
    for m in string_re.finditer(p.read_text()):
        name, body = m.group(1), m.group(2)
        if name not in expected:
            continue
        toks = SIZE_RE.findall(body)
        if not toks:
            # A translation may legitimately drop the size; only the default
            # locale is required to carry it.
            if p.parent.name == 'values':
                errors.append(f'{p.relative_to(repo)}: {name} carries no size token')
            continue
        want = str(expected[name])
        if toks[0] != want:
            errors.append(f'{p.relative_to(repo)}: {name} says {toks[0]}MB, catalog says {want}MB')

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

# User-guide manuals: whisper rows keep English labels; the language count
# cell must not carry the stale 101.
UG_SIZE = re.compile(r'\|\s*(\d+)\s*(MB|Mo|МБ|Mt|Мб)\b')
ug_want = {'Turbo': size_of('whisper', 'turbo'), 'Medium': size_of('whisper', 'medium'),
           'Small': size_of('whisper', 'small'), 'Distil Italian': size_of('whisper', 'distil-large-v3-it')}
for p in sorted((repo / 'docs/user-guide').glob('*/*.md')):
    if p.name == 'README.md':
        continue
    for line in p.read_text().splitlines():
        m = re.match(r'^\|\s*Whisper (Turbo|Medium|Small|Distil Italian)\s*\|', line)
        if not m:
            continue
        s = UG_SIZE.search(line)
        want = ug_want[m.group(1)]
        if not s:
            errors.append(f'{p.relative_to(repo)}: Whisper {m.group(1)} row has no size')
        elif int(s.group(1)) != want:
            errors.append(f'{p.relative_to(repo)}: Whisper {m.group(1)} says {s.group(1)}, catalog says {want}')
        if re.search(r'\b101\b', line):
            errors.append(f'{p.relative_to(repo)}: Whisper {m.group(1)} row still says 101 languages')

if errors:
    print('model size drift:')
    for e in errors:
        print(' -', e)
    sys.exit(1)
print(f'model sizes OK: {len(expected)} mapped string resources, locale copies, listings, guides and docs match the catalog')
PYEOF
