---
id: TASK-49
title: Update documentation with new app name
status: Done
assignee: []
created_date: '2026-03-05 15:28'
updated_date: '2026-03-05 15:33'
labels:
  - branding
  - documentation
milestone: App Rebrand
dependencies:
  - TASK-47
priority: medium
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Update all documentation files to reference "Anti-Vocale" instead of "LocalAI Bridge" or "Voice Message Reader".

**FILES TO MODIFY:**
- `CLAUDE.md` - Update title and descriptions
- `docs/BUILD.md` - Update any app references
- `.serena/memories/` - Update project memory files
- `MEMORY.md` - Update auto-memory references

**IMPLEMENTATION:**
1. Search for "LocalAI Bridge" and "Voice Message Reader" in documentation
2. Replace with "Anti-Vocale"
3. Ensure consistency across all docs
4. Commit documentation updates
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 [ ] CLAUDE.md updated with new app name
- [x] #2 [ ] docs/BUILD.md updated
- [x] #3 [ ] Memory files updated
- [x] #4 [ ] No "LocalAI Bridge" or "Voice Message Reader" in docs
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Code compiles without errors or warnings
- [ ] #2 Feature tested on physical device or emulator
- [ ] #3 No regressions in existing functionality
- [ ] #4 Edge cases handled appropriately
- [ ] #5 UI follows Material Design guidelines
<!-- DOD:END -->
