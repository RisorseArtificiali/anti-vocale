# Coordinated Release Runbook (F-Droid + Play Store)

End-to-end procedure for shipping a new Anti-Vocale version to both stores in
sync, so that when the F-Droid MR is approved the new version is already online.

**First formalized for v1.9.0** (2026-08-01). Each step lists the mechanical
proof required before it is considered done.

## Prerequisites

- Working tree on `main`, clean or with only intended changes committed.
- `keystore.properties` present locally (release signing key).
- GitLab token at `~/.config/gl-token` (F-Droid MR polling; Pipeline:Read + MR read).
- F-Droid data fork checked out at `~/data/repo/personal/fdroid-data`, on the current recipe branch (`anti-vocale-1.10.0` as of v1.11.0; check `git branch --show-current` there).

## The two stores, and what must stay in sync

| Concern | F-Droid | Play Store |
|---|---|---|
| Source commit | same tag | same tag |
| versionName / versionCode | per-ABI codes (base*10+abi) | base versionCode |
| Timing | when MR approved | independent |
| Artifact | unsigned APK (F-Droid resigns) | signed AAB |

Only the **version number and commit** must match. The two stores can be
published at different times; there is no hard coupling. The one hard
dependency: the F-Droid recipe `binary:` URLs must resolve (HTTP 200) **before**
the recipe is pushed to the fork, otherwise the F-Droid pipeline fails.

## Step 1. Version bump (in the app repo)

In `app/build.gradle.kts`:
- `versionCode = N` (base; Play Store uses this directly).
- `versionName = "X.Y.Z"`.
- The per-ABI mapping in `androidComponents.onVariants` derives `base*10 + abiCode`
  (1=armeabi-v7a, 2=arm64-v8a, 4=x86_64). No hardcoding; a version bump does not
  require editing the mapping. But update the `?: N` fallback literal so a fresh
  sync still resolves the base code.

Proof: `./gradlew :app:assembleFdroidDebug` succeeds; the per-ABI APKs report the
expected versionCodes in their filenames.

## Step 2. Release notes + changelogs

Three audiences, three artifacts (learned shipping v1.11.0):
1. **Play what's-new**: all sections of `docs/play-store/release-notes.xml` FIRST, then `scripts/extract-release-notes.py --output-dir /tmp/whatsnew` (enforces <=490 chars per locale; uk-UA is NOT supported by the console form, keep it out). The publish workflow reads the XML; forgetting to add the version ships the PREVIOUS notes. If notes are edited after publishing, paste manually in the console (re-publishing creates a new release).
2. **GitHub release body**: two sections, "For everyone" (user-facing bullets, measurements) and "For developers" (families, policies, docs pointers, closed-issue list). Diff vs the PREVIOUS TAG, not the rc.
3. **Fastlane changelogs** (`fastlane/metadata/android/<locale>/changelogs/<code>.txt`): F-Droid new-version notes, one file per locale directory that exists, named after the **base** versionCode.

Proof: the XML contains the new version in every locale section; the extractor runs green; the changelog files exist and reference the correct versionCode.

## Step 3. Commit, tag, push

```bash
git commit -m "release: vX.Y.Z (versionCode N) - <summary>"
git tag vX.Y.Z
git push origin main --tags
```

Proof: `git ls-remote --tags origin | grep vX.Y.Z` shows the tag at the commit SHA.

## Step 4. Update the F-Droid recipe (BEFORE building references)

The `reproducible-fdroid` job clones the recipe **from the mirror**
(`paoloantinori/fdroid-data-mirror`, branch `av1100-slim`) and builds whatever
`commit:` it points to. Therefore the MIRROR must be pushed before the
reference build runs. The FORK push comes later, after Step 6's gate: the fork
push is what triggers the fdroiddata pipeline, and that pipeline must not
start until the signed reference APKs exist (the 2026-08-31 race).

```bash
cd ~/data/repo/personal/fdroid-data
# FIRST sync the recipe branch from upstream master (research 2026-09-01:
# fdroiddata accepts per-app or per-version branch names, but the MR diffs
# against master, and master keeps moving via rewritemeta and bot merges; a
# branch left at the previous merge shows reversions in the MR diff. The
# recipe file itself is normally identical to master right after a merge, so
# the reset discards nothing):
git fetch upstream master
git diff HEAD upstream/master -- metadata/com.antivocale.app.yml   # expect empty
git reset --hard upstream/master
# Generate the three per-ABI blocks with the repo script (NEVER hand-append:
# the 2026-08-30 hand edit landed the blocks inside VercodeOperation's list
# and cost three failed reference builds):
cd ~/data/repo/personal/anti-vocale
python3 scripts/new-fdroid-version.py \
  --recipe ~/data/repo/personal/fdroid-data/metadata/com.antivocale.app.yml --write
cd - && git diff metadata/com.antivocale.app.yml   # review the generated blocks
```

