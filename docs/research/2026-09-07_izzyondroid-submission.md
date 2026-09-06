# Research Report: IzzyOnDroid (apt.izzysoft.de) submission for Anti-Vocale

**Date**: 2026-09-06
**Depth**: standard (live scrape of izzyondroid.org docs, the archived GitLab repo, the Codeberg tracker with issue-template and outcome analysis, our own GitHub releases, the F-Droid recipe and package index)
**Confidence**: HIGH on requirements, size caps, dual-listing policy, signature/upgrade-path mechanics, and the AI-policy enforcement record; MEDIUM on the exact review-time distribution (sampled, not exhaustive)

## Executive Summary

No, we should not submit, and under the current policy we effectively cannot. The mechanics all exist and mostly favor us (dual listing with F-Droid is explicitly allowed, our APKs sit on tagged GitHub releases, Fastlane is in place, the license is fine, and the signature story is actually seamless because our F-Droid builds are verified reproducible and dev-signed). But two hard blockers kill it: first, their App Inclusion Policy now rejects apps "fully or in part created by generative AI tools", and in the last two weeks of their tracker every single request disclosing our honest level ("Substantial") was rejected, most of them explicitly on that clause; second, the 30 MB per-APK cap rules out arm64-v8a (35.5 MB) and x86_64 (39.5 MB), leaving a 32-bit-only listing that most modern devices could not use. A submission today would end as a public rejection citing our AI usage, with zero benefit obtained.

## Findings

### 1. Where IzzyOnDroid lives now (big infrastructure change)

The old GitLab project `gitlab.com/IzzyOnDroid/repo` is archived; its README states the issue tracker moved to Codeberg and that inclusion requests must go there [1]. The canonical documentation is now the website `izzyondroid.org` [2], with metadata in `codeberg.org/IzzyOnDroid/repodata` [3]. The repo metadata README notes that PRs to *adjust* metadata and to *remove* apps are open, but the checklist item "allowing for PRs to add new apps" is still unticked, so new apps go through the issue tracker only [3].

### 2. Inclusion requirements (current, live pages)

From the App Inclusion Policy [4] and the New App Inclusions reviewer guide [5]:

