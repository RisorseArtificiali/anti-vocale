# Reply to u/SusejLegend (r/fossdroid) — signed APK fix

Paste the block below as a reply to their comment about the Oppo Reno 12
install failure ("not compatible or not valid").

---

Good catch, and sorry about that — that was my mistake. The APK I had attached to the GitHub release was **unsigned**, which a lot of OEM package installers (ColorOS included) reject as "not compatible or not valid" even though the file itself is fine.

It's fixed now. The v1.8.1 release has a properly **signed** APK:

👉 https://github.com/RisorseArtificiali/anti-vocale/releases/download/v1.8.1/app-fdroid-release.apk

Download that one and it should install cleanly on the Reno 12. It's signed with the project's release key (APK Signature Scheme v2, SHA-256 `8e72e114…ca5a8b5a3`) — the same key the Play Store build uses.

Two notes:
- This is the Firebase-free build (no Crashlytics/Analytics), so it's the right one for a degoogled setup.
- F-Droid inclusion is still pending review, so for now GitHub Releases is the sideload source.

If it still won't install after this, grab a quick logcat (`adb logcat *:E` while you tap install) and paste it back — I'd like to rule out anything ColorOS-specific on my end. Thanks for reporting it.
