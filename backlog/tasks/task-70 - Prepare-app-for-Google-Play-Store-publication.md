---
id: TASK-70
title: Prepare app for Google Play Store publication
status: In Progress
assignee:
  - claude
created_date: '2026-03-07 00:02'
updated_date: '2026-03-07 22:29'
labels:
  - release
  - play-store
  - compliance
dependencies: []
references:
  - app/build.gradle.kts
  - app/src/main/AndroidManifest.xml
  - docs/screenshots/
  - README.md
priority: medium
ordinal: 1000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Complete all requirements to get Anti-Vocale accepted on the Google Play Store.

## Current State

- Package: `com.antivocale.app`
- versionCode: 1, versionName: "1.0"
- targetSdk: 34, minSdk: 26
- Release build: minify + shrinkResources + ProGuard configured
- No release signing configured
- No privacy policy
- Custom launcher icon exists (all densities + adaptive)
- App UI in English and Italian

## Google Play Store Requirements

### 1. Developer Account
- [ ] Register for Google Play Developer account ($25 one-time fee)
- [ ] Complete identity verification

### 2. App Signing
- [ ] Generate upload keystore (`keytool -genkey -v -keystore anti-vocale-upload.jks -keyalg RSA -keysize 2048 -validity 10000 -alias upload`)
- [ ] Configure `signingConfigs` in `build.gradle.kts` for release builds
- [ ] Store keystore securely (NOT in git) — use environment variables or `keystore.properties`
- [ ] Opt in to Google Play App Signing (recommended)

### 3. Store Listing Assets
- [ ] **App title**: "Anti-Vocale" (max 30 chars)
- [ ] **Short description**: max 80 chars
- [ ] **Full description**: max 4000 chars — explain offline transcription, supported models, privacy
- [ ] **Feature graphic**: 1024x500 PNG/JPG (required)
- [ ] **App icon**: 512x512 PNG (high-res, required)
- [ ] **Screenshots**: minimum 2, recommended 4-8 per device type (phone)
  - Use current `docs/screenshots/` as base, but crop status bar and use English locale
  - Required sizes: 16:9 or 9:16, min 320px, max 3840px per side
- [ ] **Category**: Tools or Productivity
- [ ] **Content rating**: Complete IARC questionnaire (likely "Everyone")

### 4. Privacy & Compliance
- [ ] **Privacy Policy** (REQUIRED) — host a URL explaining:
  - All data stays on-device, no network requests for transcription
  - No user data collection, no analytics, no ads
  - Audio files are processed locally and not stored
  - HuggingFace OAuth is optional and only used for model downloads
- [ ] **Data Safety section** — declare in Play Console:
  - No data collected
  - No data shared with third parties
  - Audio processing is on-device only
- [ ] **Permissions declaration** — justify each permission:
  - `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` — background transcription
  - `POST_NOTIFICATIONS` — show transcription results
  - `INTERNET` — model downloads only (not for transcription)
  - `READ_EXTERNAL_STORAGE` / `MANAGE_EXTERNAL_STORAGE` — access shared audio files

### 5. Technical Requirements
- [ ] **Target API level** — Google Play requires targetSdk 34+ (already met)
- [ ] **64-bit support** — verify arm64-v8a is included in sherpa-onnx AAR
- [ ] **App Bundle** — build `.aab` instead of `.apk` (`./gradlew bundleRelease`)
- [ ] **ProGuard rules** — verify release build doesn't crash (test sherpa-onnx JNI, LiteRT-LM)
- [ ] **Version code** — increment for each upload (currently 1)
- [ ] **Remove debug logging** — audit for sensitive data in logs

### 6. Testing
- [ ] **Release build test** — install `app-release.aab` on device, verify all features work
- [ ] **Test all model downloads** — Gemma, Parakeet, Whisper variants
- [ ] **Test share integration** — WhatsApp, Telegram, generic share
- [ ] **Test notifications** — copy, share back, auto-copy
- [ ] **Test on multiple devices/API levels** if possible
- [ ] **Crash-free** — no ANRs or crashes in normal usage

### 7. Play Console Setup
- [ ] Create app in Google Play Console
- [ ] Upload AAB to internal testing track first
- [ ] Complete store listing, content rating, pricing (Free)
- [ ] Submit for review
- [ ] Address any review feedback

