# Report Template

Structure for model scout reports saved to `docs/scout-reports/YYYY-MM-DD.md`.

```markdown
# Model Scout Report: YYYY-MM-DD

## Executive Summary
3-5 sentences covering:
- Number of new models/frameworks discovered
- Most impactful finding
- Recommended immediate action (if any)

## ASR Model Findings

### [Model Name]
- **Source:** [HuggingFace URL or GitHub URL]
- **Size:** [compressed size in MB]
- **Languages:** [count and list; which current coverage gaps does it fill?]
- **Last updated:** [date]
- **Format:** [ONNX int8, GGUF, litertlm, etc.]
- **sherpa-onnx compatible:** [yes/no/uncertain]
- **Import path:** [community catalog (family) / built-in integration / text-only]
- **Quality assessment:** [per-language evidence: benchmarks, community reports, model card claims; label confidence explicitly]
- **Score:** [composite X/10 = weighted sum / 5; weights and 1-5 criteria in SKILL.md]
- **Recommendation:** [integrate built-in / add to community catalog / evaluate / watch / skip]
- **Effort:** [built-in: low/medium/high; catalog: conversion pipeline effort]
- **Notes:** [key context, caveats, risks]

[Repeat for each finding]

## LLM Model Findings
[Same card format as ASR]

## Language Coverage
Table or list answering: which languages gained a candidate model this cycle,
which still have no good option, and which sweep languages came back empty.
If an eval-harness run (eval/, separate from this scout and run on its own
initiative) produced a fresh WER for a reference language this cycle, record
the number and the command; otherwise omit eval numbers entirely.

## Framework Updates

### sherpa-onnx
- **Current:** [version from .sherpa-version / build.gradle.kts]
- **Latest:** [version from GitHub releases]
- **Changes relevant to us:** [bullet list]
- **Migration effort:** [none / low / medium / high]
- **Recommendation:** [upgrade / defer / skip]

### ONNX Runtime
[Same format]

### LiteRT-LM
[Same format]

### llama-bro (fork)
- **Status:** [unchanged / new commits / released; GGUF-for-audio remains blocked until the profile and text-only export issues land]
- **What changed:** [bullet list]

## Landscape Developments
[Brief paragraphs on new architectures, techniques, or research relevant to on-device ASR]

## Prioritized Recommendations

| # | Action | Impact | Effort | Risk |
|---|--------|--------|--------|------|
| 1 | [description] | [high/med/low] | [low/med/high] | [low/med/high] |
| 2 | ... | ... | ... | ... |

## Watch List
[Items not ready yet but worth checking next time]

## Comparison with Previous Report
[If a previous report exists: what was recommended then vs now, what changed]

## No Change
[Models/frameworks checked with no meaningful updates]
```
