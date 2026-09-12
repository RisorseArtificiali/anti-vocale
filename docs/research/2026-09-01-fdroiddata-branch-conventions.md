# Research Report: fdroiddata branch conventions for app-update merge requests

**Date**: 2026-09-01
**Depth**: standard (GitLab API first-party evidence + official CONTRIBUTING + per-app MR history)
**Confidence**: HIGH on "both per-app and per-version branches are accepted"; MEDIUM on the documented preference (single official source)

## Executive Summary

fdroiddata accepts both patterns without friction, and there is no rule requiring either: the official CONTRIBUTING suggests naming a branch after the app id (per-app, long-lived), the bot fleet does exactly that at scale (one branch name, one MR per version, indefinitely), and human contributors to high-frequency apps freely use per-version branch names that maintainers merge as-is. For anti-vocale 1.11.1 the branch NAME is a non-decision: reusing `anti-vocale-1.10.0` matches our tooling and precedent, and the only operational requirement is that the branch be refreshed from upstream master before the new bump commit so the MR diff shows only our delta.

## Findings

### 1. The documented convention: branch named after the app id

CONTRIBUTING.md, "Setting up fdroiddata for merge requests", tells contributors to "Create a new branch. Naming it like the app name or, much better, the app id makes it easier to keep track of your contributions" [1]. This is the only written guidance; it describes a per-app branch, not per-release branches. Nothing in CONTRIBUTING, the MR templates, or the docs prescribes one MR per app or forbids branch reuse after a merge; every version update is its own MR regardless of branch naming [1][2].

### 2. What the update bots do (the dominant volume): one branch per app, forever

The majority of fdroiddata update MRs now come from bots, and their source branch is always the bare app id: `eu.faircode.email` carried 15+ successive merged MRs (FairEmail 2319 through 2333, June to September 2026, one MR per version, same branch name each time); same shape for `io.nekohasekai.sfa`, `com.kunzisoft.keepass.libre`, `website.leifs.delta.foss` and the rest of the current merged queue [3]. This is the per-app pattern at scale: the branch is recreated per MR under the same name, and maintainers merge it every time.

### 3. What human contributors do: both, freely

The Thunderbird/K-9 updater (a human) uses per-version branches (`tb-19.0`, `tb-21.1`, `tb-beta-22.0b1`, occasionally a bare `master`), one branch per release, merged without comment across 15+ MRs in 2026 [4]. Other humans in the current merged queue use version-annotated branches (`ie.equalit.ceno_v2.11.3`, `fix-levyra-2050100`), topic branches (`update-metadata-september`), or app-id branches [3]. Maintainers show no visible preference in merge behavior between the styles.

### 4. Maintainers work directly on the contributor's open-MR branch

First-party evidence from our own !47391: licaon73's review suggestions were applied as commits ONTO our source branch `anti-vocale-1.10.0` in the fork ("Apply 9 suggestion(s)", "Apply 1 suggestion(s)"), and the MR merged from there [5]. So the open-MR branch is shared ground; the practical rule for rapid successive versions is: if an MR is still open when a new version drops, push the newer bump to the same branch (maintainers ask for exactly this); once merged, the next version is a fresh MR from whichever branch name you pick.

### 5. What this means for anti-vocale 1.11.1

- Reusing `anti-vocale-1.10.0` (in place, already the runbook's and the scripts' assumption; they derive the branch from the checkout, so a rename would be free but is not needed) is fully within convention. The stale name is cosmetic.
- The one operational requirement before the bump commit: refresh the branch from upstream `fdroid/master` (master keeps moving via rewritemeta and other merges; if the recipe file changed on master after !47391, pushing our branch as-is would show reversions in the MR diff). This is a sync step, not a naming decision.
- If we ever want to match the documented idiom, `com.antivocale.app` is the target name; nothing in the tooling pins the current name except the runbook's Prerequisites line (one line to update).

## Confidence Assessment

- HIGH: both per-app and per-version branches are accepted and merged (API evidence across dozens of MRs: bot fleet, Thunderbird human, mixed humans).
- HIGH: each version update is a separate MR; open MRs are updated by pushing to the same branch (our own MR's history plus standard maintainer behavior).
- MEDIUM: "app id is the preferred name" rests on the single CONTRIBUTING sentence; no maintainer statement enforces it in practice.

## Sources

1. fdroiddata CONTRIBUTING.md, "Setting up fdroiddata for merge requests" (github.com/f-droid/fdroiddata/blob/master/CONTRIBUTING.md; same file on GitLab)
2. fdroiddata new-app MR template wording on fork/branch visibility (gitlab.com/fdroid/fdroiddata/-/merge_requests/45747)
3. GitLab API `projects/fdroid%2Ffdroiddata/merge_requests?state=merged` sampled 2026-09-01: source_branch + title of the last ~30 merged MRs (bot pattern) and the FairEmail history (15 consecutive versions from `eu.faircode.email`)
4. Same API, `search=Thunderbird`: 15 merged MRs 2026-05 to 2026-08 with per-version source branches
5. Local fork `~/data/repo/personal/fdroid-data`, branch `anti-vocale-1.10.0`: licaon73's "Apply N suggestion(s)" commits pushed to our source branch during !47391 review