**Cross-check BEFORE pushing anywhere (TASK-420):** run
`SKIP_BINARY_URLS=1 scripts/check-fdroid-release.sh` from the repo root and require
ALL CHECKS PASSED. It pins the invariants that drifted silently on 2026-08-31
(stale sherpa srclib pin cloned from the old blocks: 1.13.4 in the recipe vs
1.13.5 in the app; issue #38) and rejects consecutive blank lines, the
formatting class that made the fork CI's `fdroid rewritemeta` job red on
1.11.1 (a generator join bug fixed 2026-09-01). SKIP_BINARY_URLS=1 because on a fresh release the
signed assets only exist after step 5's dispatch; the FULL checker (URLs included,
no env var) is the post-build, pre-bot-MR gate. If the pin check fails, the
generator should already have synced it: a failure means the recipe was edited by
hand, fix the blocks and re-run.

Run `/simplify` and `/code-review high` on the diff before pushing. Check:
- Three versionName/versionCode blocks; the commit SHA matches the tag.
- `binary:` is a multi-line block (trailing space after the key, URL on next line).
- `CurrentVersion` / `CurrentVersionCode` updated (CurrentVersionCode = the MAX per-ABI code).
- No stale `fix-pg-map-id` or other postbuild experiments left over.

**VersionCode consistency check (mandatory).** The app derives per-ABI codes as
`base*10 + abi` (1=armeabi-v7a, 2=arm64-v8a, 4=x86_64). The recipe hardcodes all
three plus `CurrentVersionCode`. A transposed digit would silently mispublish an
ABI. Verify before every push:

```bash
base=$(grep -m1 'versionCode = ' app/build.gradle.kts | grep -oE '[0-9]+')
echo "armeabi-v7a=$((base*10+1)) arm64-v8a=$((base*10+2)) x86_64=$((base*10+4))"
# CurrentVersionCode must == base*10+4 (the MAX/x86_64 code; fdroiddata convention, licaon-corrected on MR 47391).
```

Then commit (do NOT push the fork yet; Step 6 gates the push):

```bash
git add metadata/com.antivocale.app.yml
git commit -m "Update to vX.Y.Z (versionCode ABC/ABD/ABF): <summary>"
# NO fork push here: the fork push triggers the fdroiddata pipeline, which
# 404s until the signed reference APKs exist. Push after Step 6's gate.
# INVARIANT (2026-09-01 incident): the fork remote is written ONLY at finalize
# (Step 6), with --force-with-lease. prepare and sync-fdroid-mirror never touch
# it; a manual reconciliation push mid-flow is what started the doomed 404
# pipeline. The branch is reset onto fdroid/master each release, so the
# finalize push is non-FF BY DESIGN; the lease plus the recipe-content guard in
# the script keep a maintainer's suggestion commits (!47391-style) safe.
# On a fresh fork clone, install the pre-push hook (backup + shim; exact
# commands in the header of scripts/fdroid-recipe-pre-push.sh): it refuses ANY
# push of a recipe-carrying branch until the signed APK URLs resolve, so the
# premature-push class cannot recur even by hand.
```

**THEN the mirror (the step the workflow actually reads):** the reproducible
job clones the GitHub mirror `paoloantinori/fdroid-data-mirror`, branch
`av1100-slim`, NOT this fork. Pushing only the fork fails the recipe-commit
guard (2026-08-30, twice). The sync requires the COMMIT above (it stamps the
mirror commit with the fork SHA that must contain the synced content):

```bash
cd ~/data/repo/personal/anti-vocale
scripts/sync-fdroid-mirror.sh
# cp + commit + push of the recipe to the mirror (branch av1100-slim). Fails
# loudly on an uncommitted fork recipe, a diverged checkout, or a mirror not
# on av1100-slim; verifies the remote branch matches before declaring sync.
# DRY_RUN=1 prints the actions instead (fetches, guard and diff still run).
```

## Step 5. Build reference APKs (reproducible F-Droid)

Now dispatch the workflow. The job clones the mirror recipe (which now points
at the correct commit), builds, signs, and uploads.

