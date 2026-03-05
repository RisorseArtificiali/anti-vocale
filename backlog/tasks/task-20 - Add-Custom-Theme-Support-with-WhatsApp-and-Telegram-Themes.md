---
id: TASK-20
title: Add Custom Theme Support with WhatsApp and Telegram Themes
status: Done
assignee:
  - Claude
created_date: '2026-03-01 19:44'
updated_date: '2026-03-04 18:25'
labels:
  - enhancement
  - ui
  - theming
dependencies: []
priority: low
ordinal: 2000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Implement theme support allowing users to choose from different color schemes.

**Themes to include:**
1. **Default** - Current app theme
2. **WhatsApp** - Green color scheme matching WhatsApp's brand
3. **Telegram** - Blue color scheme matching Telegram's brand

**Tasks:**
- Create theme configuration system
- Define color palettes for each theme
- Add theme selector in Settings
- Persist theme preference
- Apply theme throughout the app

**User story:** As a user, I want to personalize the app's appearance with familiar color schemes from messaging apps I use.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 User can select from Default, WhatsApp, and Telegram themes in Settings
- [x] #2 Theme preference is persisted across app restarts
- [x] #3 All UI components (cards, buttons, text, icons) use the selected theme colors
- [x] #4 Theme changes apply immediately without app restart
- [x] #5 WhatsApp theme uses green color palette matching WhatsApp brand
- [x] #6 Telegram theme uses blue color palette matching Telegram brand
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
# Implementation Plan: Custom Theme Support

## Architecture Overview

The app uses **Material3 theming** with a well-structured, single-theme setup. Adding WhatsApp and Telegram themes requires extending the existing `Theme.kt` and adding a preference system.

## Current State Analysis

### Theme.kt Structure
- Single `DarkColorScheme` defined with indigo colors
- `LocalAITaskerBridgeTheme` composable always uses dark theme
- No dynamic color support (intentionally disabled for branding)
- All UI components properly use `MaterialTheme.colorScheme.*` (no hardcoded colors!)

### Color Usage Patterns
```
primary → primaryContainer → onPrimary → onPrimaryContainer
secondary → secondaryContainer → onSecondary → onSecondaryContainer
background → onBackground
surface → onSurface → surfaceVariant → onSurfaceVariant
tertiary (used for OAuth button in Settings)
error → onError
```

## Files to Modify

### 1. `ui/theme/Theme.kt` - Add Theme Definitions
- Create `ThemeType` enum (DEFAULT, WHATSAPP, TELEGRAM)
- Define `WhatsAppDarkColorScheme` with green palette
- Define `TelegramDarkColorScheme` with blue palette
- Modify `LocalAITaskerBridgeTheme` to accept theme parameter
- Add `ColorScheme` selection logic based on theme type

### 2. `data/PreferencesManager.kt` - Add Theme Preference
- Add `THEME_PREFERENCE` key (stringPreferencesKey)
- Add `getThemePreference(): Flow<String>` method
- Add `saveThemePreference(theme: String)` method

### 3. `ui/viewmodel/SettingsViewModel.kt` - Add Theme State
- Add `currentTheme: StateFlow<ThemeType>` 
- Add `setTheme(theme: ThemeType)` method
- Initialize theme from preferences on startup

### 4. `ui/tabs/SettingsTab.kt` - Add Theme Selection UI
- Add Theme Selection Card after Language card
- Use radio buttons for Default/WhatsApp/Telegram options
- Show theme icons/colors for visual feedback

### 5. `MainActivity.kt` - Apply Dynamic Theme
- Collect theme preference from ViewModel
- Pass theme to `LocalAITaskerBridgeTheme`

## Color Palette Definitions

### WhatsApp Theme (Green)
```
primary: #25D366 (WhatsApp green)
primaryContainer: #128C7E (darker green)
secondary: #075E54 (dark teal)
background: #0B141A (WhatsApp dark)
surface: #111B21 (WhatsApp surface)
```

### Telegram Theme (Blue)
```
primary: #0088CC (Telegram blue)
primaryContainer: #0077B5 (darker blue)
secondary: #2AABEE (Telegram light blue)
background: #17212B (Telegram dark)
surface: #232E3C (Telegram surface)
```

## Implementation Order

1. **Theme.kt** - Define color schemes and theme enum
2. **PreferencesManager.kt** - Add persistence
3. **SettingsViewModel.kt** - Add state management
4. **MainActivity.kt** - Connect theme to app root
5. **SettingsTab.kt** - Add selection UI
6. **Testing** - Verify all screens apply theme correctly

## Key Considerations

- All UI components already use Material3 color scheme (no migration needed)
- Keep dark theme only (no light theme support required)
- Persist preference in DataStore for app restart survival
- Apply theme immediately on selection (no restart required)
<!-- SECTION:PLAN:END -->
