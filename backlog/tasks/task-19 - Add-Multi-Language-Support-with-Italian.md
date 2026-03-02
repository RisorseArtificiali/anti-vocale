---
id: task-19
title: Add Multi-Language Support with Italian
status: Done
assignee:
  - Claude
created_date: '2026-03-01 19:44'
updated_date: '2026-03-02 09:58'
labels:
  - enhancement
  - i18n
  - ui
dependencies: []
priority: medium
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Implement internationalization (i18n) support for the app, starting with Italian language.

**Tasks:**
- Set up Android string resources for multiple languages
- Extract all hardcoded UI strings to resource files
- Create Italian translation (values-it/strings.xml)
- Add language selector in Settings
- Persist language preference

**User story:** As an Italian user, I want to use the app in my native language for a better experience.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 All hardcoded UI strings are extracted to resource files
- [ ] #2 Italian translation exists in values-it/strings.xml
- [ ] #3 Language selector appears in Settings tab with 3 options: System Default, English, Italiano
- [ ] #4 Selecting a language immediately updates the UI
- [ ] #5 Language preference persists across app restarts
- [ ] #6 App respects system locale when 'System Default' is selected

- [ ] #7 Tab labels (Model, Logs, Settings) are translated in Italian
- [ ] #8 Logs tab labels are translated in Italian
- [ ] #9 Model descriptions in Model tab are translated in Italian
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
### Phase 1: Extract Hardcoded Strings
- Extract strings from `ModelTab.kt` (dialog titles, status text, buttons, info labels)
- Extract strings from `SettingsTab.kt` (status indicators, auth strings, button labels)
- Add all to `values/strings.xml` with naming convention (status_*, action_*, label_*, dialog_*, setting_*)

### Phase 2: Create Italian Translation
- Create `values-it/strings.xml` with Italian translations
- Maintain exact same resource IDs

### Phase 3: Add Language Preference Storage
**PreferencesManager.kt:**
- Add `LANGUAGE_KEY = stringPreferencesKey("language_preference")`
- Add `languageFlow: Flow<String>` (default: "system")
- Add `saveLanguage(language: String)`

### Phase 4: Add Language Selector UI
**SettingsViewModel.kt:**
- Add `languageOptions = listOf("system", "en", "it")`
- Add `_selectedLanguage` StateFlow
- Load from PreferencesManager on init

**SettingsTab.kt:**
- Add "Language" card with radio buttons
- Options: "System Default", "English", "Italiano"
- Follow existing timeout selector pattern

### Phase 5: Runtime Locale Switching
**Create LocaleHelper.kt:**
- `setLocale(context, language)` - apply locale
- Handle "system" option to use system default

**Update MainActivity.kt:**
- Override `attachBaseContext()` to apply locale
- Observe language changes and recreate activity

### Files to Modify
| File | Action |
|------|--------|
| `values/strings.xml` | Add extracted strings |
| `values-it/strings.xml` | CREATE - Italian translations |
| `ModelTab.kt` | Replace hardcoded strings |
| `SettingsTab.kt` | Replace strings, add language selector |
| `PreferencesManager.kt` | Add language preference |
| `SettingsViewModel.kt` | Add language state/options |
| `LocaleHelper.kt` | CREATE - Locale utility |
| `MainActivity.kt` | Apply locale in attachBaseContext |
<!-- SECTION:NOTES:END -->

### Phase 6: Fix Remaining Translations

**Tab Labels (MainScreen.kt or navigation):**

- Find where tabs are defined (Model, Logs, Settings)

- Replace hardcoded strings with stringResource()

**Logs Tab (LogsTab.kt):**

- Find all hardcoded labels in Logs tab

- Add to strings.xml and translate to Italian

**Model Descriptions (ModelDownloader.kt or ModelVariant):**

- The model variant descriptions are defined in code

- Either move descriptions to string resources OR accept they remain in English (technical content)

- Alternative: Add descriptionKey to ModelVariant enum
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
["Phase 6 complete - Added model description resource IDs for remaining UI strings. Updated TabItem, MainScreen.kt, LogsTab.kt, and ModelDownloader.kt to use resource IDs for descriptions"]

Phase 6 complete - Added model description resource IDs for remaining UI strings. Updated TabItem (MainScreen.kt, LogsTab.kt, ModelDownloader.kt to use resource IDs for descriptions.
<!-- SECTION:NOTES:END -->