```
gh workflow run android-release.yml -f tag=vX.Y.Z
```

One command chains the whole pre-build half: gate A (the SKIP_BINARY_URLS=1
checker), the mirror sync above, the stale-asset cleanup below, and this
dispatch:

```
scripts/release-fdroid-references.sh prepare vX.Y.Z
```

A built-in guard step fails the job fast if the recipe's `commit:` does not match
the peeled commit of the dispatched tag, so a stale-recipe reference cannot ship
silently.

**Stale-asset rule:** before ANY re-dispatch of the same tag, DELETE the
canonical `app-fdroid-*` release assets (signed + unsigned): the workflow's
uploads are the `binary:` targets, and whatever is attached when the recipe
pushes is what F-Droid ships. The script cannot tell a "right" prior asset
from a wrong one, so the cleanup is unconditional; `prepare` does it
automatically (pattern-matched against the live asset list).

Two jobs:
1. `Build` assembles the unsigned APKs and uploads them with `-unsigned` suffix.
2. `reproducible-fdroid` rebuilds inside the F-Droid buildserver image, signs the
   three per-ABI APKs with `apksigner` (v2/v3 only, `--alignment-preserved`), and
   uploads them as `app-fdroid-<abi>-release.apk` (no `-unsigned` suffix).

This is the slowest step: sherpa-onnx is compiled from source for 3 ABIs (~25-40 min).

Proof: `gh run view <id> --json jobs` shows `reproducible-fdroid` = success, and
`gh release view vX.Y.Z --json assets --jq '.assets[].name'` lists the three
**signed** (no `-unsigned`) APKs.

## Step 6. Verify binary: URLs resolve

The recipe `binary:` uses `%v` which F-Droid resolves to the versionName. Resolve
it manually and HEAD-check each URL:

```bash
for abi in armeabi-v7a arm64-v8a x86_64; do
  curl -sIL "https://github.com/RisorseArtificiali/anti-vocale/releases/download/vX.Y.Z/app-fdroid-${abi}-release.apk" \
    | grep -E '^HTTP|^location' | tail -1
done
```

Proof: all three return HTTP 200 (after redirect).

One command covers this plus two more gates:
`scripts/verify-github-workflow-before-recipe-push.sh vX.Y.Z` also requires
the reproducible job to have SUCCEEDED (not just finished) and the fork and
mirror recipes to be identical. It exists because on 2026-08-31 the recipe was
pushed while the reference build was still running, and the fdroiddata
pipeline failed on 404 binary URLs; run it before every recipe push, after
Step 5 completes.

**Only after the gate passes, push the fork** (this is what triggers the
fdroiddata pipeline; until now the signed APKs were not there yet). The push
is NON-FF by design (the branch was reset onto fdroid/master at Step 4), so
use the lease, and never discard origin-side Builds content (a maintainer's
edits; the machine-managed CurrentVersion fields are exempt because the
generator regenerates them):

```bash
cd ~/data/repo/personal/fdroid-data
git push --force-with-lease origin anti-vocale-1.10.0   # the current recipe branch (see Prerequisites)
# A pre-push hook in this checkout (scripts/fdroid-recipe-pre-push.sh in the
# app repo) blocks any push of the recipe branch until the signed APK URLs
# resolve, so the premature-push class cannot recur even by hand.
```

One command chains the whole post-build half: this gate, the fork push (only
if local recipe commits are pending), and the Step 7 pipeline status:

```
scripts/release-fdroid-references.sh finalize vX.Y.Z
```

## Step 7. Verify F-Droid reproducibility pipeline

The fork push (Step 6) triggered the fdroiddata pipeline in the fork project.
Poll it by recipe branch AND the fork SHA you pushed (a branch-only poll on
the long-lived recipe branch would return the PREVIOUS release's pipeline;
the `!43599` endpoint this step used until 2026-08-31 kept answering with a
merged 1.8.2-era green). `finalize` does this exact poll:

```bash
scripts/release-fdroid-references.sh finalize vX.Y.Z
# manual equivalent: GitLab API pipelines?ref=<recipe branch>&sha=<fork SHA>
```

Proof: GitLab pipeline `success`; the build is marked "verified reproducible".

## Step 8. Play Store (independent of F-Droid timing)

Trigger the Play Store publish job (AAB), or upload manually. This can run in
parallel with the F-Droid MR review; it does not block on it.

Proof: Play Console shows the new release in review/published.

