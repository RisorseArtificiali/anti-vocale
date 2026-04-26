# VAD Advisory Card Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Show a dismissable advisory card in the Log tab when Parakeet TDT is active and VAD silence stripping is enabled, suggesting the user turn VAD off.

**Architecture:** Reuse the OnboardingTooltip pattern (AnimatedVisibility + Card) as a new `VadAdvisoryCard` composable. Add a boolean preference for dismiss state. Combine three flows (activeBackendId, vadEnabled, vadAdvisoryDismissed) in LogsViewModel to control visibility. Insert as a conditional `item {}` at the top of LogsTab's LazyColumn.

**Tech Stack:** Kotlin, Jetpack Compose, DataStore Preferences, Hilt, StateFlow

---

### Task 1: Add preference key and accessors to PreferencesManager

**Files:**
- Modify: `app/src/main/java/com/antivocale/app/data/PreferencesManager.kt`

**Step 1: Add the preference key constant**

In the `companion object` block (after line 57, the `BENCHMARK_RESULTS` key), add:

```kotlin
// VAD advisory dismissed (for Parakeet + VAD advisory card)
private val VAD_ADVISORY_DISMISSED = booleanPreferencesKey("vad_advisory_dismissed")
```

**Step 2: Add field to CachedPreferences**

In the `CachedPreferences` data class (around line 94), add a field:

```kotlin
val vadAdvisoryDismissed: Boolean = false,
```

**Step 3: Map the key in toCached()**

In the `toCached()` function (around line 118), add to the constructor:

```kotlin
vadAdvisoryDismissed = this[VAD_ADVISORY_DISMISSED] ?: false,
```

**Step 4: Add Flow accessor**

After the existing `vadEnabled` section (after line 354), add:

```kotlin
val vadAdvisoryDismissed: Flow<Boolean> = context.dataStore.data.map { it.toCached().vadAdvisoryDismissed }
    .onStart { emit(cache.get().vadAdvisoryDismissed) }

suspend fun saveVadAdvisoryDismissed(dismissed: Boolean) {
    context.dataStore.edit { preferences ->
        preferences[VAD_ADVISORY_DISMISSED] = dismissed
    }
    cache.updateAndGet { it.copy(vadAdvisoryDismissed = dismissed) }
}
```

**Step 5: Commit**

```bash
git add app/src/main/java/com/antivocale/app/data/PreferencesManager.kt
git commit -m "feat: add VAD advisory dismissed preference to PreferencesManager"
```

---

### Task 2: Add string resources

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-it/strings.xml`

**Step 1: Add English strings**

In `values/strings.xml`, after the per-app onboarding strings (around line 383), add:

```xml
<!-- VAD Advisory (shown in Log tab when Parakeet is active with VAD enabled) -->
<string name="vad_advisory_title">VAD is not needed with Parakeet</string>
<string name="vad_advisory_description">Silence stripping adds processing overhead without improving results for this model. You can turn it off in Settings.</string>
<string name="vad_advisory_dismiss">Got it</string>
```

**Step 2: Add Italian strings**

In `values-it/strings.xml`, after the per-app onboarding strings (around line 379), add:

```xml
<!-- VAD Advisory (shown in Log tab when Parakeet is active with VAD enabled) -->
<string name="vad_advisory_title">VAD non necessario con Parakeet</string>
<string name="vad_advisory_description">La rimozione del silenzio aggiunge overhead senza migliorare i risultati per questo modello. Puoi disattivarlo nelle Impostazioni.</string>
<string name="vad_advisory_dismiss">Ho capito</string>
```

**Step 3: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml
git commit -m "feat: add VAD advisory string resources (EN + IT)"
```

---

### Task 3: Create VadAdvisoryCard composable

**Files:**
- Create: `app/src/main/java/com/antivocale/app/ui/components/VadAdvisoryCard.kt`

**Step 1: Create the component**