## Key Risks
- **Permissions**: `MANAGE_EXTERNAL_STORAGE` may trigger extra review scrutiny — consider if `READ_EXTERNAL_STORAGE` + SAF (Storage Access Framework) is sufficient
- **Large downloads**: Models are 400MB-4GB — Play Store reviewers may flag this, ensure clear disclosure in description
- **sherpa-onnx native libs**: ProGuard may strip JNI methods — need thorough release build testing
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Release-signed AAB builds and installs correctly
- [ ] #2 Privacy policy hosted at a public URL
- [ ] #3 Play Store listing complete with screenshots, descriptions, and feature graphic
- [ ] #4 Data Safety section accurately reflects on-device-only processing
- [ ] #5 App passes internal testing track without crashes
- [ ] #6 All permissions justified in Play Console declarations
- [ ] #7 Content rating questionnaire completed
- [ ] #8 App submitted for review
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
## Implementation Plan

### Phase 1: Remove MANAGE_EXTERNAL_STORAGE Permission ✅ COMPLETE
**Reason:** High-scrutiny permission that often triggers Play Store rejection

**Completed Changes:**
- [x] Removed MANAGE_EXTERNAL_STORAGE from AndroidManifest.xml
- [x] Removed USE_GALLERY_MODEL from PendingAction enum in ModelTab.kt
- [x] Removed needsManageStoragePermission() function
- [x] Removed manageStorageLauncher
- [x] Removed Gallery-related UI elements (AI Gallery name display)
- [x] Removed checkForGalleryModels(), refreshGalleryModels(), useGalleryModel() from ModelViewModel.kt
- [x] Removed Gallery state properties from UiState
- [x] Removed findGalleryModel(), isGalleryModelAvailable(), getGalleryModelPath(), listGalleryModels(), copyFromGallery() from ModelDownloader.kt
- [x] Removed getBestAvailableModelPath(), isAnyModelAvailable()
- [x] Removed Gallery-related code from ModelDiscovery.kt
- [x] Removed GALLERY_MODELS_PATH constant and galleryModelName from ModelVariant enum

**Verification:** Build successful with `./gradlew assembleDebug`

### Phase 2: App Signing Configuration ✅ COMPLETE
**Completed Changes:**
- [x] Created keystore.properties.template
- [x] Updated .gitignore to exclude *.jks, *.keystore, keystore.properties
- [x] Added signing configuration to build.gradle.kts
- [x] Configured release buildType to use signing config

**User Action Required:** Generate keystore with:
```bash
keytool -genkey -v -keystore anti-vocale-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias anti-vocale-key
```
Then copy template and fill in actual values in keystore.properties

### Phase 3: Privacy Policy ✅ COMPLETE
- [x] Created docs/PRIVACY_POLICY.md
- [x] Document clearly states no data collection, all processing is on-device

**User Action Required:** Host privacy policy at a public URL (e.g., GitHub Pages)

### Phase 4: Store Listing Assets ✅ COMPLETE
- [x] Created docs/play-store/store-listing.md with:
  - Short description (80 chars)
  - Full description (4000 chars max)
  - Category: Tools
  - Content Rating: Everyone

### Phase 5: Store Graphics (User Action Required)
- [ ] Create feature graphic: 1024x500 PNG/JPG
- [ ] Create store icon: 512x512 PNG
- [ ] Process screenshots: crop status bars, ensure English locale

### Phase 6: Build and Test Release
- [ ] User generates keystore and configures keystore.properties
- [ ] Build release AAB: `./gradlew bundleRelease`
- [ ] Test on device with all features
- [ ] Verify permissions declaration

### Phase 7: Play Console Setup
- [ ] Create app in Play Console
- [ ] Upload assets and AAB
- [ ] Complete Data Safety section (declare: no data collected/shared)
- [ ] Complete Permissions Declaration
- [ ] Submit for review
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
2026-03-07: Removed MANAGE_EXTERNAL_STORAGE permission and all Gallery-related code

2026-03-07: Configured app signing in build.gradle.kts

2026-03-07: Created privacy policy and store listing documents

2026-03-07: Created feature graphic design brief

2026-03-07: Processed all 7 screenshots for Play Store (removed status/nav bars)

2026-03-07: Created screenshot processing scripts (bash and Python versions)
<!-- SECTION:NOTES:END -->