## Post-release

- Update `project_play_store_release.md` memory with any new gotchas.
- Keep the F-Droid MR polling cron active until merge.
- If reproducibility fails: do NOT stack workarounds. Diff the built vs reference
  APK (`apksigcopier`, `unzip -l` diff) to find the nondeterministic element.

## Preflight and verify gates (TASK-335, added after v1.10.0)

One command before every tagging and before every workflow dispatch:

```bash
scripts/release-preflight.sh --tag vX.Y.Z   # add --offline to skip network checks
# The preflight checks that .sherpa-version, fetch-sherpa-aar.sh, and the
# build.gradle.kts SRCLIB PIN comment are in sync, and that the fork recipe's
# srclib pin matches the commit in .sherpa-version.
scripts/release-verify.sh vX.Y.Z            # after the signing job completes
```

The preflight encodes every failure mode of the v1.10.0 release day:
version-code derivation and the `?: N` fallback literal; Play release notes
within the 500-char limit (the extractor fails the build on over-length since
74aa4f2); fastlane changelogs present and within 500 chars (F-Droid limit);
the sherpa AAR on disk matching the fetch-script version and upstream size;
the fork recipe's newest Builds entry pointing at the tag commit with the
right vercodes and CurrentVersionCode; and, critically, the recipe's sherpa
srclib pin matching the sherpa tag of the AAR version (a stale pin builds the
F-Droid APK with a different native stack than every other artifact). Since
2026-08-31 it also verifies every recipe `ndk:` pin has an exact-version entry
in the reference workflow's NDK preinstall map (that day the 1.11.0 blocks
moved to r28c while the workflow preinstalled only r27c: fdroidserver cannot
download NDKs in that container, and the reference build died ~40 min in).

## Dispatch semantics and hard rules (v1.10.0 + 1.10.0-final lessons)

- **The mirror is the #1 drift source** (2026-08-21: three reference failures traced to it).
  Before ANY `workflow_dispatch` of the reproducible job: `diff` the mirror's recipe
  (github.com/paoloantinori/fdroid-data-mirror, branch `av1100-slim`) against the live
  fdroiddata MR HEAD for the app. The workflow guard will fail loudly on drift, but
  checking first saves a 45-minute build cycle.
- **Never `[ci skip]` on fdroiddata MRs**: their runners allow 4h; skipping blocks the
  maintainers' verification (learned 2026-08-21).
- **The reproducible job's guard is the last line of defense**: it fails the build
  unless every versionCode of the newest recipe block exists, its embedded
  versionCode matches, AND its embedded git revision equals the recipe's commit.
  A red guard is never "retry it": read the error, it names the exact drift.
- **fdroiddata uses ONE build block per versionCode sharing the versionName**:
  "the newest version" = all blocks whose versionName equals the last one.
- **Fastlane screenshot deletions do not propagate** to the F-Droid repo; same-name
  overwrites do. To retire a bad screenshot, replace it (commit a clean file under
  the same name), never just delete it upstream.

- `workflow_dispatch` with `-f tag=` checks out THE TAG COMMIT: anything fixed
  on main after tagging (notes, scripts, recipe couplings) does not reach that
  artifact. Fix-forward on main and dispatch WITHOUT the tag when the artifact
  content itself must include post-tag changes (same versionCode is fine for
  builds; see the next rule for uploads).
- Play rejects re-uploading an already-uploaded versionCode. Fix release notes
  by pasting them in the console; do not re-dispatch with `play-store-track`
  for the same code. internal is the deliberate default so promotion to
  production stays a human console decision.
- NEVER `gh release upload --clobber` on the canonical `app-fdroid-<abi>-release.apk`
  names: they are the F-Droid reproducibility references. Interim builds must
  be copied to a distinct filename before upload.
- The release-event run stays red (release-sanity) while the signing job is
  still building; the green record is the completed dispatch run. A red sanity
  with all three signed URLs resolving is the expected intermediate state.

## Play Console manual checklist (per release)

1. What's new: verify the <=500-char texts (or paste them if the upload
   predates a notes fix).
2. Advertising ID declaration (Policy, App content): answer NO. The app ships
   no AD_ID permission by design; the console warning about zeroed IDs is
   expected and correct for a no-tracking app.
3. Native debug symbols warning: advisory; a symbols zip from stripped
   prebuilt sherpa libs has limited value, skip unless native crashes need
   analysis.
4. Promote internal -> production; Google's review approval is the last gate.
