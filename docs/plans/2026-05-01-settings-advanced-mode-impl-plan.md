# Settings Advanced Mode — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Reorganize the flat SettingsTab into 3 collapsible sections (Transcription, Appearance, Advanced) so users see common settings first and power-user options are one tap away.

**Architecture:** Create a reusable `CollapsibleSection` composable that wraps groups of settings cards with an animated expand/collapse header. Reorder existing settings cards in `SettingsTab.kt` into 3 sections — no logic changes, purely UI restructuring.

**Tech Stack:** Jetpack Compose (Material 3), AnimatedVisibility, rememberSaveable

---

### Task 1: Create CollapsibleSection Composable

**Files:**
- Create: `app/src/main/java/com/antivocale/app/ui/components/CollapsibleSection.kt`

**Step 1: Create the composable**

```kotlin
package com.antivocale.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun CollapsibleSection(
    title: String,
    initiallyExpanded: Boolean = true,
    content: @Composable () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        AnimatedVisibility(visible = expanded) {
            Column {
                content()
            }
        }
    }
}
```

**Step 2: Verify it compiles**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add app/src/main/java/com/antivocale/app/ui/components/CollapsibleSection.kt
git commit -m "feat: add CollapsibleSection composable for settings reorganization"
```

---

### Task 2: Reorder SettingsTab into Sections

This is the main refactor. The current `SettingsTab.kt` has settings in this order (by line):

```
125:  Model Status Card
189:  Active Model Selection Card
260:  HuggingFace Token Card
685:  Keep-Alive Timeout
822:  Thread Count
907:  Inference Provider
994:  Auto-Copy
1038: VAD Silence Stripping
1082: Progressive Transcription
1126: Conversation Grouping
1170: Swipe Action
1250: App Language
1339: Transcription Language
1427: Theme
1502: Default Prompt
1546: Per-App Settings
1590: Advanced Sharing
1650: Performance Stats
```

The target order groups them into 3 `CollapsibleSection` blocks:

```
CollapsibleSection("Transcription", initiallyExpanded = true) {
    Model Status Card            // was line 125
    Active Model Selection Card  // was line 189
    Transcription Language       // was line 1339
    Auto-Copy                    // was line 994
    VAD Silence Stripping        // was line 1038
    Progressive Transcription    // was line 1082
    Default Prompt               // was line 1502
    Keep-Alive Timeout           // was line 685
}

CollapsibleSection("Appearance", initiallyExpanded = true) {
    Theme                        // was line 1427
    App Language                 // was line 1250
    Swipe Action                 // was line 1170
    Conversation Grouping        // was line 1126
}

