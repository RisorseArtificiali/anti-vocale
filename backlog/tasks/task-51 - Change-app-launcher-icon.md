---
id: TASK-51
title: Change app launcher icon
status: To Do
assignee: []
created_date: '2026-03-05 22:50'
updated_date: '2026-03-06 08:21'
labels:
  - ui
  - branding
dependencies: []
priority: medium
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Replace the current default Android launcher icon with a custom icon for Anti-Vocale.

---

**⚠️ BRANCHING INSTRUCTIONS:**

You're currently on `feature/per-app-notifications` branch with per-app notification tasks. To work on this task:

1. **Stash your current work** (TASK-51 and other changes):
```bash
git stash push -m "WIP: launcher icon and other changes"
```

2. **Switch back to main branch**:
```bash
git checkout main
```

3. **Restore your work**:
```bash
git stash pop
```

4. **Work on TASK-51** (this task)

5. **When TASK-51 is complete**, return to feature branch:
```bash
git checkout feature/per-app-notifications
```

6. **Merge TASK-51 into feature branch** (optional):
```bash
git merge main
```

**Why stash?** Keeps your TASK-51 work safe while per-app notification tasks wait in their own branch.
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Code compiles without errors or warnings
- [ ] #2 Feature tested on physical device or emulator
- [ ] #3 No regressions in existing functionality
- [ ] #4 Edge cases handled appropriately
- [ ] #5 UI follows Material Design guidelines
- [ ] #6 Every text should support internationalisation and should be tracked
<!-- DOD:END -->
