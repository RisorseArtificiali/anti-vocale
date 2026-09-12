# Research Report: Major Telegram and WhatsApp clients deserving source-app census (TASK-433)

**Date**: 2026-09-02
**Depth**: standard
**Confidence**: HIGH for the Tier 1 package ids (verified from client sources or an authoritative compatibility table), MEDIUM for Tier 2

## Executive Summary

Yes: beyond AyuGram, seven Android Telegram clients clearly deserve census (exteraGram, Nekogram, Telegram-X, Plus Messenger, OwlGram, iMe, and the already-verified AyuGram), all with trustworthy package ids. One pleasant accident: Nagram ships with the OFFICIAL package id `org.telegram.messenger`, so its users are already grouped correctly. WhatsApp mods (GB/ FM/ Yo/ Plus) have massive regional userbases but closed sources, package churn, and ToS-breaking status: recommend deferring them until real share-source evidence appears in user reports.

## Findings

### Tier 1: add to commonNames (and notification family grouping)

| Client | Package | Verification | Popularity signal |
|---|---|---|---|
| Nekogram | `tw.nekomimi.nekogram` (+ `.beta` flavor suffix) | gradle.properties of Nekogram/Nekogram (read 2026-09-02) | GitHub 3849 stars; RU 2026 roundup calls it one of the sanest clients [1] [3] |
| exteraGram | `com.exteragram.messenger` (+ `.beta`) | Killergram compatibility table [2] | 1440 stars; base of AyuGram [4] |
| Telegram X (Challegram) | `org.thunderdog.challegram` | Killergram table [2] | Slant top-3 Android client [5] |
| Plus Messenger | `org.telegram.plus` | Killergram table [2] | Slant top pick; ALREADY matched by the notification `startsWith("org.telegram")` branch, needs only the commonNames entry |
| OwlGram | `it.owlgram.android` | Killergram table [2] | LibHunt-compared, mid-tier [6] |
| iMe Messenger | `com.iMe.android` | Killergram table [2] | RU comparison article pairs it with Swiftgram [7] |
| AyuGram | `com.radolyn.ayugram` | gradle.properties of AyuGram/AyuGram4A (2026-09-02, prior research) | 4PDA thread, trashbox reviews [8] |

The Killergram table is a functional authority: an Xposed hook module breaks if the package ids are wrong, and it also lists official variants (`org.telegram.messenger.web`, `.beta`) worth adding as exact map keys since the current commonNames has only the base id [2].

### Covered by accident

Nagram (NextAlone/Nagram, 3215 stars, the maintained Nekogram X lineage) sets `APP_PACKAGE=org.telegram.messenger`: it installs over official Telegram and is therefore already grouped as "Telegram" by both our map and the notification prefix branch (verified from its gradle.properties, 2026-09-02). No entry needed.

### Tier 2: borderline, verify before adding

Graph Messenger (`ir.ilmili.telegraph`, closed source, popular in Iran), BGram (`org.telegram.BifToGram`), the Forkgram family (`org.forkgram.messenger`, `org.forkgram.classic`, `org.forkclient.messenger`), Swiftgram (listed in the RU top-5 mods roundup [1] but its repo id could not be located under the obvious names), Nicegram, Telegram-FOSS (named a "calm option" alongside Nekogram [3]). All from the same Killergram table except Swiftgram/Nicegram/FOSS which need source verification.

### WhatsApp mods: defer (reason updated after artifact hunt)

GBWhatsApp (`com.gbwhatsapp`), YoWhatsApp (`com.yowhatsapp`), FMWhatsApp (`com.fmwhatsapp`), WhatsApp Plus have large userbases in India, Brazil, and MENA. A dedicated hunt for runtime artifacts (stack traces, logcat dumps) found NOTHING indexed: mod support happens in Telegram channels that search engines do not index as logs, and the 13 GitHub issues mentioning `com.gbwhatsapp` are tooling artifacts (an APK extractor failing on a mod APK, a theming engine listing it), not crashes. However, the verification-rule objection DISSOLVES on three other artifact classes:

