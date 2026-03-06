---
id: TASK-47
title: Rename app from "Voice Message Reader" to "Anti-Vocale"
status: Done
assignee: []
created_date: '2026-03-05 15:27'
updated_date: '2026-03-05 15:58'
labels:
  - refactoring
  - branding
  - breaking-change
milestone: App Rebrand
dependencies: []
priority: high
ordinal: 15.625
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
**CONTEXT:**
The app is being renamed from "LocalAI Bridge" to "Anti-Vocale" (Italian for "Anti-Voice"). This is a complete branding change that must be reflected across all resources, documentation, and metadata.

**CURRENT STATE:**
- Display name: "LocalAI Bridge"
- Package: `com.localai.bridge` (50+ files reference this)
- Repo directory: `voice_message_reader`

**SCOPE:**
This comprehensive renaming affects:
1. **Display Name** (app_name in strings.xml)
2. **Directory name** (repository folder)
3. **Documentation** (CLAUDE.md, BUILD.md, backlog files)
4. **Memory files** (.serena/, memory/)
5. **Package references** in documentation (keep actual package unchanged to avoid breaking OAuth, Tasker, etc.)

**TECHNICAL DECISIONS:**
✅ **Keep package name** `com.localai.bridge` - Changing it would break:
   - HuggingFace OAuth redirect scheme
   - Tasker integration intents
   - SharedPreferences (would migrate users)
   - All 50+ source files

✅ **Display name:** "Anti-Vocale" (same in all languages - it's Italian)

**FILES TO MODIFY:**
1. `app/src/main/res/values/strings.xml` - app_name "LocalAI Bridge" → "Anti-Vocale"
2. `app/src/main/res/values-it/strings.xml` - app_name update
3. `CLAUDE.md` - Update all references
4. `docs/BUILD.md` - Update documentation
5. `.serena/memories/` - Update project references
6. `MEMORY.md` - Update memory file
7. Repository directory rename (git mv or manual)
8. All backlog task files with old name references

**IMPLEMENTATION PLAN:**
1. Update string resources (values/strings.xml, values-it/strings.xml)
2. Update documentation files
3. Update memory files
4. Rename repository directory
5. Update any hardcoded references in comments/logs
6. Test build and verify new name appears correctly
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 [ ] App displays "Anti-Vocale" as name in launcher and app switcher
- [ ] #2 [ ] All string resources updated (English and Italian)
- [ ] #3 [ ] Package name either kept or renamed consistently
- [ ] #4 [ ] No references to "Voice Message Reader" in source code
- [ ] #5 [ ] All documentation updated (README, CLAUDE.md, docs/)
- [ ] #6 [ ] Memory files updated with new app name
- [ ] #7 [ ] App builds and runs successfully with new name
- [ ] #8 [ ] Git history shows clear rename commit
- [ ] #9 [ ] backlog/task files updated (optional)
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Code compiles without errors or warnings
- [ ] #2 Feature tested on physical device or emulator
- [ ] #3 No regressions in existing functionality
- [ ] #4 Edge cases handled appropriately
- [ ] #5 UI follows Material Design guidelines
- [ ] #6 [ ] #7 No "Voice Message Reader" or "voice_message_reader" references remain
- [ ] #7 [ ] #8 All test flows pass with new branding
- [ ] #8 [ ] #9 APK installs with new app name (no conflict with old)
<!-- DOD:END -->
