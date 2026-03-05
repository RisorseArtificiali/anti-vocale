---
id: TASK-50
title: Rename repository directory to anti-vocale
status: In Progress
assignee: []
created_date: '2026-03-05 15:28'
updated_date: '2026-03-05 15:34'
labels:
  - branding
  - git
  - breaking-change
milestone: App Rebrand
dependencies:
  - TASK-47
  - TASK-48
  - TASK-49
priority: low
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Rename the repository directory from `voice_message_reader` to `anti-vocale` to reflect the new app name.

**DECISION MADE:**
- Using `anti-vocale` (kebab-case) - more common for repos

**CONSIDERATIONS:**
- Git has special handling for directory renames
- Remote repository will need to be updated separately
- This should be done LAST after all other renames are complete

**IMPLEMENTATION APPROACH:**
1. **Git mv approach** (preserves history):
   ```bash
   cd /home/pantinor/data/repo/personal
   git mv voice_message_reader anti-vocale
   cd anti-vocale
   git commit -m "refactor: Rename directory to anti-vocale"
   git push origin main
   ```

2. **Update GitHub repository** (separate action):
   - Go to repo Settings → General → Repository name
   - Change from `voice_message_reader` to `anti-vocale`

**NOTE:** This task should be done LAST after all other renames are complete and tested.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 [ ] Repository directory renamed to anti_vocale or anti-vocale
- [ ] #2 [ ] Git history preserved
- [ ] #3 [ ] All references updated
- [ ] #4 [ ] .git directory intact
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Code compiles without errors or warnings
- [ ] #2 Feature tested on physical device or emulator
- [ ] #3 No regressions in existing functionality
- [ ] #4 Edge cases handled appropriately
- [ ] #5 UI follows Material Design guidelines
<!-- DOD:END -->
