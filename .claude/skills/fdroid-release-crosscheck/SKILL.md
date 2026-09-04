# F-Droid release cross-check (app repo vs recipe)

Every F-Droid release involves FOUR artifacts that must stay in sync: the app's
`app/build.gradle.kts` (versionName/versionCode), the repo's `.sherpa-version`
(the srclib pin the app was built against), the fdroiddata recipe (per-ABI
build blocks, srclib pins, CurrentVersion), and the GitHub release's signed
APKs (the `binary:` targets). The checkupdates bot clones recipe blocks
VERBATIM: anything stale in the last block (wrong srclib pin, wrong commit)
propagates silently, and the reproducibility check can "pass" by comparing two
identically-wrong builds. That exact failure shipped a stale sherpa 1.13.4 pin
into the 1.11.0 blocks on 2026-08-31 (the app builds 1.13.5; caught pre-merge
only by manual cross-check).

## The procedure (in order, BUILD-FIRST since 2026-09-04)

The release is BUILT before any tag exists; tag + release + all 12 assets are
created together by one publish act. The checkupdates bot keys on git TAGS
(`UpdateCheckMode: Tags`), so until that act nothing is visible to it, and
afterwards every `binary:` URL already resolves (the 2026-09-04 race: bot MR
06:23 UTC, signed APKs 07:20, pipeline 404 on the tag-then-build flow).

1. **Generate, never hand-edit**: `python3 scripts/new-fdroid-version.py --recipe <fdroid-data>/metadata/com.antivocale.app.yml --commit $SHA --write` (refuses duplicate keys, refuses versionCode reuse; `$SHA` = the pushed bump commit; the `--commit` override replaces the peel-the-tag default, which needs a tag that does not exist yet).
2. **Cross-check BEFORE pushing anywhere**: `SKIP_BINARY_URLS=1 EXPECT_COMMIT=$SHA scripts/check-fdroid-release.sh vX.Y.Z`
   from the repo root must print ALL CHECKS PASSED (the generator now syncs the
   srclib pin from `.sherpa-version` automatically; historical blocks stay as-built).
   SKIP_BINARY_URLS=1 because on a fresh release the signed assets do not exist yet.
3. **Push ONLY the mirror before dispatch**: the GitHub mirror
   `paoloantinori/fdroid-data-mirror` branch `av1100-slim` (what the
   reproducible-fdroid job actually clones) via `scripts/sync-fdroid-mirror.sh`.
   NEVER push the GitLab fork branch at this stage: the fork push triggers the
   fdroiddata pipeline, which 404s on the `binary:` URLs until the signed APKs
   exist (2026-09-01 incident: a premature fork push burned a runner on exactly
   that). The fork branch is written ONLY at finalize
   (`scripts/release-fdroid-references.sh finalize vX.Y.Z`), after gate C
   proves the signed APKs exist; a pre-push hook in the fork checkout enforces
   the same rule on manual pushes.
4. **Dispatch the PRE-TAG build**: `gh workflow run android-release.yml -f commit=$SHA`
   (or `scripts/release-fdroid-references.sh prepare vX.Y.Z $SHA`, which chains
   steps 2-4). Assets upload as workflow artifacts; no release is created. Note
   the run id.
5. **Publish in one act, then full cross-check**: `scripts/release-create.sh vX.Y.Z --run-id <id> --commit $SHA --notes-file <body>` creates tag + release + the 12 assets together. Then `scripts/check-fdroid-release.sh`
   (no env var: now including the `binary:` URL resolvability). It verifies:
   srclib pin in ALL THREE blocks == `.sherpa-version` (issue #38), AAR script
   version == `.sherpa-version`, recipe commit == peeled tag, vercodes ==
   base*10+{1,2,4}, CurrentVersionCode == max (base*10+4), all three `binary:` URLs
   resolve 200, YAML parses with no duplicate top-level keys.
   If re-publishing after a wrong-pin build: DELETE the six `app-fdroid-*` assets
   (signed + unsigned) first, or the old wrong-sherpa binaries remain as the
   `binary:` targets.
   LEGACY fallback: `-f tag=vX.Y.Z` dispatch (workflow creates the release
   itself) still works via `prepare vX.Y.Z` without the commit; do not use it
   for releases the bot can race.

## Invariants to remember

- `.sherpa-version` is the single source of truth for the srclib pin; the
  recipe's NEW blocks must match it, old blocks must not be rewritten.
- The reference build signs what the recipe says: fix the recipe BEFORE
  dispatching, or you sign wrong-pin APKs.
- A reproducibility pass proves recipe-vs-binary consistency, NOT
  correctness of either against the app's actual dependencies.
- The bot MR ("Update Anti-Vocale to NNN") is usually preferable to a manual
  MR (precedent 2026-08-21: manual MR closed in favor of the bot's), but the
  bot arrives 1-2 days after the tag: run the cross-check in that window.
