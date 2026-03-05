---
id: TASK-14
title: Add Settings tab with keep-alive timeout configuration
status: Done
assignee: []
created_date: '2026-03-01 08:25'
updated_date: '2026-03-04 18:25'
labels:
  - settings
  - ui
  - keep-alive
dependencies: []
ordinal: 30000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
## Overview

Add a Settings tab to the app with configurable options.

## Implemented Features

- Settings tab in bottom navigation
- Model status display (loaded/not loaded, remaining time)
- Keep-alive timeout configuration (1min, 5min, 15min, 30min, 1hr)
- Auto-apply timeout changes to loaded model
- Preload instructions for Tasker/ADB

## Files

- `SettingsTab.kt` - Settings UI
- `SettingsViewModel.kt` - Settings state management
- `MainScreen.kt` - Added Settings tab
- `LlmManager.kt` - Made resetKeepAliveTimer() public
- `AppContainer.kt` - Added applicationContext
<!-- SECTION:DESCRIPTION:END -->
