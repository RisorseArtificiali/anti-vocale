# Settings Reorganization: Advanced Mode

**Date**: 2026-05-01
**Status**: Approved
**Driver**: User experience — settings screen has grown to 20+ options, overwhelming end users

## Problem

`SettingsTab.kt` is 2400 lines with ~20 configurable options rendered as a flat list. Users see everything at once with no visual hierarchy, making it hard to find the common settings they actually use.

## Solution

Reorganize settings into 2 always-visible sections + 1 collapsible "Advanced" section, using a reusable `CollapsibleSection` composable.

## Section Layout

### Transcription (always visible)
| Setting | Type |
|---------|------|
| Model Status Card | Info card (LLM backend only) |
| Active Model Selection | Card |
| Transcription Language | Dropdown |
| Auto-Copy | Toggle |
| VAD Silence Stripping | Toggle |
| Progressive Transcription | Toggle |
| Default Prompt | Navigation card |
| Keep-Alive Timeout | Dropdown |

### Appearance (always visible)
| Setting | Type |
|---------|------|
| Theme | Dropdown |
| App Language | Dropdown |
| Swipe Action | Dropdown |
| Log Grouping | Toggle |

### Advanced (collapsed by default)
| Setting | Type |
|---------|------|
| HuggingFace Auth | Card (OAuth + manual token) |
| Thread Count | Dropdown |
| Inference Provider | Dropdown |
| Advanced Sharing | Toggle |
| Per-App Settings | Navigation card |
| Performance Stats | Navigation card |

## Architecture

### New Component
`app/ui/components/CollapsibleSection.kt` — reusable composable:
- Params: `title`, `icon`, `initiallyExpanded`, `content`
- `AnimatedVisibility` for smooth expand/collapse
- `rememberSaveable` for state persistence during recomposition
- Collapsed state resets on app restart (always starts collapsed)

### Changes
1. **Create** `CollapsibleSection.kt` (~40 lines)
2. **Refactor** `SettingsTab.kt` — wrap each section in `CollapsibleSection`, reorder settings
3. **No changes** to ViewModel, PreferencesManager, or data layer — purely UI reorganization

### What Does NOT Change
- No new preferences or data storage
- No ViewModel changes
- Per-App Settings screen stays as-is (launched from Advanced section)
- Prompt editor screen stays as-is (launched from Transcription section)
- Performance stats dialog stays as-is (launched from Advanced section)

## Implementation Notes
- SettingsTab is currently 2400 lines — the refactor reorders existing composables into sections, no logic changes
- The CollapsibleSection composable should be extracted to `components/` for potential reuse
- Each section header uses a Material 3 `ListItem` with chevron icon and click-to-toggle
