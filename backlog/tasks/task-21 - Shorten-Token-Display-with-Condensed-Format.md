---
id: task-21
title: Shorten Token Display with Condensed Format
status: Done
assignee: []
created_date: '2026-03-01 19:44'
updated_date: '2026-03-01 19:53'
labels:
  - bug
  - ui
  - settings
dependencies: []
priority: low
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
The token input placeholder currently displays the obfuscated token at full length, which takes up too much space.

**Current behavior:** Shows full-length obfuscated token like `sk-abc••••••••••••••••••••xyz` (maintains original length)

**Proposed solution:**
- Display a condensed format showing only beginning and end: `sk-abc...xyz`
- Show first ~6-8 characters and last ~3-4 characters in clear text
- Use `...` or similar to indicate truncated middle section
- **Important:** Only condense the DISPLAY - store the full token unchanged

**User story:** As a user, I want to see a condensed version of my token for validation without it taking up excessive space.

**Example:** `hf_xxxxxxxxxxxxxxxxxxxx` → `hf_xxxx...xxxx`
<!-- SECTION:DESCRIPTION:END -->