CollapsibleSection("Advanced", initiallyExpanded = false) {
    HuggingFace Token Card       // was line 260
    Thread Count                 // was line 822
    Inference Provider           // was line 907
    Advanced Sharing             // was line 1590
    Per-App Settings             // was line 1546
    Performance Stats            // was line 1650
}
```

**Files:**
- Modify: `app/src/main/java/com/antivocale/app/ui/tabs/SettingsTab.kt`

**Step 1: Add the import for CollapsibleSection**

At the top of `SettingsTab.kt`, add:
```kotlin
import com.antivocale.app.ui.components.CollapsibleSection
```

**Step 2: Reorder the settings cards inside the main Column**

The main `Column` starts at line 117 and its content ends at line 1716 (before the closing `}`).

Replace the entire body of the Column (lines 125-1715) with the 3 sectioned groups. Each setting card is moved verbatim — no logic changes, just reordering and wrapping in `CollapsibleSection { }`.

The structure becomes:
```kotlin
Column(
    modifier = Modifier
        .fillMaxSize()
        .navigationBarsPadding()
        .verticalScroll(scrollState)
        .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
) {
    // ===== Transcription Section =====
    CollapsibleSection(
        title = stringResource(R.string.settings_section_transcription),
        initiallyExpanded = true
    ) {
        // Model Status Card (lines 125-188)
        if (isLlmBackend) { ... }

        // Active Model Selection Card (lines 189-259)
        Card { ... }

        // Transcription Language (lines 1339-1426)
        Card { ... }

        // Auto-Copy Setting (lines 994-1037)
        Card { ... }

        // VAD Silence Stripping Setting (lines 1038-1081)
        Card { ... }

        // Progressive Transcription Display Setting (lines 1082-1125)
        Card { ... }

        // Default Prompt Setting Navigation Card (lines 1502-1545)
        Card { ... }

        // Keep-Alive Timeout Setting (lines 685-821)
        Card { ... }
    }

    // ===== Appearance Section =====
    CollapsibleSection(
        title = stringResource(R.string.settings_section_appearance),
        initiallyExpanded = true
    ) {
        // Theme Setting (lines 1427-1501)
        Card { ... }

        // Language Setting (lines 1250-1338)
        Card { ... }

        // Swipe Action Setting (lines 1170-1249)
        Card { ... }

        // Conversation Grouping Setting (lines 1126-1169)
        Card { ... }
    }

    // ===== Advanced Section =====
    CollapsibleSection(
        title = stringResource(R.string.settings_section_advanced),
        initiallyExpanded = false
    ) {
        // HuggingFace Token Card (lines 260-684)
        Card { ... }

        // Thread Count Setting (lines 822-906)
        Card { ... }

        // Inference Provider Setting (lines 907-993)
        Card { ... }

        // Advanced Sharing Card (lines 1590-1649)
        Card { ... }

        // Per-App Settings Navigation Card (lines 1546-1589)
        Card { ... }

        // Performance Stats Card (lines 1650-1698)
        Card { ... }
    }

    // Performance Stats Dialog (stays outside sections)
    if (showPerfStatsDialog) { ... }

    Spacer(modifier = Modifier.height(32.dp))
}
```

**IMPORTANT:** Every setting card is copied verbatim. No logic, state, or callback changes. Only the ordering and wrapping changes.

**Step 3: Build and verify**

Run: `./scripts/install.sh`
Expected: App installs and launches. Settings tab shows 3 sections with Transcription and Appearance expanded, Advanced collapsed.

**Step 4: Commit**

```bash
git add app/src/main/java/com/antivocale/app/ui/tabs/SettingsTab.kt
git commit -m "refactor: reorganize settings into Transcription, Appearance, and Advanced sections"
```

---

### Task 3: Add String Resources for Section Titles

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

**Step 1: Add section title strings**

Add these entries to `strings.xml`:
```xml
<string name="settings_section_transcription">Transcription</string>
<string name="settings_section_appearance">Appearance</string>
<string name="settings_section_advanced">Advanced</string>
```

If an Italian `strings.xml` exists, add:
```xml
<string name="settings_section_transcription">Trascrizione</string>
<string name="settings_section_appearance">Aspetto</string>
<string name="settings_section_advanced">Avanzate</string>
```

**Step 2: Verify build**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add app/src/main/res/values/strings.xml
git commit -m "feat: add string resources for settings section titles"
```

---

### Task 4: Manual Testing on Device

**Step 1: Install on device**

Run: `./scripts/install.sh`

**Step 2: Verify behavior**

Checklist:
- [ ] Settings tab shows 3 section headers: Transcription, Appearance, Advanced
- [ ] Transcription section is expanded by default with 8 settings
- [ ] Appearance section is expanded by default with 4 settings
- [ ] Advanced section is collapsed by default — only header visible
- [ ] Tapping "Advanced" header expands it to show 6 settings
- [ ] Tapping "Transcription" header collapses it
- [ ] All toggles, dropdowns, and navigation cards work as before
- [ ] Per-App Settings screen still opens correctly
- [ ] Prompt editor screen still opens correctly
- [ ] Performance Stats dialog still opens correctly
- [ ] HuggingFace auth (OAuth + manual) still works
- [ ] Scroll works smoothly through all sections
- [ ] Section collapse state resets on app restart (Advanced starts collapsed)

**Step 3: Commit any fixes**

If any issues found during testing, fix and commit.

---

## Execution Order

Tasks 1 and 3 are independent and can run in parallel. Task 2 depends on both. Task 4 depends on all.

```
Task 1 (CollapsibleSection) ──┐
                               ├── Task 2 (Reorder) ── Task 4 (Test)
Task 3 (String resources)  ───┘
```
