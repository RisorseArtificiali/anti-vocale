---
id: TASK-29
title: Migrate to AndroidX Per-App Language API
status: Done
assignee:
  - Claude
created_date: '2026-03-02 11:00'
updated_date: '2026-03-04 18:25'
labels:
  - enhancement
  - i18n
  - android-13
  - tech-debt
dependencies: []
priority: medium
ordinal: 7000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Migrate from the legacy `attachBaseContext()` + `createConfigurationContext()` approach to the modern AndroidX Per-App Language API (`AppCompatDelegate.setApplicationLocales()`).

**Benefits:**
- Immediate language change (no restart required)
- System settings integration on Android 13+
- Automatic preference persistence
- Reduced code complexity (~80 lines less)
- Future-proof implementation

**Implementation Steps:**

1. Create `res/xml/locales_config.xml` with en/it locales
2. Add `android:localeConfig` to AndroidManifest.xml
3. Add `AppLocalesMetadataHolderService` with `autoStoreLocales` for backward compatibility
4. Update `SettingsViewModel.saveLanguagePreference()` to use `AppCompatDelegate.setApplicationLocales()`
5. Add one-time migration from DataStore to new API
6. Remove `LocaleHelper.kt` (no longer needed)
7. Remove `attachBaseContext` override from `MainActivity`
8. Remove `languagePreference` from `PreferencesManager` (AndroidX handles storage)
9. Update UI text (remove "restart required" message)

**Files to Modify:**
- `AndroidManifest.xml` - Add localeConfig and autoStoreLocales service
- `SettingsViewModel.kt` - Use AndroidX API
- `SettingsTab.kt` - Update language description text
- `MainActivity.kt` - Remove attachBaseContext override
- `PreferencesManager.kt` - Remove language preference

**Files to Create:**
- `res/xml/locales_config.xml`

**Files to Delete:**
- `LocaleHelper.kt`

**References:**
- Research report in `claudedocs/research_edge_gallery_huggingface_auth_2026-03-01.md` (or create new research doc)
- https://developer.android.com/guide/topics/resources/app-languages
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 App appears in Android 13+ system language settings (Settings > Apps > LocalAI Bridge > Language)
- [ ] #2 Changing language in app applies immediately without restart
- [ ] #3 Changing language in system settings syncs with in-app language selector
- [ ] #4 Language preference persists across app restarts
- [ ] #5 LocaleHelper.kt is removed from codebase
- [ ] #6 attachBaseContext override removed from MainActivity
- [ ] #7 UI no longer shows 'restart required' message
- [ ] #8 Existing users' language preference migrated on first run after update
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
# Implementation Plan: Migrate to AndroidX Per-App Language API

## Overview
Replace legacy `LocaleHelper` + `attachBaseContext` approach with AndroidX Per-App Language API for immediate language switching and system settings integration.

## Phase 1: Configuration Files

### 1.1 Create `res/xml/locales_config.xml`
```xml
<?xml version="1.0" encoding="utf-8"?>
<locale-config xmlns:android="http://schemas.android.com/apk/res/android">
    <locale android:name="en" />
    <locale android:name="it" />
</locale-config>
```

### 1.2 Update AndroidManifest.xml
- Add `android:localeConfig="@xml/locales_config"` to `<application>`
- Add `AppLocalesMetadataHolderService` with `autoStoreLocales=true` for backward compatibility

## Phase 2: Create LocaleManager (New)

**File**: `util/LocaleManager.kt`
- `setLocale(code)` → `AppCompatDelegate.setApplicationLocales()`
- `getCurrentLocale()` → Returns current or null for system
- `getCurrentLocaleCode()` → Returns "system", "en", or "it"

## Phase 3: Update SettingsViewModel
- Replace DataStore `languagePreference` with `MutableStateFlow`
- `saveLanguagePreference()` calls `LocaleManager.setLocale()`

## Phase 4: Update SettingsTab
- Change `viewModel.languagePreference` → `viewModel.currentLanguage`

## Phase 5: Migration Logic (BridgeApplication)
- Add `migrateLanguagePreference()` in `onCreate()`
- One-time: Read DataStore → Apply via LocaleManager → Set flag

## Phase 6: Clean MainActivity
- **DELETE** `attachBaseContext()` override

## Phase 7: Clean PreferencesManager
- Remove `LANGUAGE_PREFERENCE`, `languagePreference` flow, `saveLanguagePreference()`

## Phase 8: Delete LocaleHelper
- **DELETE** `util/LocaleHelper.kt`

---

## Files Changed

| Action | File |
|--------|------|
| CREATE | `res/xml/locales_config.xml` |
| CREATE | `util/LocaleManager.kt` |
| MODIFY | `AndroidManifest.xml` |
| MODIFY | `MainActivity.kt` |
| MODIFY | `BridgeApplication.kt` |
| MODIFY | `SettingsViewModel.kt` |
| MODIFY | `SettingsTab.kt` |
| MODIFY | `PreferencesManager.kt` |
| DELETE | `util/LocaleHelper.kt` |

## Verification
```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 25.0.2-tem && sdk use gradle 9.3.1 && gradle assembleDebug
```

Test: Fresh install, language change (immediate), restart persistence, migration for existing users.
<!-- SECTION:PLAN:END -->