- **License and code**: libre OSI/FSF license, code freely accessible on a forge. Ours: Apache-2.0 (see correction below), GitHub. PASS.
- **No proprietary components; trackers rejected in general.** If the app processes sensitive data it must have no ATS elements at all. The fdroid-flavor APKs have no Firebase and no analytics; F-Droid tags us with zero AntiFeatures [9]. PASS (we would submit the `app-fdroid-*-release.apk` assets).
- **Downloads**: the app must not download additional *executable* binary files without explicit opt-in consent. Our on-demand model downloads are user-initiated from the Models tab (explicit tap, size shown), and ONNX weights are data, not executables; no self-updater exists. This maps cleanly; a reviewer may still ask, and their metadata has a notes mechanism for it (see NetGuard's `MaintainerNotes` [10]).
- **APK provenance**: signed with a release key, not `debuggable`/`testOnly`; APKs attached to tagged Git releases (GitHub Releases are a preferred source). Ours: `app-fdroid-<abi>-release.apk` on tags `v1.11.x` [8]. PASS (v-prefixed tags are handled; an existing IoD app with `v1.33-pro` tags runs fine [10][11]).
- **Metadata**: Fastlane structure required in the app repo: short description, full description, icon, screenshots. Ours: `fastlane/metadata/android/en-US` has all of these plus changelogs; `it-IT` has descriptions and screenshots (icon only in en-US, which is the convention). PASS.
- **Categories**: games unlikely, violence/explicit/tracker-heavy apps rejected, webview wrappers rejected [4].

**Correction to the task premise**: our license is **Apache-2.0**, not GPL-3.0. The repo LICENSE file is the Apache License 2.0 and the F-Droid metadata says `License: Apache-2.0` [9]. Either way it is OSI/FSF libre, so this changes nothing for IoD eligibility.

### 3. Size limits versus our APKs: only one ABI fits

The policy: IoD "usually reserves **up to 30 megabytes per app**" (exceptions exist, rule-of-thumb), 30 MB is "at the same time the upper size limit for single `.apk` files", and up to 3 versions are kept if they fit within that budget [4]. The reviewer guide repeats it bluntly: "We cannot currently accept APK files over 30 MB" [5]. The FAQ confirms the limit is about storage/transfers/scanner API costs, says the limit "will certainly be increased at one point", and that exceptions "must be well reasoned" [6]. IoD also hosts only `armeabi` and `arm64` ABIs; other architectures only inside a fat build [1][6].

Our v1.11.3 release assets (exact bytes from the GitHub API [8]):

| Asset | Bytes | MB / MiB | Verdict |
|---|---|---|---|
| `app-fdroid-armeabi-v7a-release.apk` | 23,230,338 | 23.2 MB / 22.2 MiB | fits |
| `app-fdroid-arm64-v8a-release.apk` | 35,539,045 | 35.5 MB / 33.9 MiB | over the cap |
| `app-fdroid-x86_64-release.apk` | 39,479,373 | 39.5 MB / 37.7 MiB | over, and ABI not hosted |

So a compliant listing would be armeabi-v7a only, single version retained (two versions of 23 MB bust the per-app budget). That excludes arm64-only devices (a growing share of modern hardware) and gives most Android 16 users nothing to install. An arm64 exception request would be 18 percent over a limit they describe as hard for new listings, with the FAQ explicitly saying exceptions are rare.

### 4. The AI policy: decisive blocker, enforced aggressively

The policy has a dedicated AI section [4]: they are "strongly opposed to apps which are fully or in part created by generative AI tools"; "Vibe-coded apps will be rejected"; apps acting as front-ends for LLMs, or integrating with such services, will be rejected; LLM use for "research, brainstorming, inspiration, debugging, look-ups" is acceptable "provided their output is not included in the app's code". Separately, category 6 rejects apps fronting big AI platforms built on uncompensated scraped work.

The submission template now contains a mandatory "AI Tools Usage" section: a required dropdown (None, Minimal, Moderate, Substantial, Dominant), tool names, a description of what the tools did, and accountability checkboxes [7].

Practice, from all 50 `app-request` issues filed 2026-08-26 to 2026-09-06 (bodies plus closing comments pulled from the Codeberg API):

- **Substantial: all 11 closed issues were rejected; 9 of them explicitly on AI-policy grounds** (for example #540, #536, #525, #555, #554, #534, #506, #505, #541), the other two for unrelated reasons (#511, #547). The standard closing text: "the level of LLM assistance used in the development of this application significantly exceeds our acceptable thresholds."
- **Dominant: 2 of 2 rejected**, one by Izzy personally (#504, "goes beyond what our policy permits"); the third (#556) is still open.
- **Moderate: 14 of 22 closed issues rejected on the same boilerplate**; the few "accepted-looking" classifications in my keyword pass did not survive manual reading (for example #523 was rejected for the Smry.ai ChatGPT integration, #543 was a debug-signed withdrawal, #545 was rejected on LLM use plus proprietary libraries). The remainder are either withdrawn/stalled threads or pending questions; I verified no Moderate acceptance in the sample.
- The only clean acceptance in the sample (#546) disclosed AI use for "git setup and configs", that is, non-code artifacts.
- **They verify claims.** #517 claimed "None" and Izzy replied that "extensively using Claude" while stating no LLM was used "isn't exactly gaining you points"; #542 hid boilerplate-level use and was called out and rejected. Reviewers demonstrably read commit history and code style.

Where does Anti-Vocale sit? Our README carries an AI-assistance disclosure with a badge, CONTRIBUTING discloses it, and every commit since 2026-08-29 carries an `Assisted-by: Claude` trailer; the development model is agent-executed tasks (TASK-254 through TASK-436 and beyond) with maintainer review. The honest dropdown answer is "Substantial, used throughout development". Anything lower would be a detectable lie in a tracker where detection has already happened to others. Note also #554 (Bloom, an on-device, no-cloud voice app) was closed with the flat statement "We do not allow ai apps"; an app whose README leads with on-device AI models should expect that reading of the policy regardless of the nuance that we run local inference with openly licensed models and integrate no cloud AI service.

### 5. F-Droid overlap: explicitly permitted, dual listing is a supported state

The premise that IoD excludes or drops F-Droid apps is outdated. The archived README and the current FAQ both state: "becoming available in another F-Droid Repo (e.g. at F-Droid) no longer means it will be 'automatically removed' here. If the size of its APK files stays well inside the limits outlined above, it will usually be kept" [1][6]. The FAQ has a dedicated section "Why are some apps available in both repos?" whose first reason is that IoD updates faster than F-Droid builds, so dual-listed apps serve "early birds" [6]. NetGuard is a live dual-listed example; its IoD metadata even carries `DoubleList: keep` with a reference to the decision issue [10]. The submission template asks for "Link to app in another app store (e.g., Google Play, F-Droid)" as an optional field, with no restriction [7]. So overlap is not a blocker; it is at most a "why do you want this" question.

### 6. Signature and upgrade path: better than the task premise assumes

The task assumed an F-Droid versus IoD signature conflict forcing a reinstall. That is the generic case (IoD FAQ footnote: cross-updates from F-Droid "are not possible" *unless* the app was built reproducibly [6]), but it is not ours. Our F-Droid listing is verified reproducible: every current version on the package page states "It is built and signed by the original developer, and guaranteed to correspond to this source tarball" [12], and the recipe's per-ABI builds each carry a `binary:` line pointing at our release-signed GitHub APKs [9]. That means the APK F-Droid distributes and the APK IoD would mirror are byte-identical, developer-signed artifacts: same package, same signature, same versionCodes (391/392/394 style per-ABI codes). Users could move between F-Droid and IoD (or direct GitHub installs) in both directions without a reinstall; IoD additionally pins signatures (`AllowedAPKSigningKeys` in their metadata [10]), and our release key is stable. One operational detail: our releases also carry debug and test-signed APKs as assets alongside the release ones [8]; IoD's key pinning handles picking the right asset, but expect a reviewer remark about the asset zoo.

### 7. Process: issue template, fast turnaround, Izzy's preferences

Submission today is one Codeberg issue on `IzzyOnDroid/repodata` using the "App Inclusion Request" template (title `[AppRequest] <name>`, labels `app-request`, `needs/apk-scan`) [7][13]. The flow: APK scan by their infrastructure, community review (VirusTotal, network monitoring on a test device, Fastlane check, tag/versionName match), optionally `needs/on-device-testing`, then Izzy adds the metadata on his side [5][14]. Decisions in the sample were fast: same day to two days for both accepts and rejects. Post-acceptance, updates are fully automatic: a daily updater (19:25 CE(S)T, sync around 20:00) picks up tagged releases with attached APKs "within 24 hours"; dormant repos drop to a monthly cycle [6][15]. Preferences visible in the record: transparency about AI use is treated as a proxy for good faith (#542); APKs must actually be attached to the matching tag (#548); size exceptions need a well-reasoned case; there is a documented path of temporary update-disable before removal for policy violations [4].

### 8. The speed benefit, quantified for us today

IoD FAQ: F-Droid updates "usually within 3-5 days", IoD "usually within 24h" [6]. Our live state on 2026-09-06: latest tag v1.11.3 with APKs attached [8]; fdroiddata master recipe already at 1.11.2 (code 404) [9]; the F-Droid binary index still suggests 1.11.1 (code 394, added 2026-09-03) [12][16]. So F-Droid users are currently two releases behind while an IoD listing would have shipped each within a day. The benefit is real; it is just unreachable through the two blockers above.

## Verdict and recommendation

**Should we submit? No. Can we? Not under the current policy in any honest form.** The AI-assistance clause plus its mandatory disclosure and demonstrated enforcement (all 11 Substantial-level requests in the last two weeks rejected, false "None" claims caught by reading commit history) makes rejection near-certain for a repo that advertises its AI-assisted development in the README and in every commit trailer; and the 30 MB cap restricts any listing to armeabi-v7a only, which excludes the majority of modern devices. A submission would buy a public rejection citing our development model and nothing else.

Cost/benefit, honestly: the upside (about 2 to 4 days faster updates for a subset of IoD users on 32-bit-capable devices) does not justify (a) a guaranteed policy fight we start by losing, (b) burning reviewer goodwill in a tracker where our name would sit next to "level of LLM assistance significantly exceeds thresholds", and (c) maintaining a second store presence whose per-version artifacts must stay signature-stable. Users who want same-day updates can already install the identical, dev-signed per-ABI APKs from our GitHub Releases page and later cross-upgrade from F-Droid without a reinstall (verified in Finding 6); the F-Droid lag is a fact worth one line in FAQ.md, not a second app store.

Re-evaluate only if: IoD changes the AI policy (it is recent and enforcement is currently at its strictest), or the FAQ's promised size-limit increase lands and arm64 lands under the new cap (unlikely; the sherpa-onnx `.so` payloads dominate our APK size). If it ever becomes viable, the submission is one Codeberg issue using the template, attaching nothing (they scan the GitHub release), with Apache-2.0 as the license, Multimedia as the category, and the size exception case argued up front for arm64.

## Confidence Assessment

- HIGH: current requirements, the 30 MB per-APK and per-app limits, dual-listing tolerance, submission channel (Codeberg issue, PRs for new apps not yet open), updater cadence, and F-Droid versus IoD update-speed claims (all from live pages fetched 2026-09-06).
- HIGH: our APK sizes, per-ABI, from the v1.11.3 release API; F-Droid's "built and signed by the original developer" reproducible-build state from the live package page; F-Droid index lag (1.11.1 served, 1.11.3 tagged).
- HIGH: AI-policy enforcement pattern (50-issue census with bodies and closing comments; every Substantial and Dominant disclosure rejected; two dishonest-disclosure catches).
- MEDIUM: exact accept/reject ratio at Moderate levels and the "typical review time" (same-day to two-day decisions observed in a two-week window; longer queues may exist outside it).
- UNVERIFIED: whether IoD reviewers would class on-device ASR model downloads under the "additional binaries" rule despite them being non-executable data (no recorded precedent found in the sampled issues; the rule's text targets executables and self-updaters, and no sampled rejection cited model downloads).

## Sources

1. Archived GitLab README, IzzyOnDroid/repo: https://gitlab.com/IzzyOnDroid/repo (fetched raw 2026-09-06)
2. IzzyOnDroid website and docs index: https://izzyondroid.org/docs/ (sitemap enumerated)
3. repodata README (Codeberg), incl. "PRs to add new apps" checklist: https://codeberg.org/IzzyOnDroid/repodata
4. App Inclusion Policy incl. AI policy section: https://izzyondroid.org/docs/general/AppInclusionPolicy/
5. New app inclusions (reviewer guide, 30 MB quote): https://izzyondroid.org/contributing/NewAppInclusions/
6. FAQ (F-Droid comparison table, dual-listing section, size-limit rationale, updater cadence, cross-update footnotes): https://izzyondroid.org/faq/
7. Submission issue template (raw YAML, mandatory AI dropdown): https://codeberg.org/IzzyOnDroid/repodata/raw/branch/main/.forgejo/issue_template/app-inclusion-request.yaml
8. Our v1.11.3 release assets and sizes: https://api.github.com/repos/RisorseArtificiali/anti-vocale/releases/tags/v1.11.3 (page: https://github.com/RisorseArtificiali/anti-vocale/releases/tag/v1.11.3)
9. F-Droid recipe (License: Apache-2.0, per-ABI `binary:` lines, CurrentVersion 1.11.2/404): https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/com.antivocale.app.yml
10. IoD metadata examples, NetGuard (`DoubleList: keep`, `AllowedAPKSigningKeys`) and PermissionManagerX (v-prefixed tags, `AutoUpdateMode: Version %v-foss`): https://codeberg.org/IzzyOnDroid/repodata/src/branch/main/metadata/eu.faircode.netguard.yml and .../com.mirfatif.permissionmanagerx.yml
11. PermissionManagerX GitHub tags (v-prefixed): https://api.github.com/repos/mirfatif/PermissionManagerX/tags
12. F-Droid package page ("built and signed by the original developer", 1.11.1 added 2026-09-03): https://f-droid.org/en/packages/com.antivocale.app/
13. Issue tracker: https://codeberg.org/IzzyOnDroid/repodata/issues
14. Issue outcomes cited: #548, #547, #546, #543, #542, #540, #536, #525, #523, #517, #506, #505, #504 (bodies and comments via https://codeberg.org/api/v1/repos/IzzyOnDroid/repodata/issues/...  on 2026-09-06)
15. Service schedules (updater 19:25 CE(S)T daily, RB builders): https://izzyondroid.org/about/resources/schedules/
16. F-Droid index API (suggested 1.11.1 / 394): https://f-droid.org/api/v1/packages/com.antivocale.app
