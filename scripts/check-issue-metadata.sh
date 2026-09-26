#!/usr/bin/env bash
# Report open GitHub issues missing metadata (labels, type, milestone).
# Review-time half of the issue-metadata discipline (2026-09-26); the
# creation-time half is the gh-issue-metadata-gate hook.
# Exit 0 = all complete; exit 1 = the listing below is the work list.
set -euo pipefail
REPO="${1:-RisorseArtificiali/anti-vocale}"

missing=0
while IFS=$'\t' read -r n labels type milestone; do
    gaps=()
    [ "$labels" = "-" ] && gaps+=("labels")
    [ "$type" = "NONE" ] && gaps+=("type")
    [ "$milestone" = "none" ] && gaps+=("milestone")
    if [ ${#gaps[@]} -gt 0 ]; then
        echo "#$n missing: ${gaps[*]} (labels=$labels type=$type milestone=$milestone)"
        missing=1
    fi
done < <(gh issue list -R "$REPO" --state open --json number,labels,milestone \
    --jq '.[] | "\(.number)\t\([.labels[].name] | join(","))\t\(.milestone.title // "none")"' \
    | while IFS=$'\t' read -r n lbls ms; do
        t=$(gh api "repos/RisorseArtificiali/anti-vocale/issues/$n" --jq '.type.name // "NONE"' 2>/dev/null || echo NONE)
        printf '%s\t%s\t%s\t%s\n' "$n" "${lbls:--}" "$t" "$ms"
      done)

if [ "$missing" -eq 0 ]; then
    echo "All open issues carry labels, type and milestone."
else
    echo "Metadata gaps above; fix with gh issue edit / the type PATCH."
    exit 1
fi
