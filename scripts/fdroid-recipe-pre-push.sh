#!/usr/bin/env bash
# Pre-push hook for the fdroid-data FORK checkout: refuses any push of the
# recipe branch until the signed reference APKs exist on the GitHub release.
#
# Why a hook when the orchestrator already orders the flow (2026-09-01
# incident): the incident WAS a manual push, made mid-flow to satisfy a script
# guard. Scripts order their own path; this hook is the one chokepoint every
# push crosses, scripted or hand-typed. The fdroiddata pipeline starts from
# the fork push and dies on binary: 404s until the reference build has signed
# and attached the APKs, so a push before that is always wasted.
#
# Install (run once per fork clone; back up any existing hook first):
#   H=~/data/repo/personal/fdroid-data/.git/hooks/pre-push
#   [ -f "$H" ] && cp "$H" "$H.bak.$(date +%s)"
#   printf '#!/bin/sh\nexec "%s/scripts/fdroid-recipe-pre-push.sh" "$@"\n' \
#     "$HOME/data/repo/personal/anti-vocale" > "$H"
#   chmod +x "$H"
#
# Behavior: reads the refs git offers on stdin; for the recipe branch only,
# derives the newest versionName from the recipe BEING PUSHED and requires
# all three per-ABI signed APK URLs to resolve. Every other ref passes
# untouched. Local-only pushes (remote sha all zeros) pass: nothing leaves
# the machine, no pipeline starts.
set -euo pipefail

APP_REPO="${FDROID_CROSSCHECK_APP_REPO:-$HOME/data/repo/personal/anti-vocale}"
RECIPE_REL="metadata/com.antivocale.app.yml"
RELEASE_BASE="https://github.com/RisorseArtificiali/anti-vocale/releases/download"

while read -r local_ref local_sha remote_ref remote_sha; do
  # All zeros in the LOCAL position = branch DELETION: nothing recipe-shaped
  # leaves the machine; allow. All zeros in the REMOTE position = branch
  # CREATION, i.e. the first push of a new release branch: that push leaves
  # the machine like any other and MUST run the checks (an earlier draft had
  # the two inverted, which made the hook inert on exactly that push).
  [ "$local_sha" != "0000000000000000000000000000000000000000" ] || continue
  # Key on CONTENT, not the branch name: any pushed ref whose tree carries
  # the recipe file is a recipe branch, whatever it is called and whatever
  # this checkout happens to have checked out right now.
  recipe="$(git show "$local_sha:$RECIPE_REL" 2>/dev/null)" || continue

  ver="$(awk '/^  - versionName:/{v=$3} END{print v}' <<<"$recipe")"
  [ -n "$ver" ] || { echo "pre-push: no versionName in the pushed recipe; refusing" >&2; exit 1; }

  for abi in armeabi-v7a arm64-v8a x86_64; do
    url="$RELEASE_BASE/v$ver/app-fdroid-$abi-release.apk"
    if ! curl -fsIL --max-time 20 "$url" >/dev/null 2>&1; then
      echo "pre-push: REFUSED: $url does not resolve yet." >&2
      echo "  The fork push starts the fdroiddata pipeline, which dies on binary:" >&2
      echo "  404s until the reference build signs and attaches the APKs." >&2
      echo "  Run scripts/release-fdroid-references.sh finalize v$ver when green," >&2
      echo "  or re-run this push after the assets exist (2026-09-01 incident)." >&2
      exit 1
    fi
  done
  echo "pre-push: signed APKs for v$ver resolve; recipe push allowed" >&2
done

exit 0
