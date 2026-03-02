---
id: task-17.6
title: Add Deep Link Intent Filter to AndroidManifest
status: Done
assignee: []
created_date: '2026-03-01 12:18'
updated_date: '2026-03-01 13:12'
labels:
  - android
  - manifest
  - deep-link
dependencies: []
parent_task_id: task-17
priority: high
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Configure AndroidManifest.xml to handle the OAuth redirect deep link back to the app.

**Implementation:**

```xml
<!-- AndroidManifest.xml -->
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:launchMode="singleTop"
    android:windowSoftInputMode="adjustResize">
    
    <!-- Existing launcher intent-filter -->
    
    <!-- Deep link for OAuth callback -->
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="com.localai.bridge" />
    </intent-filter>
</activity>
```

Also handle the callback in MainActivity's `onNewIntent()` method.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Deep link intent filter added to MainActivity
- [ ] #2 Scheme matches manifestPlaceholders and redirect URI
- [ ] #3 onNewIntent() handles OAuth callback
- [ ] #4 Activity launchMode set to singleTop
<!-- AC:END -->
