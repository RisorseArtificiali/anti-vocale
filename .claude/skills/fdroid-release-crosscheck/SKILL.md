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

## The procedure (in order)

1. **Generate, never hand-edit**: `python3 scripts/new-fdroid-version.py --recipe <fdroid-data>/metadata/com.antivocale.app.yml --write` (refuses duplicate keys, refuses versionCode reuse, reads version + peeled tag from origin).
2. **Cross-check BEFORE pushing anywhere**: `SKIP_BINARY_URLS=1 scripts/check-fdroid-release.sh`
   from the repo root must print ALL CHECKS PASSED (the generator now syncs the
   srclib pin from `.sherpa-version` automatically; historical blocks stay as-built).
   SKIP_BINARY_URLS=1 because on a fresh release the signed assets do not exist yet.
3. **Push BOTH recipe copies**: the GitLab fork branch (what an MR would use)
   AND the GitHub mirror `paoloantinori/fdroid-data-mirror` branch `av1100-slim`
   (what the reproducible-fdroid job actually clones; pushing only the fork
   fails the recipe-commit guard).
4. **Full cross-check post-build, pre-bot-MR**: `scripts/check-fdroid-release.sh`
   (no env var: now including the `binary:` URL resolvability). It verifies:
   srclib pin in ALL THREE blocks == `.sherpa-version` (issue #38), AAR script
   version == `.sherpa-version`, recipe commit == peeled tag, vercodes ==
   base*10+{1,2,4}, CurrentVersionCode == max (base*10+4), all three `binary:` URLs
   resolve 200, YAML parses with no duplicate top-level keys.
5. **Only then dispatch** `gh workflow run android-release.yml -f tag=vX.Y.Z`.
   If signed APKs already exist on the release from an earlier (wrong-pin)
   build: DELETE the six `app-fdroid-*` assets (signed + unsigned) first, or
   the old wrong-sherpa binaries remain as the `binary:` targets.

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
