# CI / GitHub Actions

Workflows live in `.github/workflows/`. Everything here is dispatch- or event-driven on GitHub's infra; no self-hosted runners.

| Workflow | File | Trigger | What it does |
|----------|------|---------|--------------|
| Android CI/CD | `android-release.yml` | push/PR to main, release published/prereleased, manual dispatch | Unit tests + lint, fdroid release APK, per-ABI tester APKs, Play AAB upload, reproducible F-Droid reference APKs, release sanity gates (see `docs/release-runbook.md`) |
| Store listing | `store-listing.yml` | manual dispatch | Uploads Play Store listing text + screenshots via `scripts/upload-store-listing.py` |
| Nightly | `nightly.yml` | manual dispatch ONLY | Builds playStore release APKs from main and publishes them as a rolling `nightly` pre-release (TASK-465) |

## Nightly builds (TASK-465)

### How to trigger

Actions tab -> **Nightly** -> **Run workflow** (branch: `main`), or:

```
gh workflow run nightly.yml --ref main
```

Dispatch-only by design: no push trigger and no schedule. A cron can be added later only after the manual flow has been proven (first run + device install check, per the 2026-09-08 maintainer plan). A concurrency group (`nightly`, cancel-in-progress) prevents two manual runs from racing on the same rolling tag.

### What it produces

- `:app:assemblePlayStoreRelease` from the current `main` HEAD: the 3 per-ABI APKs (`arm64-v8a`, `armeabi-v7a`, `x86_64`; per-ABI splits come from `splits.abi` in `app/build.gradle.kts`, universal disabled).
- Signed with the project release key via the `KEYSTORE_BASE64` / `KEYSTORE_PROPERTIES` secrets, the same keystore `android-release.yml` signs its uploaded AAB with, and the key the F-Droid recipe pins (`AllowedAPKSigningKeys`). It updates in place over another nightly, but NOT over a store install: Google re-signs Play deliveries via Play App Signing and F-Droid signs its own builds, so coming from either store means uninstalling first (device-verified 2026-09-09).
- Published to a rolling pre-release on the tag `nightly` (never a semver tag), assets named `antivocale-nightly-<abi>.apk` so they can never collide with real release assets. Each run deletes the same-named old assets first, re-points the tag at the run's commit, and rewrites the release body (date UTC, versionName, commit SHA). A sanity step fails the run if the release does not end up with exactly 3 nightly assets. Any older `nightly-*` pre-release is deleted after a successful publish.

### Why the tag is non-semantic (F-Droid safety)

The fdroiddata recipe (`metadata/com.antivocale.app.yml`) pins `UpdateCheckMode: Tags`. **Do not rename the tag to anything resembling `X.Y.Z`.** The release body carries the same warning.

Research verdicts, code-verified against fdroidserver master commit `3cbbe81055e5e03a1644cc5a2f3149c5783d83d5` (2026-08-27) in `fdroidserver/checkupdates.py`:

- **REFUTED assumption, and the key pre-publish gate:** plain `Tags` mode does NOT skip the `nightly` tag. `check_tags()` takes `vcs.latesttags()` (all tags, newest commit date first, no name filter) and parses the checkout's `build.gradle` for versionName/versionCode. If the parsed versionCode exceeds the recipe's `CurrentVersionCode`, the bot proposes the nightly as an update; if a versionName parse returns `Unknown`, the metadata's `CurrentVersion` can literally become the string `nightly` (checkupdates.py, `version = tag` branch). The tag name alone is not protection.
- **Required before the first publish (the one allowed fdroiddata edit per the TASK-465 plan):** change the recipe to `UpdateCheckMode: Tags ^v[\d.]+$`. The pattern is the text after the space and filters the tag list; the official docs describe exactly this for apps that tag non-release versions (https://f-droid.org/en/docs/Build_Metadata_Reference/#UpdateCheckMode). Shape-based, not digit-suffix-based: `.*[0-9]$` was the original plan but a 2026-09-09 review caught that every pre-release shape we actually cut (`v1.10.0-beta.1/2`, `v1.11.0-rc1..3`) ends in a digit and would pass it, with production precedent (the bot proposed `v1.10.0-beta.2` as 364 over the released 344 and it shipped via the mirror); `^v[\d.]+$` matches plain `vX.Y.Z` only, so beta/rc tags, the `nightly` tag, and any future dated `nightly-*` tag are all excluded. Suffixed tags being outside update checks is by design; the canonical release path enforces the plain shape (`scripts/release-create.sh`). Alternative without a recipe change: guarantee the nightly commit's versionCode never exceeds the released one (true today: nightly base 41 vs released 4xx codes) and accept that fdroidserver may log an `FDroidException` for the app right after a release while the nightly trails.
- **Force-move is harmless:** each bot run re-reads tags fresh from origin with no value cache; the only persisted state is the recipe's `CurrentVersionCode`. A moved `nightly` pointing at a same-versionCode commit is constant every run and never looks newer.
- **GitHub releases are irrelevant to the bot:** `checkupdates.py` contains no GitHub API or release-asset lookups; under Tags mode only git refs of the recipe's `Repo` URL are read. The pre-release flag, the assets, and the body are invisible to it.
- **Our side is silent:** nothing in `.github/workflows/` or `scripts/` watches tags or reacts to a tag move (the release workflow runs on release events and manual dispatch only; the fdroid-side bot runs entirely on F-Droid's infra).

### Tester caveats

- **Untested snapshot.** A nightly has passed compilation only: no unit tests, no lint gate, no device verification, no review. Expect breakage.
- **The About row shows `1.12.0-SNAPSHOT`.** versionName does not change between nightlies; the commit SHA on the release body is the only identity.
- **Store-installed copies (Play AND F-Droid):** both carry signatures that differ from the project release key (Play App Signing re-signs deliveries; F-Droid signs its own builds). Moving to a nightly from either store requires uninstalling first, and uninstalling erases all downloaded transcription models (hundreds of MB to re-download).
- versionCode stays in the current released base space (base 41 x 10 + ABI suffix), so a nightly never outranks the next real release on Play.
