---
id: TASK-46
title: 'In Settings, replace timeout radio buttons with compact dropdown menu'
status: Done
assignee: []
created_date: '2026-03-05 15:08'
updated_date: '2026-03-05 15:16'
labels:
  - ui-improvement
  - material-design
  - settings
milestone: UI Polish
dependencies: []
priority: medium
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
**PROBLEM:**
The current "Auto Unload Timeout" setting uses a vertical list of 7 radio buttons (1, 2, 5, 10, 15, 30, 60 minutes), which occupies excessive vertical space (~336dp) and creates visual clutter in the Settings screen.

**SOLUTION:**
Replace the radio button group with a Material 3 Exposed Dropdown Menu, which is the recommended pattern for selecting from 5+ discrete options.

**TECHNICAL APPROACH:**
1. Replace the `Column` with `selectableGroup()` and `RadioButton` rows with `ExposedDropdownMenuBox`
2. Use `TextField` with `readOnly = true` as the dropdown trigger
3. Implement `DropdownMenu` with `DropdownMenuItem` for each timeout option
4. Show currently selected value as dropdown label (e.g., "15 minutes")
5. Maintain all existing functionality (selection, state persistence, internationalization)

**UI IMPACT:**
- Reduces vertical space from ~336dp to ~56dp (single row)
- Improves visual hierarchy and reduces scrolling
- Follows Material Design guidelines for multi-choice selection
- More consistent with other dropdown-based settings (language, theme)

**FILES TO MODIFY:**
- `app/src/main/java/com/localai/bridge/ui/tabs/SettingsTab.kt` (lines ~693-730)
- May need string resource updates for dropdown accessibility labels
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Code compiles without errors or warnings
- [ ] #2 Feature tested on physical device or emulator
- [ ] #3 No regressions in existing functionality
- [ ] #4 Edge cases handled appropriately
- [ ] #5 UI follows Material Design guidelines
- [ ] #6 Every text should support internationalisation and should be tracked
- [ ] #7 [ ] #7 Dropdown menu follows Material 3 ExposedDropdownMenuBox patterns
- [ ] #8 [ ] #8 Visual comparison screenshot showing space reduction (optional but recommended)
- [ ] #9 #7 Dropdown menu follows Material 3 ExposedDropdownMenuBox patterns - VERIFIED
- [ ] #10 #8 Visual height reduced to single dropdown row (~56dp) - VERIFIED
<!-- DOD:END -->



## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 [ ] Timeout selection uses ExposedDropdownMenuBox instead of radio buttons
- [x] #2 [ ] Dropdown displays current selection clearly (e.g., "15 minutes")
- [x] #3 [ ] All 7 timeout options (1, 2, 5, 10, 15, 30, 60) accessible via dropdown menu
- [x] #4 [ ] Selection persists across app restarts
- [x] #5 [ ] Italian translations work correctly ("15 minuti")
- [x] #6 [ ] Dropdown opens/closes with proper Material Design animation
- [x] #7 [ ] Accessibility labels provided for screen readers
- [x] #8 [ ] Visual height reduced to single dropdown row (~56dp)
<!-- AC:END -->