This follows the OnboardingTooltip pattern but with a warning/amber tint instead of the blue tertiary container. Uses `errorContainer`/`onErrorContainer` from Material theme to get a warm amber tone (Material 3's error container is orange-ish, not red, in most themes).

```kotlin
package com.antivocale.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.antivocale.app.R

@Composable
fun VadAdvisoryCard(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
        modifier = modifier
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.vad_advisory_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }

                Text(
                    text = stringResource(R.string.vad_advisory_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )

                Row(modifier = Modifier.align(Alignment.End)) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Text(stringResource(R.string.vad_advisory_dismiss))
                    }
                }
            }
        }
    }
}
```

**Step 2: Commit**

```bash
git add app/src/main/java/com/antivocale/app/ui/components/VadAdvisoryCard.kt
git commit -m "feat: add VadAdvisoryCard composable component"
```

---

### Task 4: Wire advisory state into LogsViewModel

**Files:**
- Modify: `app/src/main/java/com/antivocale/app/ui/viewmodel/LogsViewModel.kt`

**Step 1: Add imports**

Add these imports at the top (after existing imports):

```kotlin
import com.antivocale.app.transcription.TranscriptionBackendManager
import kotlinx.coroutines.flow.combine
```

Note: `combine` is already imported. Only `TranscriptionBackendManager` is new.

**Step 2: Add TranscriptionBackendManager dependency**

Modify the constructor to inject TranscriptionBackendManager. Since TranscriptionBackendManager is an `object` (singleton), we don't inject it via Hilt — we reference it directly. Add the combined state flow inside the ViewModel body:

```kotlin
val showVadAdvisory: StateFlow<Boolean> = combine(
    TranscriptionBackendManager.activeBackendId,
    preferencesManager.vadEnabled,
    preferencesManager.vadAdvisoryDismissed
) { backendId, vadEnabled, dismissed ->
    backendId == "sherpa-onnx" && vadEnabled && !dismissed
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

fun dismissVadAdvisory() {
    viewModelScope.launch {
        preferencesManager.saveVadAdvisoryDismissed(true)
    }
}
```

Note: `kotlinx.coroutines.launch` is already available from `viewModelScope`. Add `import kotlinx.coroutines.launch` if not present.

**Step 3: Commit**

```bash
git add app/src/main/java/com/antivocale/app/ui/viewmodel/LogsViewModel.kt
git commit -m "feat: add VAD advisory visibility state to LogsViewModel"
```

---

### Task 5: Insert VadAdvisoryCard into LogsTab

**Files:**
- Modify: `app/src/main/java/com/antivocale/app/ui/tabs/LogsTab.kt`

**Step 1: Add import**

At the top, add:

```kotlin
import com.antivocale.app.ui.components.VadAdvisoryCard
```

**Step 2: Read showVadAdvisory state**

Inside `LogsTab()`, after line 138 (`val context = LocalContext.current`), add:

```kotlin
val showVadAdvisory by viewModel.showVadAdvisory.collectAsState()
```

**Step 3: Insert VadAdvisoryCard as first item in LazyColumn**

In the LazyColumn content (around line 343, inside the `else` branch with the LazyColumn), add a new `item` block BEFORE the `groupedLogs.forEach`:

```kotlin
item(key = "vad_advisory") {
    VadAdvisoryCard(
        visible = showVadAdvisory,
        onDismiss = { viewModel.dismissVadAdvisory() },
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
    )
}
```

This goes right after the `LazyColumn(` declaration and its `contentPadding`, before the `groupedLogs.forEach` block.

**Step 4: Commit**

```bash
git add app/src/main/java/com/antivocale/app/ui/tabs/LogsTab.kt
git commit -m "feat: show VAD advisory card in Log tab when Parakeet is active with VAD on"
```

---

### Task 6: Build and verify

**Step 1: Build the project**

```bash
cd /home/pantinor/data/repo/personal/anti-vocale && ./gradlew assembleDebug 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

**Step 2: Fix any compilation errors**

If the build fails, read the error output and fix accordingly. Common issues:
- Missing import
- Wrong StateFlow type
- Wrong key name in PreferencesManager

**Step 3: Final commit (if any fixes needed)**

```bash
git add -A && git commit -m "fix: resolve compilation issues for VAD advisory card"
```

---

## Summary

| Task | What | Files |
|------|------|-------|
| 1 | PreferencesManager key + accessor | PreferencesManager.kt |
| 2 | String resources (EN + IT) | strings.xml, values-it/strings.xml |
| 3 | VadAdvisoryCard composable | VadAdvisoryCard.kt (new) |
| 4 | LogsViewModel state wiring | LogsViewModel.kt |
| 5 | LogsTab integration | LogsTab.kt |
| 6 | Build verification | — |
