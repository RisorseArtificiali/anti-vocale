# F-Droid Release Guards: Design and Implementation

**Status**: Implemented (local, pending review)  
**Created**: 2026-08-31, during the v1.11.0 pipeline incident  
**Incident**: `fdroid-pipeline-race-condition-2026-08-31.md`  
**Related**: TASK-420 cross-check tooling, `docs/release-runbook.md`

## Problem

Two failure modes shipped the broken v1.11.0 fdroiddata pipeline:

1. **Race**: the recipe push triggers the fdroiddata CI immediately, but the
   reference APKs (the `binary:` URLs) exist only 40-50 min later, after the
   sherpa-onnx source build. Result: HTTP 404, red pipeline.
2. **NDK drift**: the recipe's 1.11.0 blocks moved to `ndk: r28c` (licaon's
   review) while the reference workflow preinstalled only r27c. Nothing linked
   the two files. Result: the reference build died ~40 min in; the signed APKs
   never existed, so the race above could not even be waited out.

## Design: three explicit gates, no optimistic waits

The guards replace "hope the timing works out" with checks at each phase
boundary. Each gate fails fast, names the exact problem, and says what to wait
for or fix.

| Gate | When | Tool | Checks |
|---|---|---|---|
| A. Pre-dispatch | after recipe generation, before `gh workflow run` | `scripts/check-fdroid-release.sh` (`SKIP_BINARY_URLS=1`) | version/codes, srclib pin, commit, vercodes, NDK pins mapped in the workflow, YAML |
| B. Workflow self-check | at job setup, before the 40-min build | NDK map loop in `android-release.yml` | every recipe `ndk:` pin resolves to an exact sdkmanager version; unmapped pin exits in seconds |
| C. Pre-recipe-push | after the reference build, before pushing to the fork | `scripts/verify-github-workflow-before-recipe-push.sh` | reproducible job SUCCEEDED, three signed APK URLs are 200, fork recipe == mirror recipe |

Gate C is the race-condition killer: the runbook (Step 6) now requires it
before every recipe push. Gate B is the drift killer: the workflow derives the
pins from the recipe through an explicit `NDK_MAP` and refuses to build on an
unmapped pin; check 5b in Gate A enforces the same invariant at pre-dispatch
time so the drift is caught before any CI minutes are spent.

## Why "optimistic timeout" only in the CI-side guard

`scripts/ci/fdroid-build-with-timeout.sh` (for a fdroiddata-side adoption,
currently unused there) wraps `fdroid build` with short per-URL HEAD checks:
artifacts either exist (answers in ~1-2s) or they do not (404 in ~1-2s). A
short deadline distinguishes "not ready" from "network broken" without ever
waiting minutes. The same principle, applied to the release flow, is why the
gates fail in seconds rather than burning a 40-min build to discover a 404.

## Files

- `.github/workflows/android-release.yml`: NDK_MAP loop in the "Install
  fdroidserver" step (Gate B)
- `scripts/check-fdroid-release.sh`: check 5b (Gate A extension)
- `scripts/verify-github-workflow-before-recipe-push.sh`: Gate C
- `scripts/ci/fdroid-build-with-timeout.sh`: optimistic-guard variant for
  CI-side use
- `docs/release-runbook.md`: Step 6 and preflight list reference the gates
- `docs/diagrams/fdroid-release-sequence.mmd` and
  `docs/diagrams/fdroid-release-guarded-flow.md`: event order and guarded flow

## Alternatives rejected

- **Automatic retry in the fdroiddata CI**: no native "retry when artifact
  appears"; hides the timing from the maintainer.
- **Long timeouts (wait 60 min in CI)**: wastes runner time and gives no
  diagnosis; fail-fast with a message is strictly better.
- **Reverting the recipe to `ndk: r27c`**: contradicts the fdroiddata
  maintainer's explicit review request; fixing the container-side map is the
  smaller, upstream-compatible change.

## Verification performed (2026-08-31, local)

- YAML parse of the workflow; `bash -n` on all three scripts.
- The NDK loop simulated against the live mirror recipe: r27c -> 27.2.12479018,
  r28c -> 28.2.13676358; unmapped pin (r29b) exits non-zero.
- `SKIP_BINARY_URLS=1 scripts/check-fdroid-release.sh`: ALL CHECKS PASSED,
  including the new NDK line.
- Negative test: the check's core logic against the PRE-fix workflow fails on
  r28c, i.e. Gate A would have caught the incident before dispatch.