1. The distributors' own download metadata (fouadmods.net, fmmods.xyz): the APKs would not install as advertised with wrong ids. They document `com.gbwhatsapp`, `com.fmwhatsapp`, `com.yowhatsapp` [9] [10].
2. The multi-slot scheme, documented by distributor channels: numbered parallel packages for multi-number setups (`com.gbwhatsapp3` as OGWhatsApp, `com.yowhatsapp2`, and so on, unbounded) [11].
3. A national CERT advisory (MyCERT MA-951, WhatsappPink malware) listing mod package ids taken from REAL MALWARE SAMPLES: proof the namespace is heavily squatted by hostile fakes [12].

Two decision-relevant discoveries:

- Some mod builds ship with the OFFICIAL package: FouadWA installs as `com.whatsapp`, and WhatsApp Plus distributes a build whose package is WHATSAPP [11] [13]. Those users are already grouped correctly by our existing entry, the same accident as Nagram on the Telegram side.
- The squatting cuts both ways: a prefix entry `com.gbwhatsapp*` would label malware clones as "GB WhatsApp". For a display-name grouping that is cosmetically wrong but harmless (no trust is granted); still, it is a reason to keep the entries prefix-based and low-stakes.

Updated recommendation: keep the mods out of Tier 1, but the deferral reason is no longer "cannot verify"; it is "slot namespace unbounded and squatted". Add the three base prefixes (`com.gbwhatsapp`, `com.yowhatsapp`, `com.fmwhatsapp`) as family-prefix entries on the first real user report naming one as a share source.

### Implementation note for TASK-433

Several forks ship flavor suffixes (`.beta`, `.web`) on the same base id. Prefer a prefix/family table (the shape `ResultNotificationFactory` already uses) over exact-key matching, so `tw.nekomimi.nekogram.beta` groups without its own entry, and drive BOTH the History grouping and the notification channel from that single table instead of keeping two parallel mechanisms.

## Confidence Assessment

- HIGH: Tier 1 package ids (two read from gradle.properties, five from the Killergram compatibility table); Nagram's official-id accident (source-read).
- MEDIUM: Tier 2 entries (single secondary source each); Swiftgram and Nicegram existence-and-popularity confirmed, package unverified.
- NOT ASSESSED: actual share-source frequency from our users (no telemetry in the F-Droid build; the Play build's analytics is out of scope for this decision).

## Sources

1. https://t-j.ru/list/telegram-mods/ : RU 2026 roundup of the 5 best Telegram mods (Plus, Nekogram, exteraGram, Swiftgram, Nicegram).
2. https://github.com/Xposed-Modules-Repo/com.shatyuka.killergram : Xposed module compatibility table with exact package ids for 15+ Telegram clients.
3. https://www.securitylab.ru/analytics/570086.php : 2026 RU analysis of third-party Telegram clients (Nekogram and Telegram FOSS as the calm options).
4. https://github.com/exteraSquad/exteraGram : exteraGram repo, 1440 stars.
5. https://www.slant.co/topics/6317/~telegram-client-for-android : Slant ranking (Plus, Telegram, Challegram as top picks).
6. https://www.libhunt.com/compare-telegram-x-vs-owlgram : OwlGram mid-tier presence.
7. https://appleoutsider.ru/knowledge_functions/tpost/248r4o7ao1-sravnenie-ime-i-swiftgram-alternativnih : iMe vs Swiftgram comparison.
8. https://4pda.to/forum/index.php?showtopic=1072810 : AyuGram 4PDA thread (community size).
9. https://t.me/s/FouadMODS/366 : FouadMODS channel documenting WhatsApp mod package names.
10. https://fouadmods.net/gb-whatsapp/ , https://fouadmods.net/fouad-whatsapp/ , https://fmmods.xyz/gbwhatsapp/ : distributor download metadata with package ids (GB com.gbwhatsapp, FM com.fmwhatsapp, Yo com.yowhatsapp; PT page documents FouadWA installing as com.whatsapp).
11. https://t.me/GBWhatsAapp/416 and https://t.me/YoWApp/1235 : distributor channels documenting the numbered slot scheme (com.gbwhatsapp3 as OGWhatsApp, com.yowhatsapp2, multi-slot installs next to com.whatsapp).
12. https://www.mycert.org.my/portal/advisory?id=MA-951.062023 : MyCERT advisory on WhatsappPink malware sampling the mod package namespace.
13. https://t.me/whatsapplus/1327 : WhatsApp Plus build shipping with the official package name.
14. Direct source reads (2026-09-02): Nekogram/Nekogram and NextAlone/Nagram gradle.properties via GitHub contents API.
