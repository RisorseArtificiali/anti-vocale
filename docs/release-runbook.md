# Coordinated Release Runbook (F-Droid + Play Store)

End-to-end procedure for shipping a new Anti-Vocale version to both stores in
sync, so that when the F-Droid MR is approved the new version is already online.

**First formalized for v1.9.0** (2026-08-01). Each step lists the mechanical
proof required before it is considered done.

## Prerequisites

- Working tree on `main`, clean or with only intended changes committed.
- `keystore.properties` present locally (release signing key).
- GitLab token at `~/.config/gl-token` (F-Droid MR polling; Pipeline:Read + MR read).
- F-Droid data fork checked out at `~/data/repo/personal/fdroid-data`, branch `anti-vocale-1.8.2`.

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
3. **Fastlane changelogs** (`fastlane/metadata/android/<locale>/changelogs/<code>.txt`): F-Droid new-version notes.

- `docs/play-store/release-notes.xml`: prepend the new version block in both
  `<en-US>` and `<it-IT>`.
- `fastlane/metadata/android/{en-US,it-IT}/changelogs/<versionCode>.txt`: one file
  per locale, named after the **base** versionCode (Play Store convention).

Proof: both files exist and reference the correct versionCode.

## Step 3. Commit, tag, push

```bash
git commit -m "release: vX.Y.Z (versionCode N) - <summary>"
git tag vX.Y.Z
git push origin main --tags
```

Proof: `git ls-remote --tags origin | grep vX.Y.Z` shows the tag at the commit SHA.

## Step 4. Update the F-Droid recipe (BEFORE building references)

The `reproducible-fdroid` job reads the recipe **from the fork** and builds
whatever `commit:` it points to. Therefore the recipe MUST be pushed to the fork
before the reference build runs, or the reference gets built from the wrong
(previous) commit and the reproducibility check fails on a whole-APK diff.

```bash
cd ~/data/repo/personal/fdroid-data
# Generate the three per-ABI blocks with the repo script (NEVER hand-append:
# the 2026-08-30 hand edit landed the blocks inside VercodeOperation's list
# and cost three failed reference builds):
cd ~/data/repo/personal/anti-vocale
python3 scripts/new-fdroid-version.py \
  --recipe ~/data/repo/personal/fdroid-data/metadata/com.antivocale.app.yml --write
cd - && git diff metadata/com.antivocale.app.yml   # review the generated blocks
```

**THEN the mirror (the step the workflow actually reads):** the reproducible
job clones the GitHub mirror `paoloantinori/fdroid-data-mirror`, branch
`av1100-slim`, NOT this fork. Pushing only the fork fails the recipe-commit
guard (2026-08-30, twice). Copy the same file there:

```bash
cp metadata/com.antivocale.app.yml /path/to/fdroid-data-mirror-checkout/
cd /path/to/fdroid-data-mirror-checkout && git add -A && git commit && git push origin av1100-slim
```

Run `/simplify` and `/code-review high` on the diff before pushing. Check:
- Three versionName/versionCode blocks; the commit SHA matches the tag.
- `binary:` is a multi-line block (trailing space after the key, URL on next line).
- `CurrentVersion` / `CurrentVersionCode` updated (CurrentVersionCode = arm64 code).
- No stale `fix-pg-map-id` or other postbuild experiments left over.

**VersionCode consistency check (mandatory).** The app derives per-ABI codes as
`base*10 + abi` (1=armeabi-v7a, 2=arm64-v8a, 4=x86_64). The recipe hardcodes all
three plus `CurrentVersionCode`. A transposed digit would silently mispublish an
ABI. Verify before every push:

```bash
base=$(grep -m1 'versionCode = ' app/build.gradle.kts | grep -oE '[0-9]+')
echo "armeabi-v7a=$((base*10+1)) arm64-v8a=$((base*10+2)) x86_64=$((base*10+4))"
# CurrentVersionCode must == base*10+2 (arm64 anchor).
```

Then push:

```bash
git add metadata/com.antivocale.app.yml
git commit -m "Update to vX.Y.Z (versionCode ABC/ABD/ABF): <summary>"
git push origin anti-vocale-1.8.2
```

## Step 5. Build reference APKs (reproducible F-Droid)

Now dispatch the workflow. The job clones the fork (which now points at the
correct commit), builds, signs, and uploads.

```
gh workflow run android-release.yml -f tag=vX.Y.Z
```

A built-in guard step fails the job fast if the recipe's `commit:` does not match
the peeled commit of the dispatched tag, so a stale-recipe reference cannot ship
silently.

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

## Step 7. Verify F-Droid reproducibility pipeline

The recipe push (Step 4) triggered the F-Droid CI pipeline on MR `!43599`. But
that pipeline only has valid references to compare against once Step 5 uploads
them, so re-check it after the reference build completes. Poll until the
reproducibility check passes:

```bash
TOKEN=$(cat ~/.config/gl-token)
curl -s --header "PRIVATE-TOKEN: $TOKEN" \
  "https://gitlab.com/api/v4/projects/fdroid%2Ffdroiddata/merge_requests/43599/pipelines?per_page=1" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['status'])"
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
F-Droid APK with a different native stack than every other artifact).

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
