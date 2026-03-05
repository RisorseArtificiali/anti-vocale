---
id: TASK-15
title: Add ModelPreloadReceiver for external model loading
status: Done
assignee: []
created_date: '2026-03-01 08:25'
updated_date: '2026-03-04 18:25'
labels:
  - broadcast
  - automation
  - tasker
dependencies: []
ordinal: 29000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
## Overview

Allow external apps (Tasker, automation apps) to trigger model preloading via broadcast intent.

## Usage

**Via ADB:**
```
adb shell am broadcast -a com.localai.bridge.PRELOAD_MODEL
```

**Via Tasker:**
- Action: Send Intent
- Action: com.localai.bridge.PRELOAD_MODEL

## Purpose

Reduces latency for first inference request by preloading the model before sharing content to the app.

## Files

- `ModelPreloadReceiver.kt` - BroadcastReceiver implementation
- `AndroidManifest.xml` - Receiver registration
<!-- SECTION:DESCRIPTION:END -->
